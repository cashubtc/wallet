package com.cashu.me.Core.CDK

import com.cashu.me.Core.Protocols.StorageKeys
import com.cashu.me.Core.Wallet.userFacingWalletMessage
import kotlinx.coroutines.runBlocking
import org.cashudevkit.Amount
import org.cashudevkit.FfiException
import org.cashudevkit.Restored
import org.cashudevkit.RestoreStart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ResumableMintRestoreTest {
    private val mint = "https://mint.example"

    private class MemoryCheckpoints : MintRestoreCheckpoints {
        val values = mutableMapOf<String, MintRestoreCheckpoint>()
        override fun load(mintUrl: String) = values[mintUrl]
        override fun save(mintUrl: String, checkpoint: MintRestoreCheckpoint?) {
            if (checkpoint == null) values.remove(mintUrl) else values[mintUrl] = checkpoint
        }
    }

    private fun restored(spent: ULong, unspent: ULong, pending: ULong, next: Map<String, UInt>) =
        Restored(Amount(spent), Amount(unspent), Amount(pending), next)

    private fun interrupted(unspent: ULong, next: Map<String, UInt>) = FfiException.RestoreInterrupted(
        partial = restored(0u, unspent, 0u, next),
        code = 50000u,
        errorMessage = "connection lost",
    )

    @Test fun advancingSumsAmountsAndKeepsPositionsMonotonic() {
        val first = MintRestoreCheckpoint(mapOf("a" to 300L, "b" to 100L), spent = 5, unspent = 10, pending = 1)
        val next = first.advanced(2, 20, 0, mapOf("a" to 200L, "b" to 450L, "c" to 7L))
        assertEquals(mapOf("a" to 300L, "b" to 450L, "c" to 7L), next.nextKeysetIndices)
        assertEquals(Triple(7L, 30L, 1L), Triple(next.spent, next.unspent, next.pending))
    }

    @Test fun firstAttemptStartsFromZeroAndClearsCheckpointOnSuccess() = runBlocking {
        val store = MemoryCheckpoints()
        val totals = ResumableMintRestore.run(store, mint) { options ->
            assertNull(options.start)
            assertEquals(100u, options.batchSize)
            assertEquals(3u, options.maxGap)
            restored(1u, 2u, 3u, mapOf("a" to 10u))
        }
        assertEquals(RestoredTotals(1, 2, 3), totals)
        assertTrue(store.values.isEmpty())
    }

    @Test fun interruptedRestoreSavesPositionsAndNextAttemptResumesAndSums() = runBlocking {
        val store = MemoryCheckpoints()
        try {
            ResumableMintRestore.run(store, mint) { throw interrupted(40u, mapOf("a" to 500u)) }
            fail("Expected an interrupted restore")
        } catch (error: MintRestoreInterrupted) {
            assertEquals(mapOf("a" to 500L), error.checkpoint.nextKeysetIndices)
            assertEquals("connection lost", error.reason)
        }
        assertEquals(40L, store.values.getValue(mint).unspent)

        var resumedFrom: Map<String, UInt>? = null
        val totals = ResumableMintRestore.run(store, mint) { options ->
            resumedFrom = (options.start as RestoreStart.KeysetIndices).indices
            restored(1u, 2u, 0u, mapOf("a" to 700u))
        }
        assertEquals(mapOf("a" to 500u), resumedFrom)
        assertEquals(RestoredTotals(1, 42, 0), totals)
        assertTrue(store.values.isEmpty())
    }

    @Test fun repeatedInterruptionsAccumulateWithoutRegressing() = runBlocking {
        val store = MemoryCheckpoints()
        for ((next, unspent) in listOf(300u to 30uL, 900u to 60uL)) {
            try {
                ResumableMintRestore.run(store, mint) { throw interrupted(unspent, mapOf("a" to next)) }
                fail("Expected an interrupted restore")
            } catch (_: MintRestoreInterrupted) {
            }
        }
        val checkpoint = store.values.getValue(mint)
        assertEquals(mapOf("a" to 900L), checkpoint.nextKeysetIndices)
        assertEquals(90L, checkpoint.unspent)
    }

    @Test fun nonInterruptionFailureKeepsExistingCheckpoint() = runBlocking {
        val store = MemoryCheckpoints()
        val saved = MintRestoreCheckpoint(mapOf("a" to 200L), spent = 0, unspent = 20, pending = 0)
        store.save(mint, saved)
        try {
            ResumableMintRestore.run(store, mint) { throw FfiException.Internal("offline") }
            fail("Expected failure")
        } catch (_: FfiException.Internal) {
        }
        assertEquals(saved, store.values[mint])
    }

    @Test fun checkpointsAreWalletScopedAndDroppedWithWalletData() {
        assertTrue(StorageKeys.walletRestoreCheckpoints.startsWith(StorageKeys.walletDataPrefix))
        assertTrue(StorageKeys.walletRestoreCheckpoints in StorageKeys.walletBoundaryKeys)
    }

    @Test fun interruptedRestoreMessageTellsUserProgressIsSaved() {
        val error = MintRestoreInterrupted(MintRestoreCheckpoint.Empty, "timeout")
        assertTrue(error.userFacingWalletMessage.contains("progress is saved"))
        assertFalse(error.userFacingWalletMessage.contains("timeout"))
    }
}
