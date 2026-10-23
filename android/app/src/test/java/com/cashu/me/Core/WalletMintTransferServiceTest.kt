package com.cashu.me.Core

import com.cashu.me.Core.CDK.CrossMintQuotes
import com.cashu.me.Core.CDK.MeltInputFeeChangedException
import com.cashu.me.Core.CDK.MeltPaymentRecoveryException
import com.cashu.me.Core.MintTransferEligibility.Blocker
import com.cashu.me.Models.MeltPaymentResult
import com.cashu.me.Models.MeltProofSelection
import com.cashu.me.Models.MeltQuoteInfo
import com.cashu.me.Models.MeltQuoteState
import com.cashu.me.Models.MeltSettlement
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.MintQuoteState
import com.cashu.me.Models.MintTransferException
import com.cashu.me.Models.MintTransferOutcome
import com.cashu.me.Models.MintTransferPlan
import com.cashu.me.Models.MintTransferRecord
import com.cashu.me.Models.MintTransferRecord.State
import com.cashu.me.Models.MintTransferStage
import com.cashu.me.Models.PaymentMethodKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS parity: `MintTransferEngineTests.swift`. */
class WalletMintTransferServiceTest {

    // MARK: - Preparing an exact amount

    @Test
    fun `prepare quotes both mints and records a draft`() = runBlocking {
        val world = World()
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)

        assertEquals(MintTransferPlan.Mode.Exact, plan.mode)
        assertEquals(40L, plan.amount)
        assertEquals(2L, plan.feeReserve)
        assertEquals(0L, plan.inputFee)
        assertEquals(42L, plan.total)
        assertEquals(MeltProofSelection.Automatic, plan.selection)
        assertEquals(listOf(Quoted.Mint(DESTINATION_URL), Quoted.Melt(SOURCE_URL)), world.quotedAt)
        assertEquals(listOf(State.Draft), world.records.map { it.state })
        assertEquals(plan.mintQuoteId, world.records.first().mintQuoteId)
        assertEquals(plan.meltQuoteId, world.records.first().meltQuoteId)
    }

    /** The reserve is an upper bound, so the source must hold amount plus reserve. */
    @Test
    fun `melt quote the source cannot cover leaves no quote and no record`() = runBlocking {
        val world = World()

        val error = assertFails<MintTransferException.InsufficientBalance> {
            world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 100)
        }

        assertEquals(102L, error.required)
        assertEquals(100L, error.available)
        assertTrue(world.records.isEmpty())
        assertTrue(world.mintQuotes.isEmpty())
        assertTrue(world.meltQuotes.isEmpty())
    }

    @Test
    fun `failed melt quote leaves no quote and no record`() = runBlocking {
        val world = World()
        val failure = IllegalStateException("offline")
        world.meltQuoteError = failure

        val error = assertFails<IllegalStateException> {
            world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)
        }

        assertSame(failure, error)
        assertTrue(world.records.isEmpty())
        assertTrue(world.mintQuotes.isEmpty())
    }

    /** A mint that answers with a different amount is not paid. */
    @Test
    fun `quote for a different amount is discarded`() = runBlocking {
        val world = World()
        world.meltQuoteAmount = 41

        assertFails<MintTransferException.QuoteMismatch> {
            world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)
        }

        assertTrue(world.records.isEmpty())
        assertTrue(world.mintQuotes.isEmpty())
        assertTrue(world.meltQuotes.isEmpty())
    }

    /**
     * Kotlin cancels at suspension points, so a caller that leaves while the
     * second quote is being made must not strand the first.
     */
    @Test
    fun `caller leaving during the melt quote leaves no quote and no record`() = runBlocking {
        val world = World()
        val quoting = CompletableDeferred<Unit>()
        world.duringMeltQuote = {
            quoting.complete(Unit)
            awaitCancellation()
        }
        val caller = launch { world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40) }
        quoting.await()

        caller.cancelAndJoin()

        assertTrue(world.records.isEmpty())
        assertTrue(world.mintQuotes.isEmpty())
    }

    /** A cancellation the quote calls did not observe is honoured before the plan is handed out. */
    @Test
    fun `caller that left by the time both quotes exist gets no plan`() = runBlocking {
        val world = World()
        world.duringMeltQuote = { currentCoroutineContext().cancel() }

        val caller = async { world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40) }
        caller.join()

        assertTrue(caller.isCancelled)
        assertTrue(world.records.isEmpty())
        assertTrue(world.mintQuotes.isEmpty())
        assertTrue(world.meltQuotes.isEmpty())
    }

    @Test
    fun `ineligible pair is rejected before any quote`() = runBlocking {
        val world = World()

        val error = assertFails<MintTransferException.NotEligible> {
            world.service.prepare(SOURCE_URL, "$SOURCE_URL/", amount = 40)
        }

        assertEquals(Blocker.SameMint, error.blocker)
        assertTrue(world.quotedAt.isEmpty())
    }

    // MARK: - Preparing the maximum

    @Test
    fun `max on a fee charging source spends every proof and shows the input fee`() = runBlocking {
        val world = World()
        world.maxQuote = MaxQuote(amount = 80, feeReserve = 2, inputFee = 18)

        val plan = world.service.prepareMax(SOURCE_URL, DESTINATION_URL)

        assertEquals(MintTransferPlan.Mode.Max, plan.mode)
        assertEquals(80L, plan.amount)
        assertEquals(MeltProofSelection.AllUnspentSkippingSwap(expectedInputFee = 18), plan.selection)
        assertEquals(18L, plan.inputFee)
        assertEquals(100L, plan.total)
        assertEquals(listOf(State.Draft), world.records.map { it.state })
    }

    @Test
    fun `max on a zero fee source uses an ordinary melt`() = runBlocking {
        val world = World()
        world.maxQuote = MaxQuote(amount = 98, feeReserve = 2, inputFee = 0)

        val plan = world.service.prepareMax(SOURCE_URL, DESTINATION_URL)

        assertEquals(MeltProofSelection.Automatic, plan.selection)
        assertEquals(0L, plan.inputFee)
        assertEquals(100L, plan.total)
    }

    @Test
    fun `max from an empty mint quotes nothing`() = runBlocking {
        val world = World(sourceBalance = 0)

        assertFails<MintTransferException.NothingToTransfer> {
            world.service.prepareMax(SOURCE_URL, DESTINATION_URL)
        }

        assertTrue(world.quotedAt.isEmpty())
    }

    // MARK: - Executing

    @Test
    fun `execute pays then issues and completes the record`() = runBlocking {
        val world = World()
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)
        val stages = mutableListOf<MintTransferStage>()

        val outcome = world.service.execute(plan) { stages += it }

        assertEquals(MintTransferOutcome.Completed(amount = 40, feePaid = 1), outcome)
        assertEquals(listOf(MintTransferStage.Paying, MintTransferStage.Issuing), stages)
        assertEquals(listOf(plan.meltQuoteId), world.melts.map { it.quoteId })
        assertEquals(listOf(SOURCE_URL), world.melts.map { it.mintUrl })
        assertEquals(listOf(State.Completed), world.records.map { it.state })
    }

    @Test
    fun `max plan melts with its planned selection`() = runBlocking {
        val world = World()
        world.maxQuote = MaxQuote(amount = 80, feeReserve = 2, inputFee = 18)
        val plan = world.service.prepareMax(SOURCE_URL, DESTINATION_URL)

        world.service.execute(plan)

        assertEquals(
            listOf<MeltProofSelection>(MeltProofSelection.AllUnspentSkippingSwap(expectedInputFee = 18)),
            world.melts.map { it.selection },
        )
    }

    /**
     * The payment went out but the destination is slow. The record stays
     * committed so the sweeps keep checking the quote.
     */
    @Test
    fun `slow issuance hands over to the sweeps still committed`() = runBlocking {
        val world = World()
        world.issues = false
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)

        val outcome = world.service.execute(plan)

        assertEquals(MintTransferOutcome.Settling(MintTransferOutcome.Leg.Issuance), outcome)
        assertEquals(WalletMintTransferService.ISSUANCE_ATTEMPTS, world.issueAttempts)
        assertEquals(listOf(State.Committed), world.records.map { it.state })
        assertEquals(1, world.mintQuotes.size)
    }

    @Test
    fun `pending payment does not attempt issuance`() = runBlocking {
        val world = World()
        world.meltSettlement = MeltSettlement.Pending
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)

        val outcome = world.service.execute(plan)

        assertEquals(MintTransferOutcome.Settling(MintTransferOutcome.Leg.Payment), outcome)
        assertEquals(0, world.issueAttempts)
        assertEquals(listOf(State.Committed), world.records.map { it.state })
    }

    /**
     * The wallet can believe a payment failed that the mint went on to make,
     * so a melt that ran keeps its destination quote even when it reports the
     * funds as returned.
     */
    @Test
    fun `returned payment keeps the destination quote`() = runBlocking {
        val world = World()
        world.meltError = MeltPaymentRecoveryException("q", "op", unresolved = false)
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)

        assertFails<MintTransferException.PaymentReturned> { world.service.execute(plan) }

        assertEquals(listOf(State.Failed), world.records.map { it.state })
        assertEquals(listOf(plan.mintQuoteId), world.mintQuotes.keys.toList())
    }

    @Test
    fun `unknown payment outcome stays committed`() = runBlocking {
        val world = World()
        world.meltError = MeltPaymentRecoveryException("q", "op", unresolved = true)
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)

        val error = assertFails<MeltPaymentRecoveryException> { world.service.execute(plan) }

        assertTrue(error.unresolved)
        assertEquals(listOf(State.Committed), world.records.map { it.state })
        assertEquals(1, world.mintQuotes.size)
    }

    /** A melt that never reached the mint leaves nothing behind at all. */
    @Test
    fun `melt that never started is forgotten entirely`() = runBlocking {
        val world = World()
        val failure = IllegalStateException("offline")
        world.meltError = failure
        world.meltLeavesEvidence = false
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)

        val error = assertFails<IllegalStateException> { world.service.execute(plan) }

        assertSame(failure, error)
        assertTrue(world.records.isEmpty())
        assertTrue(world.mintQuotes.isEmpty())
    }

    @Test
    fun `changed input fee reports a stale plan`() = runBlocking {
        val world = World()
        world.maxQuote = MaxQuote(amount = 80, feeReserve = 2, inputFee = 18)
        world.meltError = MeltInputFeeChangedException(expected = 18, actual = 19)
        world.meltLeavesEvidence = false
        val plan = world.service.prepareMax(SOURCE_URL, DESTINATION_URL)

        assertFails<MintTransferException.PlanStale> { world.service.execute(plan) }

        assertTrue(world.records.isEmpty())
    }

    @Test
    fun `a plan cannot be executed twice`() = runBlocking {
        val world = World()
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)
        world.service.execute(plan)

        assertFails<MintTransferException.PlanStale> { world.service.execute(plan) }

        assertEquals(1, world.melts.size)
    }

    @Test
    fun `expired plan is discarded unpaid`() = runBlocking {
        val world = World()
        world.quoteExpiry = 2_000
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)
        world.now = 2_000_000

        assertFails<MintTransferException.PlanExpired> { world.service.execute(plan) }

        assertTrue(world.melts.isEmpty())
        assertTrue(world.records.isEmpty())
        assertTrue(world.mintQuotes.isEmpty())
    }

    // MARK: - Discarding

    @Test
    fun `discarding a draft removes its quotes`() = runBlocking {
        val world = World()
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)

        world.service.discard(plan)

        assertTrue(world.records.isEmpty())
        assertTrue(world.mintQuotes.isEmpty())
        assertTrue(world.meltQuotes.isEmpty())
    }

    @Test
    fun `discarding after commit removes nothing`() = runBlocking {
        val world = World()
        world.issues = false
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)
        world.service.execute(plan)

        world.service.discard(plan)

        assertEquals(listOf(State.Committed), world.records.map { it.state })
        assertEquals(1, world.mintQuotes.size)
    }

    /**
     * If the quotes cannot be removed because the melt shows signs of having
     * started, the draft is promoted so its destination quote stays tracked.
     */
    @Test
    fun `draft with a started melt is promoted instead of discarded`() = runBlocking {
        val world = World()
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)
        world.startedMelts += plan.meltQuoteId

        world.service.discard(plan)

        assertEquals(listOf(State.Committed), world.records.map { it.state })
        assertEquals(1, world.mintQuotes.size)
    }

    @Test
    fun `only drafts from before this launch are abandoned`() = runBlocking {
        val world = World()
        val old = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)
        world.now = 1_500_000
        val live = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 21)

        world.service.discardAbandonedDrafts(beforeEpochMillis = 1_200_000)

        assertEquals(listOf(live.id), world.records.map { it.id })
        assertEquals(listOf(live.mintQuoteId), world.mintQuotes.keys.toList())
        assertNotEquals(old.id, live.id)
    }

    // MARK: - Maintenance

    @Test
    fun `committed transfer whose melt ended unpaid is failed`() = runBlocking {
        val world = World()
        world.meltSettlement = MeltSettlement.Pending
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)
        world.service.execute(plan)
        world.endedUnpaid += plan.meltQuoteId

        world.service.failEndedTransfers(excluding = emptySet())

        assertEquals(listOf(State.Failed), world.records.map { it.state })
    }

    /**
     * A transfer that is executing has committed but may not have reached its
     * melt yet, which reads the same as one that ended unpaid.
     */
    @Test
    fun `transfer in flight is not failed`() = runBlocking {
        val world = World()
        world.meltSettlement = MeltSettlement.Pending
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)
        world.service.execute(plan)
        world.endedUnpaid += plan.meltQuoteId

        world.service.failEndedTransfers(excluding = setOf(plan.id))

        assertEquals(listOf(State.Committed), world.records.map { it.state })
    }

    @Test
    fun `late issuance completes even a failed transfer`() = runBlocking {
        val world = World()
        world.meltError = MeltPaymentRecoveryException("q", "op", unresolved = false)
        val plan = world.service.prepare(SOURCE_URL, DESTINATION_URL, amount = 40)
        assertFails<MintTransferException.PaymentReturned> { world.service.execute(plan) }
        assertEquals(listOf(State.Failed), world.records.map { it.state })

        world.service.noteIssued(plan.mintQuoteId)

        assertEquals(listOf(State.Completed), world.records.map { it.state })
    }

    // MARK: - Harness

    private inline fun <reified T : Throwable> assertFails(operation: () -> Unit): T {
        val error = try {
            operation()
            null
        } catch (error: Throwable) {
            error
        }
        if (error is T) return error
        throw AssertionError("Expected ${T::class.simpleName}, got $error", error)
    }

    private sealed interface Quoted {
        data class Mint(val mintUrl: String) : Quoted
        data class Melt(val mintUrl: String) : Quoted
        data class Max(val sourceMintUrl: String, val destinationMintUrl: String) : Quoted
    }

    private data class Melt(val quoteId: String, val mintUrl: String, val selection: MeltProofSelection)

    private data class MaxQuote(val amount: Long, val feeReserve: Long, val inputFee: Long)

    /** Two held mints and the quotes, melts and records the service makes. */
    private class World(sourceBalance: Long = 100) {
        val mints = listOf(
            MintInfo(url = SOURCE_URL, name = "Source", isActive = true, balance = sourceBalance),
            MintInfo(url = DESTINATION_URL, name = "Destination", isActive = false, balance = 0),
        )
        var records: List<MintTransferRecord> = emptyList()
        val mintQuotes = linkedMapOf<String, MintQuoteInfo>()
        val meltQuotes = linkedMapOf<String, MeltQuoteInfo>()
        val quotedAt = mutableListOf<Quoted>()
        val melts = mutableListOf<Melt>()
        val startedMelts = mutableSetOf<String>()
        val endedUnpaid = mutableSetOf<String>()
        var issueAttempts = 0
        var now = 1_000_000L

        var meltQuoteError: Throwable? = null
        var duringMeltQuote: suspend () -> Unit = {}
        var meltQuoteAmount: Long? = null
        var quoteExpiry: Long? = null
        var maxQuote = MaxQuote(amount = 98, feeReserve = 2, inputFee = 0)
        var meltError: Throwable? = null

        /** Whether a failing melt got far enough to leave a trace locally. */
        var meltLeavesEvidence = true
        var meltSettlement = MeltSettlement.Settled
        var issues = true

        private var sequence = 0

        private fun nextId(prefix: String): String {
            sequence += 1
            return "$prefix-$sequence"
        }

        private fun mintQuote(amount: Long, mintUrl: String): MintQuoteInfo {
            val quote = MintQuoteInfo(
                id = nextId("mint"), request = "lnbc$amount", amount = amount,
                paymentMethod = PaymentMethodKind.Bolt11, state = MintQuoteState.Unpaid,
                expiryEpochSeconds = quoteExpiry, mintUrl = mintUrl,
            )
            mintQuotes[quote.id] = quote
            return quote
        }

        private fun meltQuote(amount: Long, feeReserve: Long, mintUrl: String): MeltQuoteInfo {
            val quote = MeltQuoteInfo(
                id = nextId("melt"), mintUrl = mintUrl, amount = amount, feeReserve = feeReserve,
                paymentMethod = PaymentMethodKind.Bolt11, state = MeltQuoteState.Unpaid,
                expiryEpochSeconds = quoteExpiry,
            )
            meltQuotes[quote.id] = quote
            return quote
        }

        val service: WalletMintTransferService
            get() = WalletMintTransferService(
                mints = { mints },
                createMintQuote = { amount, mintUrl ->
                    quotedAt += Quoted.Mint(mintUrl)
                    mintQuote(amount, mintUrl)
                },
                createMeltQuote = { request, mintUrl ->
                    quotedAt += Quoted.Melt(mintUrl)
                    duringMeltQuote()
                    meltQuoteError?.let { throw it }
                    val invoiced = mintQuotes.values.firstOrNull { it.request == request }?.amount ?: 0
                    meltQuote(meltQuoteAmount ?: invoiced, feeReserve = 2, mintUrl = mintUrl)
                },
                createMaxQuotes = { sourceMintUrl, destinationMintUrl ->
                    quotedAt += Quoted.Max(sourceMintUrl, destinationMintUrl)
                    CrossMintQuotes(
                        mintQuote = mintQuote(maxQuote.amount, destinationMintUrl),
                        meltQuote = meltQuote(maxQuote.amount, maxQuote.feeReserve, sourceMintUrl),
                        inputFee = maxQuote.inputFee,
                    )
                },
                melt = { quoteId, mintUrl, selection ->
                    melts += Melt(quoteId, mintUrl, selection)
                    meltError?.let {
                        if (meltLeavesEvidence) startedMelts += quoteId
                        throw it
                    }
                    startedMelts += quoteId
                    MeltPaymentResult(
                        preimage = null, amount = meltQuotes[quoteId]?.amount ?: 0, feePaid = 1,
                        mintUrl = mintUrl, settlement = meltSettlement,
                    )
                },
                issue = {
                    issueAttempts += 1
                    issues
                },
                removeUnusedQuotes = { mintQuoteId, meltQuoteId ->
                    if (meltQuoteId != null && meltQuoteId in startedMelts) {
                        false
                    } else {
                        mintQuotes -= mintQuoteId
                        if (meltQuoteId != null) meltQuotes -= meltQuoteId
                        true
                    }
                },
                meltEndedUnpaid = { meltQuoteId -> meltQuoteId != null && meltQuoteId in endedUnpaid },
                loadRecords = { records },
                saveRecords = { records = it },
                nowEpochMillis = { now },
                makeId = { nextId("transfer") },
                pause = {},
            )
    }

    private companion object {
        const val SOURCE_URL = "https://source.example"
        const val DESTINATION_URL = "https://destination.example"
    }
}
