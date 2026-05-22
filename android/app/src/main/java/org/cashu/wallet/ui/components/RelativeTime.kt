package org.cashu.wallet.ui.components

import java.util.concurrent.TimeUnit

/**
 * Compact, iOS-flavored relative timestamp. Examples: "just now", "5 min ago", "2 hr ago",
 * "3 d ago", "2 wk ago". Falls through to month/year for older entries.
 */
fun formatRelativeTimestamp(epochMillis: Long, nowMillis: Long = System.currentTimeMillis()): String {
    val diff = (nowMillis - epochMillis).coerceAtLeast(0)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(diff)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        seconds < 45 -> "just now"
        minutes < 60 -> "$minutes min ago"
        hours < 24 -> "$hours hr ago"
        days < 7 -> "$days d ago"
        days < 30 -> "${days / 7} wk ago"
        days < 365 -> "${days / 30} mo ago"
        else -> "${days / 365} yr ago"
    }
}
