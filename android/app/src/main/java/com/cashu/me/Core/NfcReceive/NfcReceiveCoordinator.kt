package com.cashu.me.Core.NfcReceive

import android.content.Context
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import com.cashu.me.Core.CashuRequestStore
import com.cashu.me.Core.WalletManager
import com.cashu.me.Core.PaymentRequestBuilder
import com.cashu.me.Models.CashuRequest
import org.cashudevkit.Token as CdkToken

enum class NfcReceivePhase {
    Unavailable,
    Disabled,
    NeedsAmount,
    Inactive,
    Waiting,
    Connected,
    Receiving,
    Validating,
    Redeeming,
    Converting,
    Success,
    Failure,
}

data class NfcReceiveState(
    val phase: NfcReceivePhase = NfcReceivePhase.Inactive,
    val message: String? = null,
    val amount: Long? = null,
    val sourceMint: String? = null,
    val settlementMint: String? = null,
)

/**
 * Application-scoped bridge between the Android HCE transport and wallet logic.
 * This is the only feature class allowed to know about both sides.
 */
class NfcReceiveCoordinator(
    context: Context,
    private val walletManager: WalletManager,
    private val requestStore: CashuRequestStore,
) {
    private data class Session(val request: CashuRequest, val settlementMint: String)

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutableState = MutableStateFlow(initialState())
    val state: StateFlow<NfcReceiveState> = mutableState.asStateFlow()
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var session: Session? = null
    @Volatile private var processing = false
    @Volatile private var armed = false

    val isAdvertising: Boolean get() = armed && session != null

    internal val type4Tag = NfcType4Tag(
        requestFile = {
            session?.request?.takeIf { armed }?.let { request ->
                NfcNdefCodec.textFile(
                    PaymentRequestBuilder.buildNfc(
                        id = request.id,
                        amount = request.amount,
                        unit = request.unit,
                        mints = request.mints,
                        description = request.memo,
                    ),
                )
            }
        },
        onEvent = ::onTransportEvent,
    )

    fun activate(request: CashuRequest, settlementMint: String?) {
        val availability = initialState()
        if (availability.phase == NfcReceivePhase.Unavailable || availability.phase == NfcReceivePhase.Disabled) {
            session = null
            armed = false
            mutableState.value = availability
            return
        }
        if (!request.shouldOfferNfcReceive()) {
            session = null
            armed = false
            mutableState.value = NfcReceiveState(NfcReceivePhase.Inactive)
            return
        }
        if (!request.canReceiveByNfc()) {
            session = null
            armed = false
            mutableState.value = NfcReceiveState(
                NfcReceivePhase.NeedsAmount,
                "Add an amount to enable Tap to receive.",
            )
            return
        }
        val target = settlementMint?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        if (target == null) {
            session = null
            armed = false
            mutableState.value = NfcReceiveState(NfcReceivePhase.Unavailable, "Choose an active mint to receive by NFC.")
            return
        }
        session = Session(request, target)
        armed = true
        if (!processing) mutableState.value = NfcReceiveState(NfcReceivePhase.Waiting)
    }

    fun deactivate() {
        session = null
        armed = false
        type4Tag.deactivate()
        if (!processing) mutableState.value = NfcReceiveState(NfcReceivePhase.Inactive)
    }

    fun clearResult() {
        if (session != null && !processing) {
            armed = true
            mutableState.value = NfcReceiveState(NfcReceivePhase.Waiting)
        }
    }

    internal fun onTransportTimeout(wasWriting: Boolean) {
        if (session == null || processing) return
        if (wasWriting) {
            armed = false
            mutableState.value = NfcReceiveState(
                NfcReceivePhase.Failure,
                "Connection lost while receiving. Keep the phones together and try again.",
            )
        } else {
            mutableState.value = NfcReceiveState(NfcReceivePhase.Waiting)
        }
    }

    private fun initialState(): NfcReceiveState {
        val manager = appContext.packageManager
        val adapter = NfcAdapter.getDefaultAdapter(appContext)
        return when {
            adapter == null || !manager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION) ->
                NfcReceiveState(NfcReceivePhase.Unavailable, "Tap to receive is not available on this device.")
            !adapter.isEnabled -> NfcReceiveState(NfcReceivePhase.Disabled, "Turn on NFC to receive by tap.")
            else -> NfcReceiveState(NfcReceivePhase.Inactive)
        }
    }

    private fun onTransportEvent(event: NfcType4Event) {
        if (session == null || !armed) return
        when (event) {
            NfcType4Event.Connected -> if (!processing) {
                mutableState.value = NfcReceiveState(NfcReceivePhase.Connected, "Phone detected")
            }
            NfcType4Event.WritingStarted -> if (!processing) {
                mutableState.value = NfcReceiveState(NfcReceivePhase.Receiving, "Keep phones together")
            }
            is NfcType4Event.MessageReceived -> submit(event.payload)
        }
    }

    private fun submit(payload: NfcNdefPayload) {
        val snapshot = session ?: return
        if (processing || !armed) return
        armed = false
        processing = true
        scope.launch {
            var fingerprint: String? = null
            try {
                val token = when (payload) {
                    is NfcNdefPayload.Text -> extractToken(payload.value)
                    is NfcNdefPayload.CashuBinary -> CdkToken.fromRawBytes(payload.bytes).encode()
                } ?: error("No valid Cashu token was received.")
                fingerprint = tokenFingerprint(token)
                require(inFlight.add(fingerprint)) { "This payment is already being received." }
                process(snapshot, token, fingerprint)
            } catch (error: Throwable) {
                mutableState.value = NfcReceiveState(
                    phase = NfcReceivePhase.Failure,
                    message = error.message ?: "NFC payment failed.",
                )
            } finally {
                processing = false
                fingerprint?.let(inFlight::remove)
            }
        }
    }

    private suspend fun process(session: Session, tokenString: String, fingerprint: String) {
        mutableState.value = NfcReceiveState(NfcReceivePhase.Validating, "Checking payment")
        val token = CdkToken.decode(tokenString)
        val sourceMint = normalizeNfcMint(token.mintUrl().url)
        val unit = token.unit()?.toUnitString() ?: "sat"
        val grossAmount = token.value().value.toLong()
        val route = validateNfcReceiveTerms(
            request = session.request,
            sourceMint = sourceMint,
            tokenUnit = unit,
            grossAmount = grossAmount,
            settlementMint = session.settlementMint,
        )
        val (amountReceived, transactionId) = if (route == NfcSettlementRoute.Direct) {
            mutableState.value = NfcReceiveState(NfcReceivePhase.Redeeming, "Securing ecash", sourceMint = sourceMint)
            walletManager.receiveNfcCashuRequestPayment(
                tokenString = tokenString,
                processedId = fingerprint,
            ).let { it.amountReceived to it.transactionId }
        } else {
            mutableState.value = NfcReceiveState(
                NfcReceivePhase.Converting,
                "Moving payment to your active mint",
                sourceMint = sourceMint,
                settlementMint = session.settlementMint,
            )
            walletManager.settleForeignNfcToken(tokenString, session.settlementMint, fingerprint)
                .let { it.amountReceived to it.transactionId }
        }
        require(amountReceived > 0) { "The payment was not credited." }
        requestStore.attachPayment(session.request.id, transactionId, amountReceived)
        mutableState.value = NfcReceiveState(
            phase = NfcReceivePhase.Success,
            message = "Payment received",
            amount = amountReceived,
            sourceMint = sourceMint,
            settlementMint = session.settlementMint,
        )
    }

    private fun extractToken(value: String): String? {
        val start = listOf("cashuA", "cashuB", "crawB")
            .map(value::indexOf)
            .filter { it >= 0 }
            .minOrNull() ?: return null
        return value.substring(start).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' || it == '=' }
            .takeIf { it.startsWith("cashu") || it.startsWith("craw") }
    }

    private fun tokenFingerprint(token: String): String = "nfc:" + MessageDigest.getInstance("SHA-256")
        .digest(token.toByteArray(Charsets.UTF_8))
        .take(16)
        .joinToString("") { "%02x".format(it) }

    private fun org.cashudevkit.CurrencyUnit.toUnitString(): String = when (this) {
        is org.cashudevkit.CurrencyUnit.Sat -> "sat"
        is org.cashudevkit.CurrencyUnit.Msat -> "msat"
        is org.cashudevkit.CurrencyUnit.Usd -> "usd"
        is org.cashudevkit.CurrencyUnit.Eur -> "eur"
        is org.cashudevkit.CurrencyUnit.Custom -> unit
        else -> toString().lowercase()
    }
}
