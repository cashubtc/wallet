package com.cashu.me.Core

import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import com.cashu.me.Models.liveDetail
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MintReceiptProjectionTest {
    @Test fun settlementWinsWithoutSummingAndOldDetailResolvesToReceipt() {
        val failed = attempt("failed", TransactionStatus.Failed, 30)
        val pending = attempt("pending", TransactionStatus.Pending, 20)
        val completed = attempt("completed", TransactionStatus.Completed, 10)
        for (input in listOf(listOf(failed, pending, completed), listOf(completed, pending, failed))) {
            val rows = MintReceiptProjection.project(input)
            assertEquals(listOf(completed), rows)
            assertEquals(64L, rows.single().amount)
            assertEquals(rows, MintReceiptProjection.project(rows))
            assertEquals(completed, rows.liveDetail(openId = failed.id, openQuoteId = failed.quoteId))
        }
    }

    @Test fun activeAttemptWinsOtherwiseLatestFailureWithDeterministicTieBreak() {
        val failed = attempt("failed", TransactionStatus.Failed, 30)
        val pending = attempt("pending", TransactionStatus.Pending, 20)
        assertEquals(listOf(pending), MintReceiptProjection.project(listOf(failed, pending)))
        for (other in listOf(failed.copy(id = "older", dateEpochMillis = 10), failed.copy(id = "a"))) {
            assertEquals(listOf(failed), MintReceiptProjection.project(listOf(other, failed)))
            assertEquals(listOf(failed), MintReceiptProjection.project(listOf(failed, other)))
        }
    }

    @Test fun distinctPaymentsAndUncertainRecordsRemainSeparate() {
        val first = attempt("first", TransactionStatus.Failed, 1)
        val second = attempt("second", TransactionStatus.Completed, 2)
        for (other in listOf(
            second.copy(quoteId = "another"), second.copy(mintUrl = "https://another.example"),
            second.copy(unit = "usd"), second.copy(invoice = "another-invoice"),
            second.copy(quoteId = null), second.copy(mintUrl = null), second.copy(sagaId = null),
            second.copy(paymentMethod = null), second.copy(amount = 65),
        )) assertEquals(2, MintReceiptProjection.project(listOf(first, other)).size)
        for (method in listOf(PaymentMethodKind.Bolt12, PaymentMethodKind.Onchain)) {
            assertEquals(2, MintReceiptProjection.project(listOf(first.copy(paymentMethod = method), second.copy(paymentMethod = method))).size)
        }
        assertEquals(2, MintReceiptProjection.project(listOf(first.copy(type = TransactionType.Outgoing), second.copy(type = TransactionType.Outgoing))).size)
        assertEquals(2, MintReceiptProjection.project(listOf(first.copy(status = TransactionStatus.Completed), second)).size)
    }

    @Test fun methodSurvivesCacheAndLegacyCacheStillDecodes() {
        val tx = attempt("cached", TransactionStatus.Completed, 1)
        val json = Json { encodeDefaults = true }
        val encoded = json.encodeToString(WalletTransaction.serializer(), tx)
        assertEquals(tx, json.decodeFromString(WalletTransaction.serializer(), encoded))
        val legacy = encoded.replace(",\"paymentMethod\":\"bolt11\"", "")
        assertNull(json.decodeFromString(WalletTransaction.serializer(), legacy).paymentMethod)
    }

    private fun attempt(id: String, status: TransactionStatus, time: Long) = WalletTransaction(
        id = id, amount = 64, type = TransactionType.Incoming, kind = TransactionKind.Lightning,
        dateEpochMillis = time, status = status, mintUrl = "https://mint.example", invoice = "lnbc-fixture",
        sagaId = id, quoteId = "quote", paymentMethod = PaymentMethodKind.Bolt11,
    )
}
