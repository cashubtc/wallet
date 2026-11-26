package com.cashu.me.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

class RelativeTimeTest {

    private val zone: ZoneId = ZoneId.of("UTC")
    private val now: ZonedDateTime = Instant.parse("2026-08-02T12:00:00Z").atZone(zone)
    private val nowMillis = now.toInstant().toEpochMilli()

    private fun recency(ageMillis: Long): String =
        formatRelativeRecency(nowMillis - ageMillis, nowMillis, zone)

    @Test
    fun zeroAgeReadsNow() {
        assertEquals("now", recency(0))
    }

    @Test
    fun futureTimestampClampsToNow() {
        assertEquals("now", recency(-TimeUnit.MINUTES.toMillis(5)))
    }

    @Test
    fun secondsAgo() {
        assertEquals("1 sec ago", recency(TimeUnit.SECONDS.toMillis(1)))
        assertEquals("45 sec ago", recency(TimeUnit.SECONDS.toMillis(45)))
    }

    @Test
    fun minutesAgo() {
        assertEquals("1 min ago", recency(TimeUnit.MINUTES.toMillis(1)))
        assertEquals("5 min ago", recency(TimeUnit.MINUTES.toMillis(5)))
        assertEquals("59 min ago", recency(TimeUnit.MINUTES.toMillis(59)))
    }

    @Test
    fun hoursAgo() {
        assertEquals("1 hr ago", recency(TimeUnit.MINUTES.toMillis(90)))
        assertEquals("2 hr ago", recency(TimeUnit.HOURS.toMillis(2)))
    }

    @Test
    fun daysAgo() {
        assertEquals("1 day ago", recency(TimeUnit.HOURS.toMillis(25)))
        assertEquals("6 days ago", recency(TimeUnit.DAYS.toMillis(6)))
    }

    @Test
    fun weeksAgo() {
        assertEquals("1 wk ago", recency(TimeUnit.DAYS.toMillis(13)))
        assertEquals("3 wk ago", recency(TimeUnit.DAYS.toMillis(27)))
    }

    @Test
    fun monthsAgo() {
        assertEquals("1 mo ago", recency(TimeUnit.DAYS.toMillis(40)))
        assertEquals("2 mo ago", recency(TimeUnit.DAYS.toMillis(75)))
    }

    @Test
    fun yearsAgo() {
        assertEquals("1 yr ago", recency(TimeUnit.DAYS.toMillis(400)))
    }

    @Test
    fun stalePriceTimestampStaysAccurate() {
        val stale = formatRelativeRecency(
            epochMillis = nowMillis - TimeUnit.HOURS.toMillis(2),
            nowMillis = nowMillis,
            zone = zone,
        )
        assertEquals("2 hr ago", stale)
    }

    @Test
    fun relativeTimestampFormatsAllRangesForUsLocale() {
        assertEquals(
            "Now",
            formatRelativeTimestamp(
                epochMillis = nowMillis - TimeUnit.SECONDS.toMillis(30),
                nowMillis = nowMillis,
                zone = zone,
                locale = Locale.US,
            ),
        )
        assertEquals(
            "15 min ago",
            formatRelativeTimestamp(
                epochMillis = nowMillis - TimeUnit.MINUTES.toMillis(15),
                nowMillis = nowMillis,
                zone = zone,
                locale = Locale.US,
            ),
        )
        assertEquals(
            "09:30",
            formatRelativeTimestamp(
                epochMillis = Instant.parse("2026-08-02T09:30:00Z").toEpochMilli(),
                nowMillis = nowMillis,
                zone = zone,
                locale = Locale.US,
            ),
        )
        assertEquals(
            "Yesterday 22:15",
            formatRelativeTimestamp(
                epochMillis = Instant.parse("2026-08-01T22:15:00Z").toEpochMilli(),
                nowMillis = nowMillis,
                zone = zone,
                locale = Locale.US,
            ),
        )
        assertEquals(
            "May 22",
            formatRelativeTimestamp(
                epochMillis = Instant.parse("2026-05-22T12:00:00Z").toEpochMilli(),
                nowMillis = nowMillis,
                zone = zone,
                locale = Locale.US,
            ),
        )
        assertEquals(
            "Dec 22 2025",
            formatRelativeTimestamp(
                epochMillis = Instant.parse("2025-12-22T12:00:00Z").toEpochMilli(),
                nowMillis = nowMillis,
                zone = zone,
                locale = Locale.US,
            ),
        )
    }

    @Test
    fun relativeTimestampRespectsLocaleChangesAcrossCachedFormatters() {
        val sameYearDate = Instant.parse("2026-03-15T10:00:00Z").atZone(zone)
        val sameYearMillis = sameYearDate.toInstant().toEpochMilli()
        val olderYearDate = Instant.parse("2025-12-15T10:00:00Z").atZone(zone)
        val olderYearMillis = olderYearDate.toInstant().toEpochMilli()

        val expectedUsSameYear = DateTimeFormatter.ofPattern("MMM d", Locale.US).format(sameYearDate)
        val expectedDeSameYear = DateTimeFormatter.ofPattern("MMM d", Locale.GERMANY).format(sameYearDate)
        val expectedUsOlder = DateTimeFormatter.ofPattern("MMM d yyyy", Locale.US).format(olderYearDate)
        val expectedDeOlder = DateTimeFormatter.ofPattern("MMM d yyyy", Locale.GERMANY).format(olderYearDate)

        assertNotEquals(expectedUsOlder, expectedDeOlder)

        assertEquals(
            expectedUsSameYear,
            formatRelativeTimestamp(sameYearMillis, nowMillis, zone, Locale.US),
        )
        assertEquals(
            expectedDeSameYear,
            formatRelativeTimestamp(sameYearMillis, nowMillis, zone, Locale.GERMANY),
        )
        assertEquals(
            expectedUsOlder,
            formatRelativeTimestamp(olderYearMillis, nowMillis, zone, Locale.US),
        )
        assertEquals(
            expectedDeOlder,
            formatRelativeTimestamp(olderYearMillis, nowMillis, zone, Locale.GERMANY),
        )
        // Re-verify Locale.US after Locale.GERMANY to ensure cached entries stay isolated.
        assertEquals(
            expectedUsOlder,
            formatRelativeTimestamp(olderYearMillis, nowMillis, zone, Locale.US),
        )
    }
}
