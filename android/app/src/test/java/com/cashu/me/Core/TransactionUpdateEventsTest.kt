package com.cashu.me.Core

import com.cashu.me.Models.PendingReceiveToken
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class TransactionUpdateEventsTest {
    @Test
    fun incrementsTransactionUpdateVersion() {
        assertEquals(1, nextTransactionUpdateVersion(0))
        assertEquals(42, nextTransactionUpdateVersion(41))
    }

    @Test
    fun wrapsMaxValueToNonZeroVersion() {
        assertEquals(1, nextTransactionUpdateVersion(Long.MAX_VALUE))
    }

    @Test
    fun initialEmptyLoadIncrementsVersionToMarkHistoryReady() {
        val initial = WalletState(isRuntimeReady = true, transactionUpdateVersion = 0)
        val afterFirstLoad = initial.withLoadedTransactions(
            loadedTransactions = emptyList(),
            loadedPendingReceiveTokens = emptyList(),
        )

        assertEquals(1L, afterFirstLoad.transactionUpdateVersion)
    }

    @Test
    fun identicalReloadLeavesVersionAndStateReferenceUnchanged() {
        val tx = sampleTransaction(id = "tx-1", status = TransactionStatus.Completed)
        val pending = samplePendingToken(tokenId = "tok-1")
        val initial = WalletState(isRuntimeReady = true)

        val afterFirstLoad = initial.withLoadedTransactions(
            loadedTransactions = listOf(tx),
            loadedPendingReceiveTokens = listOf(pending),
        )
        assertEquals(1L, afterFirstLoad.transactionUpdateVersion)

        val afterIdenticalReload = afterFirstLoad.withLoadedTransactions(
            loadedTransactions = listOf(tx.copy()),
            loadedPendingReceiveTokens = listOf(pending.copy()),
        )
        assertEquals(1L, afterIdenticalReload.transactionUpdateVersion)
        assertSame(afterFirstLoad, afterIdenticalReload)
    }

    @Test
    fun transactionOrPendingTokenChangeIncrementsVersion() {
        val pendingTx = sampleTransaction(id = "tx-1", status = TransactionStatus.Pending)
        val completedTx = pendingTx.copy(status = TransactionStatus.Completed)
        val pendingToken = samplePendingToken(tokenId = "tok-1")

        val loaded = WalletState(isRuntimeReady = true).withLoadedTransactions(
            loadedTransactions = listOf(pendingTx),
            loadedPendingReceiveTokens = emptyList(),
        )
        assertEquals(1L, loaded.transactionUpdateVersion)

        val afterTxChange = loaded.withLoadedTransactions(
            loadedTransactions = listOf(completedTx),
            loadedPendingReceiveTokens = emptyList(),
        )
        assertEquals(2L, afterTxChange.transactionUpdateVersion)
        assertNotSame(loaded, afterTxChange)

        val afterPendingTokenAdded = afterTxChange.withLoadedTransactions(
            loadedTransactions = listOf(completedTx),
            loadedPendingReceiveTokens = listOf(pendingToken),
        )
        assertEquals(3L, afterPendingTokenAdded.transactionUpdateVersion)
        assertEquals(listOf(pendingToken), afterPendingTokenAdded.pendingReceiveTokens)
    }

    @Test
    fun maxVersionStaysUnchangedOnIdenticalReloadAndWrapsOnChange() {
        val tx = sampleTransaction(id = "tx-1", status = TransactionStatus.Completed)
        val atMax = WalletState(
            isRuntimeReady = true,
            transactions = listOf(tx),
            transactionUpdateVersion = Long.MAX_VALUE,
        )

        val unchanged = atMax.withLoadedTransactions(
            loadedTransactions = listOf(tx),
            loadedPendingReceiveTokens = emptyList(),
        )
        assertEquals(Long.MAX_VALUE, unchanged.transactionUpdateVersion)
        assertSame(atMax, unchanged)

        val changed = atMax.withLoadedTransactions(
            loadedTransactions = emptyList(),
            loadedPendingReceiveTokens = emptyList(),
        )
        assertEquals(1L, changed.transactionUpdateVersion)
    }

    private fun sampleTransaction(id: String, status: TransactionStatus) = WalletTransaction(
        id = id,
        amount = 21,
        type = TransactionType.Incoming,
        kind = TransactionKind.Lightning,
        dateEpochMillis = 1_750_000_000_000L,
        status = status,
        mintUrl = "https://mint.example.com",
    )

    private fun samplePendingToken(tokenId: String) = PendingReceiveToken(
        tokenId = tokenId,
        token = "cashuAexample",
        amount = 100,
        dateEpochMillis = 1_750_000_000_000L,
        mintUrl = "https://mint.example.com",
    )
}

