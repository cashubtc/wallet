package org.cashu.wallet.ui.home

import org.cashu.wallet.Models.CashuRequest
import org.cashu.wallet.Models.CashuRequestPayment
import org.cashu.wallet.Models.TransactionKind
import org.cashu.wallet.Models.TransactionStatus
import org.cashu.wallet.Models.TransactionType
import org.cashu.wallet.Models.WalletTransaction
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

    private fun incomingTransaction(id: String, date: Long): WalletTransaction =
        WalletTransaction(
            id = id,
            amount = 21,
            type = TransactionType.Incoming,
            kind = TransactionKind.Ecash,
            dateEpochMillis = date,
            status = TransactionStatus.Completed,
        )
}
