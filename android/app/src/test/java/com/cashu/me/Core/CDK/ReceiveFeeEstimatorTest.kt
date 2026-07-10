package com.cashu.me.Core.CDK

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ReceiveFeeEstimatorTest {
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
