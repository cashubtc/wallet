package com.cashu.me.Core

import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.MintTransferRecord
import com.cashu.me.Models.MintTransferRecord.State
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import com.cashu.me.ui.history.unifiedFiltered
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** iOS parity: `MintTransferProjectionTests.swift`. */
class MintTransferProjectionTest {
    @Test
    fun completedTransferShowsAsOneNeutralRow() {
        val rows = project(listOf(payment(), receipt()), listOf(record(State.Completed)))

        assertEquals(listOf("payment"), rows.map { it.id })
        val row = rows.single()
        assertEquals("Transfer to Destination", TransactionDisplay.title(row, MINTS))
        assertEquals(TransactionStatus.Completed, row.status)
        // An outgoing row is unsigned and never green.
        assertEquals(TransactionType.Outgoing, row.type)
        assertEquals(40L, row.amount)
        assertEquals(2L, row.fee)
        assertEquals(SOURCE, row.transfer?.sourceMintUrl)
        assertEquals(DESTINATION, row.transfer?.destinationMintUrl)
    }

    /**
     * The invoice was the wallet's own, so the row must never offer it as a
     * code to scan, copy or pay.
     */
    @Test
    fun transferRowCarriesNoPaymentCodeOrInvoiceDescription() {
        val pending = payment(TransactionStatus.Pending).copy(memo = "Destination mint boilerplate")

        val row = project(listOf(pending), listOf(record(State.Committed))).single()

        assertNull(row.invoice)
        assertNull(row.displayDescription)
        assertFalse(TransactionDisplay.showsQr(row))
        assertNull(TransactionDisplay.copyableContent(row))
    }

    @Test
    fun paidButNotYetIssuedReadsAsArriving() {
        val row = project(listOf(payment()), listOf(record(State.Committed))).single()

        assertEquals(TransactionStatus.Pending, row.status)
        assertEquals("Arriving at Destination", row.displayStatusText)
        assertEquals("mint-quote", row.mintQuoteIdForStatusRefresh)
    }

    @Test
    fun paymentStillSettlingReadsAsInProgress() {
        val row = project(listOf(payment(TransactionStatus.Pending)), listOf(record(State.Committed))).single()

        assertEquals(TransactionStatus.Pending, row.status)
        assertEquals("Payment in progress", row.displayStatusText)
    }

    @Test
    fun failedPaymentReadsAsAFailedTransfer() {
        val row = project(listOf(payment(TransactionStatus.Failed)), listOf(record(State.Failed))).single()

        assertEquals("Transfer to Destination", TransactionDisplay.title(row, MINTS))
        assertEquals(TransactionStatus.Failed, row.status)
        assertNull(row.mintQuoteIdForStatusRefresh)
    }

    /** A sweep can issue a transfer the wallet had written off. */
    @Test
    fun issuedReceiptCompletesEvenAFailedRecord() {
        val rows = project(listOf(payment(TransactionStatus.Failed), receipt()), listOf(record(State.Failed)))

        assertEquals(listOf("payment"), rows.map { it.id })
        assertEquals(TransactionStatus.Completed, rows.single().status)
    }

    @Test
    fun rowsWithoutARecordAreLeftAsCdkWroteThem() {
        val rows = project(listOf(payment(), receipt()), emptyList())

        assertEquals(listOf("Lightning paid", "Lightning received"), rows.map(TransactionDisplay::title))
    }

    @Test
    fun draftRecordChangesNothing() {
        val rows = project(listOf(payment(), receipt()), listOf(record(State.Draft)))

        assertEquals(listOf(payment(), receipt()), rows)
    }

    /**
     * Without the source payment there is nothing to anchor the transfer on,
     * so the receipt stays visible rather than the money disappearing.
     */
    @Test
    fun receiptStaysWhenTheSourcePaymentIsMissing() {
        val rows = project(listOf(receipt()), listOf(record(State.Completed)))

        assertEquals(listOf("Lightning received"), rows.map(TransactionDisplay::title))
    }

    @Test
    fun sameQuoteIdAtAnotherMintIsUntouched() {
        val stranger = receipt().copy(mintUrl = "https://other.example")

        val rows = project(listOf(payment(), stranger), listOf(record(State.Completed)))

        assertEquals(
            listOf("Transfer to Destination", "Lightning received"),
            rows.map { TransactionDisplay.title(it, MINTS) },
        )
    }

    @Test
    fun projectingTwiceChangesNothingMore() {
        val once = project(listOf(payment(), receipt()), listOf(record(State.Committed)))
        val twice = project(once, listOf(record(State.Committed)))

        assertEquals(once, twice)
    }

    @Test
    fun homeRecentShowsACompletedTransferOnce() {
        val rows = project(listOf(payment(), receipt()), listOf(record(State.Completed)))

        assertEquals(
            listOf("Transfer to Destination"),
            recentPaymentTransactions(rows, limit = 5).map { TransactionDisplay.title(it, MINTS) },
        )
    }

    @Test
    fun transferIsFoundBySearchingItsTitle() {
        val rows = project(listOf(payment(), receipt()), listOf(record(State.Completed)))

        assertEquals(1, unifiedFiltered(rows, emptyList(), HistoryFilter.All, "transfer").size)
    }

    /** Search matches the title the row shows, so the destination's name finds it. */
    @Test
    fun transferIsFoundBySearchingItsDestinationName() {
        val rows = project(listOf(payment(), receipt()), listOf(record(State.Completed)))

        assertEquals(1, unifiedFiltered(rows, emptyList(), HistoryFilter.All, "to destination", MINTS).size)
        assertEquals(0, unifiedFiltered(rows, emptyList(), HistoryFilter.All, "to source", MINTS).size)
    }

    /** CDK and the record can spell the same mint differently. */
    @Test
    fun equivalentMintUrlSpellingsStillFold() {
        val rows = project(
            listOf(
                payment().copy(mintUrl = "https://Source.example:443/"),
                receipt().copy(mintUrl = "$DESTINATION/"),
            ),
            listOf(record(State.Completed)),
        )

        assertEquals(listOf("Transfer to Destination"), rows.map { TransactionDisplay.title(it, MINTS) })
    }

    /**
     * History is cached as loaded, before transfers are folded, and folded
     * again when the cache is shown. Rows cached before the field existed
     * must still decode.
     */
    @Test
    fun legacyCacheStillDecodesAndAFoldedRowSurvivesTheCacheShape() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val folded = project(listOf(payment(), receipt()), listOf(record(State.Committed))).single()
        val encoded = json.encodeToString(WalletTransaction.serializer(), folded)

        assertEquals(folded, json.decodeFromString(WalletTransaction.serializer(), encoded))
        val legacy = json.encodeToString(WalletTransaction.serializer(), payment()).replace(",\"transfer\":null", "")
        assertFalse(legacy.contains("\"transfer\""))
        assertEquals(payment(), json.decodeFromString(WalletTransaction.serializer(), legacy))
    }

    // MARK: - Fixtures

    private fun project(rows: List<WalletTransaction>, records: List<MintTransferRecord>) =
        MintTransferProjection.project(rows, records) { url ->
            if (url == DESTINATION) "Destination" else "Source"
        }

    private fun payment(status: TransactionStatus = TransactionStatus.Completed) = WalletTransaction(
        id = "payment", amount = 40, type = TransactionType.Outgoing, kind = TransactionKind.Lightning,
        dateEpochMillis = 100_000, status = status, mintUrl = SOURCE, invoice = "lnbc400n1transfer",
        fee = 2, quoteId = "melt-quote", paymentMethod = PaymentMethodKind.Bolt11,
    )

    private fun receipt() = WalletTransaction(
        id = "receipt", amount = 40, type = TransactionType.Incoming, kind = TransactionKind.Lightning,
        dateEpochMillis = 101_000, status = TransactionStatus.Completed, mintUrl = DESTINATION,
        invoice = "lnbc400n1transfer", quoteId = "mint-quote", paymentMethod = PaymentMethodKind.Bolt11,
    )

    private fun record(state: State) = MintTransferRecord(
        id = "transfer", sourceMintUrl = SOURCE, destinationMintUrl = DESTINATION,
        mintQuoteId = "mint-quote", meltQuoteId = "melt-quote",
        amount = 40, createdAtEpochMillis = 99_000, state = state,
    )

    private companion object {
        const val SOURCE = "https://source.example"
        const val DESTINATION = "https://destination.example"
        val MINTS = listOf(MintInfo(url = SOURCE, name = "Source"), MintInfo(url = DESTINATION, name = "Destination"))
    }
}
