package com.cashu.me.Models

import com.cashu.me.Core.MintTransferEligibility.Blocker
import com.cashu.me.Core.MintTransferInProgressException
import com.cashu.me.Core.Protocols.StorageKeys
import com.cashu.me.Core.Wallet.isInsufficientBalance
import com.cashu.me.Core.Wallet.userFacingWalletMessage
import com.cashu.me.Models.MintTransferRecord.State
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS parity: `MintTransferModelTests` in `MintTransferEngineTests.swift`. */
class MintTransferModelsTest {

    // MARK: - How a max plan melts

    @Test
    fun `zero fee source never needs the skip swap melt`() {
        assertEquals(
            MeltProofSelection.Automatic,
            MintTransferPlan.maxMeltSelection(balance = 100, amount = 98, feeReserve = 2, inputFee = 0),
        )
    }

    /**
     * The quote used the whole balance, so only melting exactly the proofs it
     * counted fits.
     */
    @Test
    fun `balance bound max on a fee charging source skips the swap`() {
        assertEquals(
            MeltProofSelection.AllUnspentSkippingSwap(expectedInputFee = 18),
            MintTransferPlan.maxMeltSelection(balance = 100, amount = 80, feeReserve = 2, inputFee = 18),
        )
    }

    /**
     * A mint limit capped the amount well below the balance. There is room for
     * an ordinary melt's own fees, and no reason to route the whole balance
     * through one payment.
     */
    @Test
    fun `limit bound max leaves room for an ordinary melt`() {
        assertEquals(
            MeltProofSelection.Automatic,
            MintTransferPlan.maxMeltSelection(
                balance = 1_000_000, amount = 500_000, feeReserve = 5_000, inputFee = 20,
            ),
        )
    }

    @Test
    fun `slack smaller than a swap and a melt still skips the swap`() {
        assertEquals(
            MeltProofSelection.AllUnspentSkippingSwap(expectedInputFee = 18),
            MintTransferPlan.maxMeltSelection(balance = 139, amount = 100, feeReserve = 2, inputFee = 18),
        )
        assertEquals(
            MeltProofSelection.Automatic,
            MintTransferPlan.maxMeltSelection(balance = 156, amount = 100, feeReserve = 2, inputFee = 18),
        )
    }

    @Test
    fun `plan totals add the fee upper bound`() {
        val plan = plan(amount = 80, feeReserve = 2, inputFee = 18, expiresAtEpochSeconds = 100)

        assertEquals(20L, plan.feeUpperBound)
        assertEquals(100L, plan.total)
        assertFalse(plan.isExpired(nowEpochMillis = 99_000))
        assertTrue(plan.isExpired(nowEpochMillis = 100_000))
    }

    // MARK: - Records

    @Test
    fun `quote ownership by state`() {
        val records = listOf(
            record("draft", State.Draft), record("committed", State.Committed),
            record("completed", State.Completed), record("failed", State.Failed),
        )

        assertEquals(
            setOf("mint-draft", "mint-committed", "mint-completed", "mint-failed"),
            records.ownedMintQuoteIds,
        )
        assertEquals(setOf("mint-draft"), records.draftMintQuoteIds)
        assertEquals(setOf("mint-committed"), records.awaitingIssuanceMintQuoteIds)
    }

    /** Only a transfer that was paid for and has not finished ties a mint down. */
    @Test
    fun `only committed transfers block mint removal`() {
        val committed = listOf(record("a", State.Committed))

        assertTrue(committed.hasUnfinishedTransfer(referencing = "https://Source.example:443/"))
        assertTrue(committed.hasUnfinishedTransfer(referencing = "https://destination.example"))
        assertFalse(committed.hasUnfinishedTransfer(referencing = "https://other.example"))
        for (state in listOf(State.Draft, State.Completed, State.Failed)) {
            assertFalse(
                listOf(record("a", state)).hasUnfinishedTransfer(referencing = "https://source.example"),
            )
        }
    }

    @Test
    fun `upserting replaces by id and keeps newest first`() {
        val older = record("a", State.Draft, createdAtEpochMillis = 10)
        val newer = record("b", State.Draft, createdAtEpochMillis = 20)
        val updated = older.copy(state = State.Committed)

        val records = listOf(older).upserting(newer).upserting(updated)

        assertEquals(listOf("b", "a"), records.map { it.id })
        assertEquals(State.Committed, records.last().state)
    }

    /** Dropping an unfinished record would orphan a quote that may be paid. */
    @Test
    fun `limit drops only finished records`() {
        val limit = MintTransferRecord.LIMIT
        var records = (0 until limit).map {
            record("done-$it", State.Completed, createdAtEpochMillis = 1_000L + it)
        }
        records = records.upserting(record("waiting", State.Committed, createdAtEpochMillis = 1))
        records = records.upserting(record("newest", State.Completed, createdAtEpochMillis = 5_000))

        assertEquals(limit, records.count { it.state == State.Completed })
        assertTrue(records.any { it.id == "waiting" })
        assertTrue(records.any { it.id == "newest" })
        assertFalse(records.any { it.id == "done-0" })
    }

    /**
     * `WalletStore` needs a device, so this covers what it relies on: the
     * stored form round-trips, older records still decode, and the key is
     * wiped at the wallet boundary.
     */
    @Test
    fun `records survive persistence and are wiped with the wallet`() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val serializer = ListSerializer(MintTransferRecord.serializer())
        val halfQuoted = record("a", State.Draft).copy(meltQuoteId = null)
        val records = listOf(halfQuoted, record("b", State.Committed))

        assertEquals(records, json.decodeFromString(serializer, json.encodeToString(serializer, records)))

        val stored = """[{"id":"a","sourceMintUrl":"https://source.example",""" +
            """"destinationMintUrl":"https://destination.example","mintQuoteId":"mint-a",""" +
            """"amount":40,"createdAtEpochMillis":0,"addedLater":true}]"""
        val decoded = json.decodeFromString(serializer, stored).single()
        assertNull(decoded.meltQuoteId)
        assertEquals(State.Draft, decoded.state)

        assertTrue(StorageKeys.walletMintTransfers in StorageKeys.walletBoundaryKeys)
        assertTrue(StorageKeys.walletMintTransfers.startsWith(StorageKeys.walletDataPrefix))
    }

    // MARK: - Messages

    @Test
    fun `transfer errors have their own copy`() {
        assertEquals(
            "Choose two different mints.",
            MintTransferException.NotEligible(Blocker.SameMint).userFacingWalletMessage,
        )
        assertEquals(
            "Nothing left to transfer after fees.",
            MintTransferException.NothingToTransfer().userFacingWalletMessage,
        )
        assertEquals(
            "This quote expired. Review the transfer again.",
            MintTransferException.PlanExpired().userFacingWalletMessage,
        )
        assertEquals(
            "The transfer didn't go through. Your funds were not moved.",
            MintTransferException.PaymentReturned().userFacingWalletMessage,
        )
        assertTrue(MintTransferException.InsufficientBalance(required = 42, available = 40).isInsufficientBalance)
        assertEquals(
            "A transfer involving this mint is still settling. Keep it connected until the transfer finishes.",
            MintTransferInProgressException().userFacingWalletMessage,
        )
    }

    // MARK: - Fixtures

    private fun plan(amount: Long, feeReserve: Long, inputFee: Long, expiresAtEpochSeconds: Long?) =
        MintTransferPlan(
            id = "plan", mode = MintTransferPlan.Mode.Max,
            sourceMintUrl = "https://source.example", destinationMintUrl = "https://destination.example",
            mintQuoteId = "mint", meltQuoteId = "melt",
            amount = amount, feeReserve = feeReserve, inputFee = inputFee,
            selection = MeltProofSelection.AllUnspentSkippingSwap(expectedInputFee = inputFee),
            expiresAtEpochSeconds = expiresAtEpochSeconds,
        )

    private fun record(id: String, state: State, createdAtEpochMillis: Long = 0) = MintTransferRecord(
        id = id,
        sourceMintUrl = "https://source.example", destinationMintUrl = "https://destination.example",
        mintQuoteId = "mint-$id", meltQuoteId = "melt-$id",
        amount = 40, createdAtEpochMillis = createdAtEpochMillis, state = state,
    )
}
