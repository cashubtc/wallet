package com.cashu.me.Core

import com.cashu.me.Core.CDK.LightningAddressResolutionException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fee-aware Max for Lightning and on-chain sends. iOS parity: `SendMaxMeltAmountTests`. */
class MeltMaxAmountTest {
    private fun largest(balance: Long, reserve: (Long) -> Long): Pair<Long?, Int> = runBlocking {
        var quotes = 0
        val payable = largestPayableMeltAmount(balance) { amount ->
            quotes += 1
            amount + reserve(amount)
        }
        payable to quotes
    }

    @Test
    fun flatReserveSettlesOnTheSecondQuote() {
        val (payable, quotes) = largest(1_314) { 13 }
        assertEquals(1_301L, payable)
        assertEquals(2, quotes)
    }

    @Test
    fun percentageReserveWithFloorFitsTheBalance() {
        // 1% rounded up, at least 2 sat — a common Lightning backend shape.
        val reserve = { amount: Long -> maxOf(2L, (amount + 99) / 100) }
        val (payable, _) = largest(1_314, reserve)
        assertEquals(1_300L, payable)
        assertTrue(1_300 + reserve(1_300) <= 1_314)
        assertTrue("1,300 is the largest amount that fits", 1_301 + reserve(1_301) > 1_314)
    }

    @Test
    fun noReserveKeepsTheWholeBalanceWithOneQuote() {
        val (payable, quotes) = largest(500) { 0 }
        assertEquals(500L, payable)
        assertEquals(1, quotes)
    }

    @Test
    fun balanceBelowTheReserveFloorPaysNothing() {
        assertNull(largest(1) { 2 }.first)
    }

    @Test
    fun recipientMaximumIsAppliedBeforeCalculatingFees() = runBlocking {
        val candidates = mutableListOf<Long>()
        val result = largestPayableMeltAmount(500) { amount ->
            candidates += amount
            if (amount > 300) throw LightningAddressResolutionException.AmountOutOfRange(1_000, 300_999)
            amount + 2
        }
        assertEquals(300L, result)
        assertEquals(listOf(500L, 300L), candidates)
    }

    @Test
    fun recipientCapStillLeavesRoomForFees() = runBlocking {
        val result = largestPayableMeltAmount(500) { amount ->
            if (amount > 499) throw LightningAddressResolutionException.AmountOutOfRange(1_000, 499_000)
            amount + 2
        }
        assertEquals(498L, result)
    }

    @Test(expected = LightningAddressResolutionException.AmountOutOfRange::class)
    fun recipientMinimumDoesNotCauseAnUpwardRetry() {
        runBlocking {
            largestPayableMeltAmount(100) {
                throw LightningAddressResolutionException.AmountOutOfRange(200_000, 500_000)
            }
        }
    }

    @Test(expected = IllegalStateException::class)
    fun inconclusiveQuotesDoNotReportAnEmptyBalance() {
        runBlocking { largestPayableMeltAmount(100, rounds = 1) { it + 2 } }
    }

    @Test(expected = IllegalStateException::class)
    fun quoteFailuresReachTheCaller() {
        runBlocking { largestPayableMeltAmount(100) { error("mint down") } }
    }
}
