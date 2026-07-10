package org.cashu.wallet.Core

import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.cashu.wallet.Core.Wallet.userFacingWalletMessage
import org.cashu.wallet.Models.PendingReceiveToken

data class CashuRequestListenerState(
    val isRunning: Boolean = false,
    val lastError: String? = null,
    /** One-shot prompt only; the durable payment lives in pending-receive storage. */
    val heldForApproval: PendingReceiveToken? = null,
)

internal enum class CashuRequestClaimAction {
    ClaimSilently,
    HoldForApproval,
}

internal object CashuRequestApprovalPolicy {
    fun action(autoClaim: Boolean, mintKnown: Boolean): CashuRequestClaimAction =
        if (autoClaim && mintKnown) {
            CashuRequestClaimAction.ClaimSilently
        } else {
            CashuRequestClaimAction.HoldForApproval
        }
}

/**
 * Foreground NUT-18 receive listener.
 *
 * Matches iOS trust semantics: a payment is only claimed without interaction
 * when automatic claiming is enabled and its mint is already tracked. Every
 * other valid payment is stored as a pending receive before its relay event is
 * acknowledged, so it survives restarts and remains claimable from History.
 */
class CashuRequestListener(
    private val nostrService: NostrService,
    private val settingsManager: SettingsManager,
    private val walletManager: WalletManager,
    private val walletStore: WalletStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var client: NostrInboxClient? = null
    private val eventsInFlight = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var walletGeneration = 0L
    private val mutableState = MutableStateFlow(CashuRequestListenerState())
    val state: StateFlow<CashuRequestListenerState> = mutableState.asStateFlow()

    fun start() {
        if (!settingsManager.state.value.enablePaymentRequests) {
            stop()
            return
        }
        if (client != null) return
        val nostr = nostrService.state.value
        val privateKeyHex = nostrService.currentPrivateKey()
        if (!nostr.isInitialized || nostr.publicKeyHex.isBlank() || privateKeyHex.isNullOrBlank()) {
            mutableState.value = mutableState.value.copy(lastError = "Nostr key is not initialized.")
            return
        }
        val relays = settingsManager.state.value.nostrRelays
            .map(String::trim)
            .filter { it.startsWith("ws://") || it.startsWith("wss://") }
            .distinct()
        if (relays.isEmpty()) {
            mutableState.value = mutableState.value.copy(lastError = "No Nostr relays configured.")
            return
        }

        // NIP-59 gift wraps can be backdated by roughly two days. Re-scan a
        // fixed wide window on each start and de-duplicate by event id instead
        // of advancing a `since` cursor that could skip a later backdated wrap.
        val since = (System.currentTimeMillis() / 1000) - LookbackWindowSeconds
        val recipientPrivateKey = NIP44.hexToBytes(privateKeyHex)
        val generation = walletGeneration
        client = NostrInboxClient(
            pubkeyHex = nostr.publicKeyHex,
            relays = relays,
            since = since,
        ) { event ->
            handle(event, recipientPrivateKey, generation)
        }.also { it.start() }
        mutableState.value = mutableState.value.copy(isRunning = true, lastError = null)
        AppLogger.wallet.info("CashuRequestListener: started on ${relays.size} relays")
    }

    fun stop() {
        client?.stop()
        client = null
        mutableState.value = mutableState.value.copy(isRunning = false)
    }

    /** Invalidate callbacks before the wallet repository starts changing. */
    fun pauseForWalletBoundary() {
        walletGeneration += 1
        stop()
        eventsInFlight.clear()
    }

    /** Clear identity-bound listener state after create/restore/delete. */
    fun resetForWalletBoundary() {
        pauseForWalletBoundary()
        walletStore.saveProcessedNIP17EventIds(emptyList())
        mutableState.value = CashuRequestListenerState()
    }

    private suspend fun handle(
        event: NostrIncomingEvent,
        recipientPrivateKey: ByteArray,
        generation: Long,
    ) {
        if (event.kind != 1059 || isProcessed(event.id) || !eventsInFlight.add(event.id)) return
        try {
            val rumor = runCatching { NIP17.unwrap(event, recipientPrivateKey) }
                .onFailure { AppLogger.wallet.debug("CashuRequestListener: NIP-17 unwrap failed") }
                .getOrNull()
            if (rumor == null || rumor.kind != 14) {
                if (generation == walletGeneration) markProcessed(event.id)
                return
            }
            if (generation != walletGeneration) return

            when (tryClaim(rumor.content)) {
                ClaimOutcome.Claimed,
                ClaimOutcome.Held,
                ClaimOutcome.Unclaimable -> if (generation == walletGeneration) markProcessed(event.id)
                ClaimOutcome.TransientFailure -> Unit
            }
        } finally {
            eventsInFlight.remove(event.id)
        }
    }

    private enum class ClaimOutcome {
        Claimed,
        Held,
        Unclaimable,
        TransientFailure,
    }

    private suspend fun tryClaim(content: String): ClaimOutcome {
        val payload = runCatching { paymentPayloadToToken(content) }
            .onFailure { AppLogger.wallet.debug("CashuRequestListener: malformed payment payload") }
            .getOrNull() ?: return ClaimOutcome.Unclaimable

        return when (
            CashuRequestApprovalPolicy.action(
                autoClaim = settingsManager.state.value.receivePaymentRequestsAutomatically,
                mintKnown = walletManager.isMintKnown(payload.mintUrl),
            )
        ) {
            CashuRequestClaimAction.ClaimSilently -> claimNow(payload)
            CashuRequestClaimAction.HoldForApproval -> holdForApproval(payload)
        }
    }

    private suspend fun claimNow(payload: PaymentPayloadToken): ClaimOutcome = try {
        walletManager.receiveCashuRequestPayment(
            tokenString = payload.token,
            requestId = payload.requestId,
        )
        ClaimOutcome.Claimed
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        AppLogger.wallet.error("CashuRequestListener: redeem failed; will retry", error)
        mutableState.value = mutableState.value.copy(lastError = error.userFacingWalletMessage)
        ClaimOutcome.TransientFailure
    }

    private suspend fun holdForApproval(payload: PaymentPayloadToken): ClaimOutcome {
        val pending = walletManager.state.value.pendingReceiveTokens
        if (pending.any { it.token == payload.token }) return ClaimOutcome.Held
        if (pending.count { it.cashuRequestId != null } >= MaxHeldPayments) {
            AppLogger.wallet.info("CashuRequestListener: held-payment backlog full")
            return ClaimOutcome.TransientFailure
        }

        val held = PendingReceiveToken(
            tokenId = UUID.randomUUID().toString(),
            token = payload.token,
            amount = payload.amount,
            dateEpochMillis = System.currentTimeMillis(),
            mintUrl = payload.mintUrl,
            unit = payload.unit,
            cashuRequestId = payload.requestId ?: "",
            memo = payload.memo,
        )
        walletManager.savePendingReceiveToken(held)
        walletManager.loadTransactions()
        mutableState.value = mutableState.value.copy(heldForApproval = held, lastError = null)
        return ClaimOutcome.Held
    }

    suspend fun claimHeldPayment(pending: PendingReceiveToken): Long {
        val amount = walletManager.claimPendingReceiveToken(pending)
        if (mutableState.value.heldForApproval?.tokenId == pending.tokenId) {
            mutableState.value = mutableState.value.copy(heldForApproval = null)
        }
        claimEligibleHeldPayments()
        return amount
    }

    fun declineHeldPayment(pending: PendingReceiveToken) {
        walletManager.removePendingReceiveToken(pending.tokenId)
        if (mutableState.value.heldForApproval?.tokenId == pending.tokenId) {
            mutableState.value = mutableState.value.copy(heldForApproval = null)
        }
        scope.launch { walletManager.loadTransactions() }
    }

    /** Hide only the one-shot prompt; the durable History item remains. */
    fun dismissHeldPayment() {
        mutableState.value = mutableState.value.copy(heldForApproval = null)
    }

    /** Claim known-mint payments that became eligible after auto-claim was enabled. */
    suspend fun claimEligibleHeldPayments() {
        if (!settingsManager.state.value.receivePaymentRequestsAutomatically) return
        val eligible = walletManager.state.value.pendingReceiveTokens.filter {
            it.cashuRequestId != null && walletManager.isMintKnown(it.mintUrl)
        }
        eligible.forEach { pending ->
            try {
                walletManager.claimPendingReceiveToken(pending)
                if (mutableState.value.heldForApproval?.tokenId == pending.tokenId) {
                    mutableState.value = mutableState.value.copy(heldForApproval = null)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                AppLogger.wallet.error("CashuRequestListener: held payment remains claimable", error)
            }
        }
    }

    private fun isProcessed(id: String): Boolean = id in walletStore.loadProcessedNIP17EventIds()

    private fun markProcessed(id: String) {
        val current = walletStore.loadProcessedNIP17EventIds()
        if (id in current) return
        walletStore.saveProcessedNIP17EventIds((current + id).takeLast(MaxProcessedEventIds))
    }

    data class PaymentPayloadToken(
        val token: String,
        val requestId: String?,
        val mintUrl: String,
        val amount: Long,
        val unit: String,
        val memo: String?,
    )

    companion object {
        private const val LookbackWindowSeconds = 7L * 24 * 60 * 60
        private const val MaxProcessedEventIds = 1_000
        private const val MaxHeldPayments = 50
        private val payloadJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun paymentPayloadToToken(content: String): PaymentPayloadToken {
            val fields = payloadJson.parseToJsonElement(content).jsonObject
            val mintUrl = fields["mint"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("Payment payload mint missing.")
            val proofs = fields["proofs"]?.jsonArray
                ?: throw IllegalArgumentException("Payment payload proofs missing.")
            val unit = fields["unit"]?.jsonPrimitive?.contentOrNull ?: "sat"
            val memo = fields["memo"]?.jsonPrimitive?.contentOrNull
            val token = JsonObject(
                buildMap {
                    put(
                        "token",
                        JsonArray(
                            listOf(
                                JsonObject(
                                    mapOf(
                                        "mint" to JsonPrimitive(mintUrl),
                                        "proofs" to proofs,
                                    ),
                                ),
                            ),
                        ),
                    )
                    put("unit", JsonPrimitive(unit))
                    if (!memo.isNullOrBlank()) put("memo", JsonPrimitive(memo))
                },
            )
            val encoded = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(payloadJson.encodeToString(token).toByteArray(Charsets.UTF_8))
            return PaymentPayloadToken(
                token = "cashuA$encoded",
                requestId = fields["id"]?.jsonPrimitive?.contentOrNull,
                mintUrl = mintUrl,
                amount = proofs.sumOf { proof ->
                    proof.jsonObject["amount"]?.jsonPrimitive?.longOrNull?.coerceAtLeast(0) ?: 0L
                },
                unit = unit,
                memo = memo,
            )
        }
    }
}
