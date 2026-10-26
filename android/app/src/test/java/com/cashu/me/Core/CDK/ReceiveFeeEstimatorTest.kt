package com.cashu.me.Core.CDK

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReceiveFeeEstimatorTest {
    @Test
    fun missingKeysetFeesPropagateInsteadOfBecomingZero() = runBlocking {
        val failure = IllegalStateException("Http transport error Some(502): Bad Gateway")
        try {
            receiveFeeForKeysets(listOf("keyset")) { throw failure }
            org.junit.Assert.fail("An unknown fee must not be shown as free")
        } catch (error: IllegalStateException) {
            org.junit.Assert.assertSame(failure, error)
        }
    }

    @Test
    fun roundsCombinedInputFeesOnceAndCachesLookups() = runBlocking {
        var lookups = 0
        val fee = receiveFeeForKeysets(listOf("a", "a", "b")) {
            lookups++
            100L
        }
        assertEquals(1L, fee)
        assertEquals(2, lookups)
        assertEquals(0L, receiveFeeForKeysets(listOf("free")) { 0L })
    }

    @Test
    fun usesCalculatedFeeWhenAvailable() = runBlocking {
        var fallbackUsed = false

        val fee = estimateReceiveFee(
            proofCount = 3,
            calculateFee = { 7 },
            keysetFee = {
                fallbackUsed = true
                2
            },
        )

        assertEquals(7L, fee)
        assertFalse(fallbackUsed)
    }

    @Test
    fun fallsBackToKeysetFeeTimesProofCountWhenCalculateFeeFails() = runBlocking {
        val fee = estimateReceiveFee(
            proofCount = 3,
            calculateFee = { error("calculateFee unavailable") },
            keysetFee = { 2 },
        )

        assertEquals(6L, fee)
    }

    @Test
    fun emptyProofSetHasNoFeeAndDoesNotCallCdk() = runBlocking {
        var calculateCalled = false
        var fallbackCalled = false

        val fee = estimateReceiveFee(
            proofCount = 0,
            calculateFee = {
                calculateCalled = true
                7
            },
            keysetFee = {
                fallbackCalled = true
                2
            },
        )

        assertEquals(0L, fee)
        assertFalse(calculateCalled)
        assertFalse(fallbackCalled)
    }
}
