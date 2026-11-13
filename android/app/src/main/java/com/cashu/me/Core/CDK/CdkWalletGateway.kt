package com.cashu.me.Core.CDK

import java.net.URI
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow
import org.cashudevkit.PendingMelt
import com.cashu.me.Core.NPCQuote
import com.cashu.me.Models.MeltPaymentResult
import com.cashu.me.Models.MeltQuoteInfo
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.RestoreMintResult
import com.cashu.me.Models.ReceiveTokenPreview
import com.cashu.me.Models.SendTokenResult
import com.cashu.me.Models.WalletTransaction

interface CdkWalletGateway {
    suspend fun initializeLogging(level: String = "info")
    suspend fun generateMnemonic(): String
    suspend fun mnemonicEntropy(mnemonic: String): ByteArray
    suspend fun validateMnemonic(mnemonic: String): Boolean
    suspend fun openWalletRepository(mnemonic: String, databasePath: String)
    suspend fun closeWalletRepository()

    /** Whether the repository currently tracks any mint wallets. */
    suspend fun hasWallets(): Boolean

    /** NUT-27: publish the encrypted mint-list backup for the open seed to the given relays. */
    suspend fun backupMints(relays: List<String>, client: String)

    /** NUT-27: fetch the newest mint-list backup for the open seed; returns the backed-up mint URLs. */
    suspend fun fetchMintBackup(relays: List<String>, timeoutSecs: ULong): List<String>
    suspend fun ensureWallet(mintUrl: String, unit: String = "sat")

    /**
     * Removes every unit wallet registered for [mintUrl] from the native
     * repository. Returns false when no native wallet existed.
     */
    suspend fun removeWallets(mintUrl: String): Boolean
    suspend fun fetchMintInfo(mintUrl: String): MintInfo?
    suspend fun restoreMint(mintUrl: String): RestoreMintResult
    /** Local CDK accounts, independent of the mint's current advertisements. */
    suspend fun storedAccounts(): List<WalletAccountReference>
    suspend fun storedAccountBalance(account: WalletAccountReference): Long
    suspend fun totalBalance(mintUrl: String): Long

    /** Balance of the (mint, unit) wallet, registering the unit wallet if needed. */
    suspend fun unitBalance(mintUrl: String, unit: String): Long

    suspend fun createMintQuote(amount: Long?, method: PaymentMethodKind, mintUrl: String, unit: String = "sat", description: String? = null): MintQuoteInfo
    suspend fun checkMintQuote(quoteId: String): MintQuoteInfo

    /** Explicitly approved recovery; never invoked by ordinary seed restore. */
    suspend fun recoverDepositKey(request: com.cashu.me.Core.DepositRecoveryRequest): com.cashu.me.Core.DepositRecoveryLookup =
        throw UnsupportedOperationException("Deposit recovery is not available.")

    /** Last durable local quote snapshot, without contacting the mint. */
    suspend fun storedMintQuote(quoteId: String): MintQuoteInfo? = null

    /** Push updates may trigger CDK saga recovery, so callers can defer the
     * network refresh while their persisted retry deadline is in the future. */
    fun subscribeToMintQuote(quoteId: String, mayRefresh: () -> Boolean = { true }): Flow<MintQuoteInfo>
    suspend fun listUnissuedMintQuotes(): List<MintQuoteInfo>
    suspend fun mintTokens(quoteId: String): Long
    suspend fun mintNPCQuote(quote: NPCQuote, p2pkPubkey: String?): Long
    suspend fun createMeltQuote(request: String, amountSats: Long? = null, preferredMintURL: String? = null): MeltQuoteInfo
    suspend fun listMeltQuotes(): List<MeltQuoteInfo>
    suspend fun meltTokens(quoteId: String, mintUrl: String? = null): MeltConfirmation

    /** Re-check a melt quote against the mint (NUT-05 async settlement follow-up). */
    suspend fun checkMeltQuoteStatus(quoteId: String, mintUrl: String? = null): MeltQuoteInfo

    /**
     * Ask CDK to complete or compensate interrupted wallet sagas for a mint
     * (e.g. a melt the process never saw the outcome of). iOS startup
     * maintenance parity.
     */
    suspend fun recoverIncompleteSagas(mintUrl: String): SagaRecoveryReport

    /** Durable receive evidence only; never includes preview-only wallets. */
    suspend fun receiveRecoveryCandidates(): List<ReceiveRecoveryCandidate>
    suspend fun recoverReceiveAccount(candidate: ReceiveRecoveryCandidate): SagaRecoveryReport
    suspend fun sendEcashToken(amount: Long, memo: String?, p2pkPubkey: String?, mintUrl: String, unit: String = "sat", p2pkSigningKeys: List<String> = emptyList()): SendTokenResult

    suspend fun sendContactlessToken(amount: Long, mintUrl: String, p2pkSigningKeys: List<String>,
        callbacks: com.cashu.me.Core.Services.ContactlessSendCallbacks): SendTokenResult =
        throw UnsupportedOperationException("Contactless payments are unavailable.")
    suspend fun receiveEcashToken(tokenString: String, p2pkSigningKeys: List<String> = emptyList()): Long
    suspend fun receiveNfcEcashToken(
        tokenString: String,
        p2pkSigningKeys: List<String> = emptyList(),
    ): NfcReceiveReceipt
    suspend fun settleForeignNfcToken(tokenString: String, settlementMintUrl: String): ForeignNfcSettlement
    suspend fun previewReceiveToken(tokenString: String): ReceiveTokenPreview

    /** Active sat keyset input fee in parts per thousand, or null when unavailable. */
    suspend fun activeMintInputFeePpk(mintUrl: String): Long?

    /**
     * Exact input fee for paying a Cashu Request from [mintUrl].
     *
     * The preview must use the same `includeFee = true` coin selection as
     * `payCashuPaymentRequest`, then release any prepared proofs.
     */
    suspend fun estimateCashuPaymentRequestFee(amountSats: Long, mintUrl: String): Long
    suspend fun checkTokenSpendable(token: String, mintUrl: String): Boolean
    suspend fun listTransactions(unitsByMint: Map<String, List<String>>): List<WalletTransaction>
    suspend fun payCashuPaymentRequest(encoded: String, customAmountSats: Long?, preferredMintURL: String?)

    suspend fun checkSendRecovery(context: com.cashu.me.Core.PendingSendRecovery): com.cashu.me.Core.PendingSendRecovery =
        throw UnsupportedOperationException("Send recovery is unavailable.")

    /**
     * CDK 0.18 send lifecycle: operation ids of outgoing sends whose token is
     * still unclaimed (their transactions read as Pending).
     */
    suspend fun listPendingSendOperationIds(mintUrl: String, unit: String = "sat"): List<String>

    /**
     * Ask the mint whether a pending send's token was claimed; CDK flips its
     * transaction to Completed when it was. Returns the claim state.
     */
    suspend fun checkPendingSendClaimed(mintUrl: String, operationId: String, unit: String = "sat"): Boolean

    /**
     * Revoke an unclaimed send: CDK swaps the proofs back and marks the
     * transaction Failed. Returns the recovered amount in [unit] base units.
     */
    suspend fun revokePendingSend(mintUrl: String, operationId: String, unit: String = "sat"): Long

    /**
     * CDK 0.18: refresh every unissued mint quote of one wallet against its
     * mint and mint the outstanding (paid, not yet issued) amounts — including
     /// reusable BOLT12 offers. Returns the total newly minted in [unit] base units.
     */
    suspend fun mintUnissuedQuotes(mintUrl: String, unit: String = "sat"): Long

    /** Extract the encoded token a send saga persists until the token is
     * claimed; null for non-send or already-finalized operations. */
    suspend fun pendingSendTokenFromSaga(operationId: String): String?

    /**
     * Quote the largest amount [sourceMintUrl] can move to [destinationMintUrl]
     * in one Lightning payment: a BOLT11 mint quote at the destination and the
     * melt quote at the source that pays it.
     *
     * CDK probes to find that amount, and every probe leaves a quote at both
     * mints that cannot be cancelled. Call this once for an explicit request,
     * never speculatively. CDK keeps only the returned pair locally.
     */
    suspend fun createMaxCrossMintQuotes(sourceMintUrl: String, destinationMintUrl: String): CrossMintQuotes

    /**
     * Melt every unspent sat proof at [mintUrl] directly, with no pre-melt swap.
     * A max cross-mint quote sizes its amount on the input fee of exactly this
     * proof set, so a swap would charge a second fee the balance no longer
     * covers. Throws [MeltInputFeeChangedException], having spent nothing, when
     * the proofs on hand no longer cost [expectedInputFee].
     */
    suspend fun meltAllUnspentSkippingSwap(quoteId: String, mintUrl: String, expectedInputFee: Long): MeltConfirmation

    /**
     * Forget a quote pair the user backed out of, so the destination quote
     * stops reading as an invoice awaiting payment. Returns false, and removes
     * nothing, once there is any local evidence the melt started: from then on
     * the destination quote is the only handle on money that may be in flight.
     * A failed or compensated melt still counts as started — the wallet can
     * believe a payment failed that the mint went on to make.
     */
    suspend fun removeUnusedQuotes(mintQuoteId: String, meltQuoteId: String? = null): Boolean

    /**
     * Whether local state shows a melt is over and did not pay: the quote is
     * unpaid, no operation holds it, and no attempt is still pending. False
     * whenever that cannot be established.
     */
    suspend fun meltEndedUnpaid(meltQuoteId: String?): Boolean
}

/** A destination mint quote and the source melt quote that pays it. */
data class CrossMintQuotes(
    val mintQuote: MintQuoteInfo,
    val meltQuote: MeltQuoteInfo,
    /** Input fee for spending every unspent source proof. Zero on most mints. */
    val inputFee: Long,
)

/**
 * The proofs on hand no longer cost what the quote was sized for, so melting
 * them would not leave the amount the quote promised. Nothing was sent.
 */
class MeltInputFeeChangedException(val expected: Long, val actual: Long) :
    IllegalStateException("Melt input fee changed from $expected to $actual.")

internal fun mintRemovalUrlsMatch(lhs: String, rhs: String): Boolean {
    fun identity(raw: String): List<Any?>? = runCatching {
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase() ?: return@runCatching null
        var authority = uri.rawAuthority?.lowercase() ?: return@runCatching null
        // Match iOS mint identity while preserving the original URL for DB reads.
        if ((scheme == "https" && uri.port == 443) || (scheme == "http" && uri.port == 80)) {
            authority = authority.substringBeforeLast(':')
        }
        var path = uri.rawPath.orEmpty()
        while (path.length > 1 && path.endsWith('/')) path = path.dropLast(1)
        if (path == "/") path = ""
        listOf(scheme, authority, path, uri.rawQuery, uri.rawFragment)
    }.getOrNull()

    val left = identity(lhs) ?: return lhs.trim() == rhs.trim()
    val right = identity(rhs) ?: return false
    return left == right
}

data class ForeignNfcSettlement(
    val amountReceived: Long,
    val transactionId: String,
    val feePaid: Long,
    val sourceMintUrl: String,
    val settlementMintUrl: String,
)

/**
 * Outcome of confirming a melt (iOS LightningService.MeltConfirmation parity).
 * Lightning melts settle synchronously in the common case — an async-accepted
 * (NUT-05) lightning melt is awaited in-lane via the handle's `wait()` for a
 * bounded window before the gateway gives up on a terminal answer. A
 * `PendingMelt` handle survives here only for on-chain melts (minutes-scale by
 * nature) and lightning waits that outlived the cap; the manager re-arms
 * `wait()` on it in the background. The handle dies with the process, after
 * which CDK's durable saga (surfaced as a Pending transaction) is the
 * reconciliation path.
 */
data class MeltConfirmation(
    val result: MeltPaymentResult,
    val pendingMelt: PendingMelt?,
    /**
     * Set when a confirmation that cannot answer asynchronously outlived the
     * cap: the still-running native call. It is never cancelled mid-payment;
     * the manager observes it to refresh the moment settlement lands.
     */
    val residualSettlement: Deferred<*>? = null,
)

/** Counts from CDK's `recoverIncompleteSagas()` for one mint. */
data class SagaRecoveryReport(
    val recovered: Long,
    val compensated: Long,
    val skipped: Long,
    val failed: Long,
) {
    val hasActivity: Boolean get() = recovered > 0 || compensated > 0 || skipped > 0 || failed > 0
}

data class NfcReceiveReceipt(
    val amountReceived: Long,
    val transactionId: String,
)

class CdkGatewayUnavailable(message: String) : IllegalStateException(message)

/** App-internal account identity. This does not alter any payment protocol. */
data class WalletAccountReference(val mintUrl: String, val unit: String)

/** Internal receive-recovery account reference; no bearer token is copied out of CDK. */
data class ReceiveRecoveryCandidate(val mintUrl: String, val unit: String)
