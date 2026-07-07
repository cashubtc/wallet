package org.cashu.wallet.ui.home

import org.cashu.wallet.Models.CashuRequest
import org.cashu.wallet.Models.CashuRequestPayment
import org.cashu.wallet.Models.TransactionKind
import org.cashu.wallet.Models.TransactionStatus
import org.cashu.wallet.Models.TransactionType
import org.cashu.wallet.Models.WalletTransaction
import org.cashu.wallet.ui.components.requestRowTitle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRecentTest {
    @Test
    fun cashuRequestPaymentSuppressesMatchingTransactionRow() {
        val transaction = incomingTransaction(id = "event-1", date = 2_000)
        val request = CashuRequest(
            id = "request-1",
            encoded = "creq",
            amount = 21,
            createdAtEpochMillis = 3_000,
            receivedPayments = listOf(
                CashuRequestPayment(
                    transactionId = "event-1",
                    amount = 21,
                    receivedAtEpochMillis = 2_000,
                ),
            ),
        )

        val items = unifiedRecent(
            transactions = listOf(transaction, incomingTransaction(id = "other", date = 1_000)),
            requests = listOf(request),
            limit = 5,
        )

        assertEquals(2, items.size)
        assertTrue(items.first() is HomeRecentItem.Req)
        assertEquals(listOf("req:request-1", "tx:other"), items.map { it.key })
    }

    @Test
    fun cashuRequestPaymentSuppressesMatchingQuoteTransactionRow() {
        val transaction = incomingTransaction(id = "tx-row-id", date = 2_000, quoteId = "quote-1")
        val request = CashuRequest(
            id = "quote-1",
            encoded = "lno...",
            amount = 21,
            quoteId = "quote-1",
            quoteKind = "Bolt12",
            createdAtEpochMillis = 3_000,
            receivedPayments = listOf(
                CashuRequestPayment(
                    transactionId = "quote-1",
                    amount = 21,
                    receivedAtEpochMillis = 2_000,
                ),
            ),
        )

        val items = unifiedRecent(
            transactions = listOf(transaction, incomingTransaction(id = "other", date = 1_000)),
            requests = listOf(request),
            limit = 5,
        )

        assertEquals(listOf("req:quote-1", "tx:other"), items.map { it.key })
    }

    @Test
    fun requestRowTitleUsesQuoteIntentKind() {
        assertEquals(
            "Reusable Invoice",
            requestRowTitle(
                CashuRequest(
                    id = "quote-1",
                    encoded = "lno...",
                    quoteId = "quote-1",
                    quoteKind = "Bolt12",
                ),
            ),
        )
        assertEquals(
            "Cashu Request",
            requestRowTitle(CashuRequest(id = "request-1", encoded = "creq...")),
        )
    }

    private fun incomingTransaction(id: String, date: Long, quoteId: String? = null): WalletTransaction =
        WalletTransaction(
            id = id,
            amount = 21,
            type = TransactionType.Incoming,
            kind = TransactionKind.Ecash,
            dateEpochMillis = date,
            status = TransactionStatus.Completed,
            quoteId = quoteId,
        )
}
