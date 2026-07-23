import Foundation

/// Per-quote check spacing for passive mint-quote sync (foreground poll,
/// History open, startup). Without this, every unissued quote is hit on every
/// pass — painful with hundreds of leftover invoices.
///
/// User-triggered paths (`force = true`: pull-to-refresh, transaction detail)
/// only honor a short debounce so a double-tap can't spam the mint.
/// Android `MintQuoteCheckBackoff` parity.
enum MintQuoteCheckBackoff {
    static let baseInterval: TimeInterval = 5
    /// Stay at `baseInterval` for this many unpaid streaks before ramping.
    static let flatStreakCount = 5
    static let maxInterval: TimeInterval = 15 * 60
    static let forcedMinInterval: TimeInterval = 1.5
    /// Cap network checks per passive bulk pass (oldest-checked first).
    static let maxPassiveChecksPerPass = 20

    struct Entry {
        var lastCheckedAt: Date = .distantPast
        /// Consecutive checks that did not mint (still unpaid / errors).
        var unpaidStreak: Int = 0
    }

    /// Wait after `unpaidStreak` completed non-mint checks before the next
    /// passive check: 5s × 5, then 10s, 20s, 40s, … capped at 15 min.
    static func intervalAfterUnpaidStreak(_ unpaidStreak: Int) -> TimeInterval {
        guard unpaidStreak > 0 else { return 0 }
        if unpaidStreak <= flatStreakCount { return baseInterval }
        // streak 6 → 10s, 7 → 20s, … (exp step starts at 1 after the flat run)
        let exp = min(max(unpaidStreak - flatStreakCount, 1), 10)
        let raw = baseInterval * pow(2.0, Double(exp))
        return min(raw, maxInterval)
    }

    static func shouldCheck(entry: Entry?, now: Date = Date(), force: Bool) -> Bool {
        guard let entry, entry.lastCheckedAt > .distantPast else { return true }
        let minGap = force
            ? forcedMinInterval
            : intervalAfterUnpaidStreak(max(entry.unpaidStreak, 1))
        return now.timeIntervalSince(entry.lastCheckedAt) >= minGap
    }

    static func afterUnpaidOrError(entry: Entry?, now: Date = Date()) -> Entry {
        Entry(
            lastCheckedAt: now,
            unpaidStreak: (entry?.unpaidStreak ?? 0) + 1
        )
    }

    static func afterMintedOrSettled(now: Date = Date()) -> Entry {
        Entry(lastCheckedAt: now, unpaidStreak: 0)
    }
}
