package com.cashu.me.Core

import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeActivityTest {
    @Test
    fun showsOnlyLatestCompletedSentAndReceivedTransactions() {
        val transactions = listOf(
            transaction("received-via-request", TransactionStatus.Completed, 20, TransactionType.Incoming)
                .copy(cashuRequestId = "request"),
            transaction("pending-request", TransactionStatus.Pending, 50),
            transaction("failed", TransactionStatus.Failed, 40),
            transaction("sent", TransactionStatus.Completed, 30),
            transaction("expired", TransactionStatus.Expired, 60),
        )

        val recent = recentCompletedTransactions(transactions, limit = 5)

        assertEquals(listOf("sent", "received-via-request"), recent.map { it.id })
    }

    @Test
    fun appliesTheRecentLimitAfterFilteringAndSorting() {
        val transactions = listOf(
            transaction("old", TransactionStatus.Completed, 10),
            transaction("new", TransactionStatus.Completed, 30),
            transaction("middle", TransactionStatus.Completed, 20),
        )

        assertEquals(
            listOf("new", "middle"),
            recentCompletedTransactions(transactions, limit = 2).map { it.id },
        )
    }

    private fun transaction(
        id: String,
        status: TransactionStatus,
        dateEpochMillis: Long,
        type: TransactionType = TransactionType.Outgoing,
    ) = WalletTransaction(
        id = id,
        amount = 1,
        type = type,
        kind = TransactionKind.Ecash,
        dateEpochMillis = dateEpochMillis,
        status = status,
    )
}
