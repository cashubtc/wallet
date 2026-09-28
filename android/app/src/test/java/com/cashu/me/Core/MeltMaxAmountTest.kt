package com.cashu.me.Core

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

    @Test(expected = IllegalStateException::class)
    fun quoteFailuresReachTheCaller() {
        runBlocking { largestPayableMeltAmount(100) { error("mint down") } }
    }
}
