package com.cashu.me.ui.components

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Smart relative timestamp matching iOS HistoryView.formatRelativeDate:
 *   < 1 min       → "Now"
 *   same day, < 1h → "$N min ago"
 *   same day, ≥ 1h → "HH:mm"  (e.g. "22:57")
 *   yesterday     → "Yesterday HH:mm"
 *   same year     → "MMM d"   (e.g. "May 22")
 *   older         → "MMM d yyyy"
 */
fun formatRelativeTimestamp(
    epochMillis: Long,
    nowMillis: Long = System.currentTimeMillis(),
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val deltaMs = (nowMillis - epochMillis).coerceAtLeast(0)
    if (TimeUnit.MILLISECONDS.toSeconds(deltaMs) < 60) return "Now"

    val nowZoned = Instant.ofEpochMilli(nowMillis).atZone(zone)
    val thenZoned = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val nowDate: LocalDate = nowZoned.toLocalDate()
    val thenDate: LocalDate = thenZoned.toLocalDate()

    val shortTime = DateTimeFormatter.ofPattern("HH:mm", locale)
    val sameYearDate = DateTimeFormatter.ofPattern("MMM d", locale)
    val differentYearDate = DateTimeFormatter.ofPattern("MMM d yyyy", locale)

    return when {
        thenDate == nowDate -> {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(deltaMs)
            if (minutes < 60) "$minutes min ago" else shortTime.format(thenZoned)
        }
        thenDate == nowDate.minusDays(1) -> "Yesterday ${shortTime.format(thenZoned)}"
        thenDate.year == nowDate.year -> sameYearDate.format(thenZoned)
        else -> differentYearDate.format(thenZoned)
    }
}
