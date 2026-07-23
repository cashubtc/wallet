package com.cashu.me.Core

import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.WalletTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransactionDetailLookupTest {
    @Test
    fun prefersExactIdMatch() {
        val open = tx(id = "quote-1", quoteId = "quote-1", status = TransactionStatus.Pending)
        val other = tx(id = "cdk-9", quoteId = "quote-1", status = TransactionStatus.Completed)
        assertEquals(open, resolveTransactionForDetail(listOf(other, open), openId = "quote-1"))
    }

    @Test
    fun fallsBackToQuoteIdWhenPendingRowGone() {
        val completed = tx(id = "cdk-9", quoteId = "quote-1", status = TransactionStatus.Completed, date = 200)
        val unrelated = tx(id = "other", quoteId = "other", status = TransactionStatus.Completed, date = 300)
        assertEquals(
            completed,
            resolveTransactionForDetail(
                listOf(unrelated, completed),
                openId = "quote-1",
                openQuoteId = "quote-1",
            ),
        )
    }

    @Test
    fun prefersCompletedOverPendingForSameQuote() {
        val pending = tx(id = "quote-1", quoteId = "quote-1", status = TransactionStatus.Pending, date = 300)
        val completed = tx(id = "cdk-9", quoteId = "quote-1", status = TransactionStatus.Completed, date = 100)
        assertEquals(
            completed,
            resolveTransactionForDetail(
                listOf(pending, completed),
                openId = "missing",
                openQuoteId = "quote-1",
            ),
        )
    }

    @Test
    fun returnsNullWhenNothingMatches() {
        assertNull(
            resolveTransactionForDetail(
                listOf(tx(id = "a", quoteId = "a", status = TransactionStatus.Completed)),
                openId = "missing",
                openQuoteId = "also-missing",
            ),
        )
    }

    private fun tx(
        id: String,
        quoteId: String?,
        status: TransactionStatus,
        date: Long = 0L,
    ) = WalletTransaction(
        id = id,
        amount = 21,
        type = com.cashu.me.Models.TransactionType.Incoming,
        kind = com.cashu.me.Models.TransactionKind.Lightning,
        dateEpochMillis = date,
        status = status,
        quoteId = quoteId,
        invoice = "lnbc1",
    )
}
