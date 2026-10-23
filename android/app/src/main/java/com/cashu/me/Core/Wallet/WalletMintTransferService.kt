package com.cashu.me.Core

import com.cashu.me.Core.CDK.CdkWalletGateway
import com.cashu.me.Core.CDK.CrossMintQuotes
import com.cashu.me.Core.CDK.MeltInputFeeChangedException
import com.cashu.me.Core.CDK.MeltPaymentRecoveryException
import com.cashu.me.Core.CDK.mintRemovalUrlsMatch
import com.cashu.me.Core.Wallet.isInsufficientBalance
import com.cashu.me.Models.MeltPaymentResult
import com.cashu.me.Models.MeltProofSelection
import com.cashu.me.Models.MeltQuoteInfo
import com.cashu.me.Models.MeltSettlement
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.MintTransferException
import com.cashu.me.Models.MintTransferOutcome
import com.cashu.me.Models.MintTransferPlan
import com.cashu.me.Models.MintTransferRecord
import com.cashu.me.Models.MintTransferStage
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.setting
import com.cashu.me.Models.upserting
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * Moves ecash between two held mints: a BOLT11 mint quote at the destination,
 * a melt at the source that pays it, then issuance at the destination.
 *
 * The wallet operations are injected, so the rules about which quotes may be
 * forgotten and when a transfer counts as finished are testable without a CDK
 * runtime (iOS `MintTransferEngine.swift` parity).
 */
internal class WalletMintTransferService(
    private val mints: () -> List<MintInfo>,
    private val createMintQuote: suspend (amount: Long, mintUrl: String) -> MintQuoteInfo,
    private val createMeltQuote: suspend (request: String, mintUrl: String) -> MeltQuoteInfo,
    private val createMaxQuotes: suspend (sourceMintUrl: String, destinationMintUrl: String) -> CrossMintQuotes,
    private val melt: suspend (quoteId: String, mintUrl: String, selection: MeltProofSelection) -> MeltPaymentResult,
    // Check the destination quote and issue what is outstanding. True once the
    // quote has been paid and fully issued.
    private val issue: suspend (mintQuoteId: String) -> Boolean,
    // Remove an unused quote pair. False, removing nothing, once the melt
    // shows any sign of having started.
    private val removeUnusedQuotes: suspend (mintQuoteId: String, meltQuoteId: String?) -> Boolean,
    private val meltEndedUnpaid: suspend (meltQuoteId: String?) -> Boolean,
    private val loadRecords: () -> List<MintTransferRecord>,
    private val saveRecords: (List<MintTransferRecord>) -> Unit,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
    private val makeId: () -> String = { UUID.randomUUID().toString() },
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    // Sweeps run on Dispatchers.IO while a transfer runs on the main-scoped
    // job, so every read-modify-write of the records goes through this lock.
    private val recordMonitor = Any()

    /** Production wiring; the primary constructor is the test seam for the rules. */
    constructor(
        gateway: CdkWalletGateway,
        walletStore: WalletStore,
        mints: () -> List<MintInfo>,
        melt: suspend (quoteId: String, mintUrl: String, selection: MeltProofSelection) -> MeltPaymentResult,
        issue: suspend (mintQuoteId: String) -> Boolean,
    ) : this(
        mints = mints,
        createMintQuote = { amount, mintUrl ->
            gateway.createMintQuote(amount, PaymentMethodKind.Bolt11, mintUrl, "sat")
        },
        createMeltQuote = { request, mintUrl -> gateway.createMeltQuote(request, null, mintUrl) },
        createMaxQuotes = gateway::createMaxCrossMintQuotes,
        melt = melt,
        issue = issue,
        removeUnusedQuotes = { mintQuoteId, meltQuoteId -> gateway.removeUnusedQuotes(mintQuoteId, meltQuoteId) },
        meltEndedUnpaid = gateway::meltEndedUnpaid,
        loadRecords = walletStore::loadMintTransfers,
        saveRecords = walletStore::saveMintTransfers,
    )

    // MARK: - Preparing

    /**
     * Quote moving [amount] from one held mint to another. [amount] is what
     * arrives; the source pays it plus the fee reserve.
     */
    suspend fun prepare(
        sourceMintUrl: String,
        destinationMintUrl: String,
        amount: Long,
    ): MintTransferPlan {
        val (source, destination) = heldMints(sourceMintUrl, destinationMintUrl)
        if (amount <= 0) throw MintTransferException.NothingToTransfer()

        val mintQuote = createMintQuote(amount, destination.url)
        // Recorded before the second quote so a kill in between still leaves
        // something that knows this invoice is not awaiting payment.
        var record = MintTransferRecord(
            id = makeId(),
            sourceMintUrl = source.url,
            destinationMintUrl = destination.url,
            mintQuoteId = mintQuote.id,
            amount = amount,
            createdAtEpochMillis = nowEpochMillis(),
        )
        save(record)

        val meltQuote = try {
            createMeltQuote(mintQuote.request, source.url)
        } catch (error: Throwable) {
            abandon(record)
            throw error
        }
        record = record.copy(meltQuoteId = meltQuote.id)
        save(record)

        // The reserve is an upper bound the source must be able to cover in
        // full (iOS checks this where the melt quote is made).
        if (meltQuote.totalAmount > source.balance) {
            abandon(record)
            throw MintTransferException.InsufficientBalance(
                required = meltQuote.totalAmount,
                available = source.balance,
            )
        }

        val plan = MintTransferPlan(
            id = record.id,
            mode = MintTransferPlan.Mode.Exact,
            sourceMintUrl = source.url,
            destinationMintUrl = destination.url,
            mintQuoteId = mintQuote.id,
            meltQuoteId = meltQuote.id,
            amount = amount,
            feeReserve = meltQuote.feeReserve,
            inputFee = 0,
            selection = MeltProofSelection.Automatic,
            expiresAtEpochSeconds = expiry(mintQuote, meltQuote),
        )
        validate(plan, record, mintQuote, meltQuote)
        return plan
    }

    /**
     * Quote the largest amount the source can move to the destination in one
     * payment. Makes several quotes at both mints to find it, so call it for
     * an explicit request only.
     */
    suspend fun prepareMax(
        sourceMintUrl: String,
        destinationMintUrl: String,
    ): MintTransferPlan {
        val (source, destination) = heldMints(sourceMintUrl, destinationMintUrl)
        if (source.balance <= 0) throw MintTransferException.NothingToTransfer()

        val quotes = try {
            createMaxQuotes(source.url, destination.url)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            if (error.isInsufficientBalance) throw MintTransferException.NothingToTransfer()
            throw error
        }
        val amount = quotes.mintQuote.amount?.takeIf { it > 0 }
        if (amount == null) {
            withContext(NonCancellable) { removeUnusedQuotes(quotes.mintQuote.id, quotes.meltQuote.id) }
            throw MintTransferException.NothingToTransfer()
        }
        val record = MintTransferRecord(
            id = makeId(),
            sourceMintUrl = source.url,
            destinationMintUrl = destination.url,
            mintQuoteId = quotes.mintQuote.id,
            meltQuoteId = quotes.meltQuote.id,
            amount = amount,
            createdAtEpochMillis = nowEpochMillis(),
        )
        save(record)

        val selection = MintTransferPlan.maxMeltSelection(
            balance = source.balance,
            amount = amount,
            feeReserve = quotes.meltQuote.feeReserve,
            inputFee = quotes.inputFee,
        )
        val plan = MintTransferPlan(
            id = record.id,
            mode = MintTransferPlan.Mode.Max,
            sourceMintUrl = source.url,
            destinationMintUrl = destination.url,
            mintQuoteId = quotes.mintQuote.id,
            meltQuoteId = quotes.meltQuote.id,
            amount = amount,
            feeReserve = quotes.meltQuote.feeReserve,
            // An ordinary melt picks its own proofs, so its input fee is not
            // known until it runs — the same as any Lightning payment.
            inputFee = if (selection == MeltProofSelection.Automatic) 0 else quotes.inputFee,
            selection = selection,
            expiresAtEpochSeconds = expiry(quotes.mintQuote, quotes.meltQuote),
        )
        validate(plan, record, quotes.mintQuote, quotes.meltQuote)
        return plan
    }

    // MARK: - Executing

    /**
     * Pay the destination's invoice from the source, then issue the ecash at
     * the destination. Returns [MintTransferOutcome.Settling] when either leg
     * is slow; the pending-quote sweeps then finish it, across relaunches.
     */
    suspend fun execute(
        plan: MintTransferPlan,
        onStage: (MintTransferStage) -> Unit = {},
    ): MintTransferOutcome {
        if (plan.isExpired(nowEpochMillis())) {
            loadRecords()
                .firstOrNull { it.id == plan.id && it.state == MintTransferRecord.State.Draft }
                ?.let { discard(it) }
            throw MintTransferException.PlanExpired()
        }
        // Commit before any money moves. From here the destination quote is
        // kept until it issues, whatever happens to this process.
        if (!setState(MintTransferRecord.State.Committed, plan.id, from = MintTransferRecord.State.Draft)) {
            throw MintTransferException.PlanStale()
        }

        onStage(MintTransferStage.Paying)
        val payment = try {
            melt(plan.meltQuoteId, plan.sourceMintUrl, plan.selection)
        } catch (cancellation: CancellationException) {
            // Cancelled mid-payment is an unknown outcome too: stay committed.
            throw cancellation
        } catch (_: MeltInputFeeChangedException) {
            settleUnpaid(plan)
            throw MintTransferException.PlanStale()
        } catch (recovery: MeltPaymentRecoveryException) {
            if (!recovery.unresolved) {
                settleUnpaid(plan)
                throw MintTransferException.PaymentReturned()
            }
            // Unknown outcome: stay committed so the sweeps keep checking.
            throw recovery
        } catch (error: Throwable) {
            settleUnpaid(plan)
            throw error
        }

        if (payment.settlement != MeltSettlement.Settled) {
            return MintTransferOutcome.Settling(MintTransferOutcome.Leg.Payment)
        }

        onStage(MintTransferStage.Issuing)
        for (attempt in 0 until ISSUANCE_ATTEMPTS) {
            if (issue(plan.mintQuoteId)) {
                noteIssued(plan.mintQuoteId)
                return MintTransferOutcome.Completed(amount = plan.amount, feePaid = payment.feePaid)
            }
            if (attempt < ISSUANCE_ATTEMPTS - 1) pause(ISSUANCE_INTERVAL_MS)
        }
        return MintTransferOutcome.Settling(MintTransferOutcome.Leg.Issuance)
    }

    // MARK: - Discarding

    /**
     * Forget a plan the user backed out of. Safe to call at any point: once
     * the melt has started it removes nothing.
     */
    suspend fun discard(plan: MintTransferPlan) {
        val record = loadRecords()
            .firstOrNull { it.id == plan.id && it.state == MintTransferRecord.State.Draft }
            ?: return
        discard(record)
    }

    /**
     * Drafts made before [beforeEpochMillis] belong to a review screen that no
     * longer exists. A later draft is a plan on screen and is left alone.
     */
    suspend fun discardAbandonedDrafts(beforeEpochMillis: Long) {
        for (record in loadRecords()) {
            if (record.state == MintTransferRecord.State.Draft && record.createdAtEpochMillis < beforeEpochMillis) {
                discard(record)
            }
        }
    }

    // MARK: - Maintenance

    /**
     * A committed transfer stops being chased once its melt is seen to have
     * ended unpaid. Its destination quote then follows the ordinary schedule,
     * which still checks it until the invoice can no longer be paid.
     */
    suspend fun failEndedTransfers(excluding: Set<String>) {
        for (record in loadRecords()) {
            if (record.state != MintTransferRecord.State.Committed || record.id in excluding) continue
            if (meltEndedUnpaid(record.meltQuoteId)) {
                // Only from committed: the quote may have issued meanwhile.
                setState(MintTransferRecord.State.Failed, record.id, from = MintTransferRecord.State.Committed)
            }
        }
    }

    /**
     * Note that a transfer's destination quote issued, however that happened —
     * the transfer itself, or a sweep long after it.
     */
    fun noteIssued(quoteId: String) {
        synchronized(recordMonitor) {
            val records = loadRecords()
            val record = records.firstOrNull { it.mintQuoteId == quoteId } ?: return
            if (record.state == MintTransferRecord.State.Completed) return
            saveRecords(records.setting(MintTransferRecord.State.Completed, forId = record.id))
        }
    }

    // MARK: - Internals

    private fun save(record: MintTransferRecord) {
        synchronized(recordMonitor) { saveRecords(loadRecords().upserting(record)) }
    }

    private fun remove(id: String) {
        synchronized(recordMonitor) { saveRecords(loadRecords().filterNot { it.id == id }) }
    }

    /**
     * Returns false when the record is missing or, with [from], not in that
     * state — so a plan can only be committed once.
     */
    private fun setState(
        state: MintTransferRecord.State,
        id: String,
        from: MintTransferRecord.State? = null,
    ): Boolean = synchronized(recordMonitor) {
        val records = loadRecords()
        val record = records.firstOrNull { it.id == id } ?: return@synchronized false
        if (from != null && record.state != from) return@synchronized false
        saveRecords(records.setting(state, forId = id))
        true
    }

    /**
     * Remove a draft's quotes and its record. If the quotes cannot be removed
     * because the melt shows signs of having started, the record is promoted
     * instead, so the destination quote stays tracked.
     */
    private suspend fun discard(record: MintTransferRecord) {
        if (removeUnusedQuotes(record.mintQuoteId, record.meltQuoteId)) {
            remove(record.id)
        } else {
            setState(MintTransferRecord.State.Committed, record.id, from = MintTransferRecord.State.Draft)
        }
    }

    /**
     * Discard a draft whose quoting failed. Shielded from cancellation: a
     * caller that left during the quote calls must not strand the draft.
     */
    private suspend fun abandon(record: MintTransferRecord) {
        withContext(NonCancellable) { discard(record) }
    }

    /**
     * The melt ended without paying. If nothing at all was started the plan is
     * simply forgotten; otherwise it is kept as failed, with its destination
     * quote still checked until that invoice can no longer be paid.
     */
    private suspend fun settleUnpaid(plan: MintTransferPlan) {
        if (removeUnusedQuotes(plan.mintQuoteId, plan.meltQuoteId)) {
            remove(plan.id)
        } else {
            setState(MintTransferRecord.State.Failed, plan.id, from = MintTransferRecord.State.Committed)
        }
    }

    private fun heldMints(sourceMintUrl: String, destinationMintUrl: String): Pair<MintInfo, MintInfo> {
        val held = mints()
        fun find(url: String) = held.firstOrNull { mintRemovalUrlsMatch(it.url, url) }
        val source = find(sourceMintUrl)
        val destination = find(destinationMintUrl)
        if (source == null || destination == null) throw IllegalArgumentException("Mint is no longer tracked.")
        MintTransferEligibility.blocker(source, destination)?.let {
            throw MintTransferException.NotEligible(it)
        }
        return source to destination
    }

    /**
     * The quotes must describe the transfer that was asked for. A mint that
     * answers with anything else is not paid.
     */
    private suspend fun validate(
        plan: MintTransferPlan,
        record: MintTransferRecord,
        mintQuote: MintQuoteInfo,
        meltQuote: MeltQuoteInfo,
    ) {
        val mintQuoteMint = mintQuote.mintUrl
        val valid = mintQuote.amount == plan.amount &&
            meltQuote.amount == plan.amount &&
            mintQuote.unit == "sat" &&
            mintQuote.paymentMethod == PaymentMethodKind.Bolt11 &&
            (mintQuoteMint == null || mintRemovalUrlsMatch(mintQuoteMint, plan.destinationMintUrl)) &&
            // Blank when CDK did not record the mint; the quote was requested
            // from the source's own wallet either way.
            (meltQuote.mintUrl.isBlank() || mintRemovalUrlsMatch(meltQuote.mintUrl, plan.sourceMintUrl))
        if (!valid) {
            abandon(record)
            throw MintTransferException.QuoteMismatch()
        }
        // A caller that left during the quote calls is honoured here.
        if (!currentCoroutineContext().isActive) {
            abandon(record)
            currentCoroutineContext().ensureActive()
        }
    }

    private fun expiry(mintQuote: MintQuoteInfo, meltQuote: MeltQuoteInfo): Long? =
        listOfNotNull(mintQuote.expiryEpochSeconds, meltQuote.expiryEpochSeconds)
            .filter { it > 0 }
            .minOrNull()

    companion object {
        // How long a transfer waits for the destination to issue before
        // handing over to the pending-quote sweeps.
        const val ISSUANCE_ATTEMPTS = 10
        const val ISSUANCE_INTERVAL_MS = 2_000L
    }
}
