package com.cashu.me.Core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MintQuoteCheckBackoffTest {
    @Test
    fun intervalStaysFlatThenGrowsExponentiallyAndCaps() {
        assertEquals(0L, MintQuoteCheckBackoff.intervalAfterUnpaidStreakMs(0))
        for (streak in 1..MintQuoteCheckBackoff.FLAT_STREAK_COUNT) {
            assertEquals(5_000L, MintQuoteCheckBackoff.intervalAfterUnpaidStreakMs(streak))
        }
        assertEquals(10_000L, MintQuoteCheckBackoff.intervalAfterUnpaidStreakMs(6))
        assertEquals(20_000L, MintQuoteCheckBackoff.intervalAfterUnpaidStreakMs(7))
        assertEquals(40_000L, MintQuoteCheckBackoff.intervalAfterUnpaidStreakMs(8))
        assertEquals(MintQuoteCheckBackoff.MAX_INTERVAL_MS, MintQuoteCheckBackoff.intervalAfterUnpaidStreakMs(30))
    }

    @Test
    fun neverCheckedIsAlwaysEligible() {
        assertTrue(MintQuoteCheckBackoff.shouldCheck(null, nowMs = 1_000L, force = false))
        assertTrue(MintQuoteCheckBackoff.shouldCheck(null, nowMs = 1_000L, force = true))
    }

    @Test
    fun passiveHonorsBackoffAfterUnpaid() {
        val t0 = 1_000_000L
        val afterFirst = MintQuoteCheckBackoff.afterUnpaidOrError(null, nowMs = t0)
        assertEquals(1, afterFirst.unpaidStreak)
        assertFalse(
            MintQuoteCheckBackoff.shouldCheck(afterFirst, nowMs = t0 + 2_000L, force = false),
        )
        assertTrue(
            MintQuoteCheckBackoff.shouldCheck(afterFirst, nowMs = t0 + 5_000L, force = false),
        )
        // Still in the flat 5×5s window through streak 5.
        val afterFifth = (1..4).fold(afterFirst) { entry, i ->
            MintQuoteCheckBackoff.afterUnpaidOrError(entry, nowMs = t0 + i * 5_000L)
        }
        assertEquals(5, afterFifth.unpaidStreak)
        assertFalse(
            MintQuoteCheckBackoff.shouldCheck(afterFifth, nowMs = afterFifth.lastCheckedAtMs + 2_000L, force = false),
        )
        assertTrue(
            MintQuoteCheckBackoff.shouldCheck(afterFifth, nowMs = afterFifth.lastCheckedAtMs + 5_000L, force = false),
        )
        val afterSixth = MintQuoteCheckBackoff.afterUnpaidOrError(
            afterFifth,
            nowMs = afterFifth.lastCheckedAtMs + 5_000L,
        )
        assertEquals(6, afterSixth.unpaidStreak)
        assertFalse(
            MintQuoteCheckBackoff.shouldCheck(afterSixth, nowMs = afterSixth.lastCheckedAtMs + 5_000L, force = false),
        )
        assertTrue(
            MintQuoteCheckBackoff.shouldCheck(afterSixth, nowMs = afterSixth.lastCheckedAtMs + 10_000L, force = false),
        )
    }

    @Test
    fun forceOnlyDebouncesBriefly() {
        val t0 = 1_000_000L
        val entry = MintQuoteCheckBackoff.afterUnpaidOrError(null, nowMs = t0)
        assertFalse(
            MintQuoteCheckBackoff.shouldCheck(entry, nowMs = t0 + 500L, force = true),
        )
        assertTrue(
            MintQuoteCheckBackoff.shouldCheck(
                entry,
                nowMs = t0 + MintQuoteCheckBackoff.FORCED_MIN_INTERVAL_MS,
                force = true,
            ),
        )
    }

    @Test
    fun mintedResetsStreak() {
        val t0 = 1_000_000L
        val unpaid = MintQuoteCheckBackoff.afterUnpaidOrError(null, nowMs = t0)
        val settled = MintQuoteCheckBackoff.afterMintedOrSettled(nowMs = t0 + 5_000L)
        assertEquals(0, settled.unpaidStreak)
        assertEquals(1, unpaid.unpaidStreak)
        // Streak 0 still waits base interval (coerceAtLeast(1)) after last check.
        assertFalse(
            MintQuoteCheckBackoff.shouldCheck(settled, nowMs = t0 + 5_000L + 1_000L, force = false),
        )
        assertTrue(
            MintQuoteCheckBackoff.shouldCheck(settled, nowMs = t0 + 5_000L + 5_000L, force = false),
        )
    }
}
