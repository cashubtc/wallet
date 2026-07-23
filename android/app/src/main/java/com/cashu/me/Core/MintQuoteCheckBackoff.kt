package com.cashu.me.Core

/**
 * Per-quote check spacing for passive mint-quote sync (foreground poll,
 * History open, startup). Without this, every unissued quote is hit on every
 * pass — painful with hundreds of leftover invoices.
 *
 * User-triggered paths (`force = true`: pull-to-refresh, transaction detail)
 * only honor a short debounce so a double-tap can't spam the mint.
 */
object MintQuoteCheckBackoff {
    const val BASE_INTERVAL_MS = 5_000L
    /** Stay at [BASE_INTERVAL_MS] for this many unpaid streaks before ramping. */
    const val FLAT_STREAK_COUNT = 5
    const val MAX_INTERVAL_MS = 15 * 60_000L
    const val FORCED_MIN_INTERVAL_MS = 1_500L
    /** Cap network checks per passive bulk pass (oldest-checked first). */
    const val MAX_PASSIVE_CHECKS_PER_PASS = 20

    data class Entry(
        val lastCheckedAtMs: Long = 0L,
        /** Consecutive checks that did not mint (still unpaid / errors). */
        val unpaidStreak: Int = 0,
    )

    /**
     * Wait after [unpaidStreak] completed non-mint checks before the next
     * passive check: 5s × 5, then 10s, 20s, 40s, … capped at 15 min.
     */
    fun intervalAfterUnpaidStreakMs(unpaidStreak: Int): Long {
        if (unpaidStreak <= 0) return 0L
        if (unpaidStreak <= FLAT_STREAK_COUNT) return BASE_INTERVAL_MS
        // streak 6 → 10s, 7 → 20s, … (exp step starts at 1 after the flat run)
        val exp = (unpaidStreak - FLAT_STREAK_COUNT).coerceIn(1, 10)
        val raw = BASE_INTERVAL_MS * (1L shl exp)
        return minOf(raw, MAX_INTERVAL_MS)
    }

    fun shouldCheck(entry: Entry?, nowMs: Long, force: Boolean): Boolean {
        if (entry == null || entry.lastCheckedAtMs <= 0L) return true
        val minGap = if (force) {
            FORCED_MIN_INTERVAL_MS
        } else {
            intervalAfterUnpaidStreakMs(entry.unpaidStreak.coerceAtLeast(1))
        }
        return nowMs - entry.lastCheckedAtMs >= minGap
    }

    fun afterUnpaidOrError(entry: Entry?, nowMs: Long): Entry =
        Entry(
            lastCheckedAtMs = nowMs,
            unpaidStreak = (entry?.unpaidStreak ?: 0) + 1,
        )

    fun afterMintedOrSettled(nowMs: Long): Entry =
        Entry(lastCheckedAtMs = nowMs, unpaidStreak = 0)
}
