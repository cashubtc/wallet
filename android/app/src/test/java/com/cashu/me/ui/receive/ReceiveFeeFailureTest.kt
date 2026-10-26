package com.cashu.me.ui.receive

import com.cashu.me.Core.Wallet.ReceiveFeeFailure
import com.cashu.me.Models.TokenInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ReceiveFeeFailureTest {
    private val info = TokenInfo(2900, "https://mint.example", "sat", null, 4)

    @Test fun unavailableMintCanBeSavedAndRetried() = runBlocking {
        val failed = loadTokenReview("cashu-token", info, P2PKLockState.Unlocked) {
            throw IllegalStateException("Http transport error Some(502): Bad Gateway")
        }
        assertNull(failed.fee)
        assertFalse(failed.canClaim)
        assertEquals(ReceiveFeeFailure.MintUnavailable, failed.feeFailure)
        assertEquals("cashu-token", pendingReceiveTokenFrom(failed).token)
        assertEquals(2900L, pendingReceiveTokenFrom(failed).amount)

        val retried = loadTokenReview("cashu-token", info, P2PKLockState.Unlocked) { 2L }
        assertEquals(2L, retried.fee)
        assertNull(retried.feeFailure)
        assertTrue(retried.canClaim)
    }

    @Test fun feeFailuresNeverBecomeFreeReceives() = runBlocking {
        val failed = loadTokenReview("cashu-token", info, P2PKLockState.Unlocked) {
            throw IllegalStateException("Unknown keyset")
        }
        assertEquals(ReceiveFeeFailure.Unavailable, failed.feeFailure)
        assertNull(failed.fee)
        assertFalse(failed.canClaim)
        assertTrue(loadTokenReview("cashu-token", info, P2PKLockState.Unlocked) { 0L }.canClaim)
    }

    @Test fun cancellationIsNotAnOfflineMint() = runBlocking {
        try {
            loadTokenReview("cashu-token", info, P2PKLockState.Unlocked) { throw CancellationException() }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) { }
    }

    @Test fun classifiesTransportFailuresWithoutMislabelingRejections() {
        for (raw in listOf("HTTP status 503", "HTTP 504", "request timed out",
            "error sending request for url (https://mint.example/v1/keysets)",
            "dns error: failed to lookup address", "Connection refused")) {
            assertEquals(raw, ReceiveFeeFailure.MintUnavailable, ReceiveFeeFailure.classify(IllegalStateException(raw)))
        }
        assertEquals(ReceiveFeeFailure.MintUnavailable, ReceiveFeeFailure.classify(java.net.UnknownHostException()))
        for (raw in listOf("HTTP 401 Unauthorized", "HTTP 404 Not Found", "Unknown keyset",
            "The mint returned an invalid receive fee.", "database is locked")) {
            assertEquals(raw, ReceiveFeeFailure.Unavailable, ReceiveFeeFailure.classify(IllegalStateException(raw)))
        }
    }
}
