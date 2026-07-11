package com.cashu.me.ui.history

import com.cashu.me.Core.AmountFormatter
import com.cashu.me.Models.CashuRequest
import com.cashu.me.Models.CashuRequestPayment
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import com.cashu.me.ui.components.requestRowAmount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcRequestHistoryTest {
    @Test
    fun `nfc settlement appears once and request row shows net received`() {
        val transaction = WalletTransaction(
            id = "cdk-transaction",
            amount = 96,
            type = TransactionType.Incoming,
            kind = TransactionKind.Lightning,
            dateEpochMillis = 2,
            status = TransactionStatus.Completed,
            mintUrl = "https://active.example",
        )
        val request = CashuRequest(
            id = "request",
            encoded = "creqA",
            amount = 100,
            receivedPayments = listOf(
                CashuRequestPayment("cdk-transaction", amount = 96, receivedAtEpochMillis = 2),
            ),
        )

        val items = unifiedFiltered(
            transactions = listOf(transaction),
            requests = listOf(request),
            filter = com.cashu.me.Core.HistoryFilter.All,
            query = "",
        )

        assertEquals(1, items.size)
        assertTrue(items.single() is HistoryItem.Req)
        assertEquals("96 sat", requestRowAmount(request, AmountFormatter(), useBitcoinSymbol = false))
    }
}
