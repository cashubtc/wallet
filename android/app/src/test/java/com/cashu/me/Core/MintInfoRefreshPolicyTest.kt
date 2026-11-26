package com.cashu.me.Core

import com.cashu.me.Models.Bolt11SatCapability
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.NutSupport
import com.cashu.me.Models.PaymentMethodKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MintInfoRefreshPolicyTest {

    @Test
    fun hasSameContentAsIgnoresTimestampAndDetectsSemanticChanges() {
        val base = MintInfo(
            url = "https://mint.example",
            name = "Example Mint",
            description = "Desc",
            isActive = true,
            balance = 100L,
            iconUrl = "https://mint.example/icon.png",
            units = listOf("sat", "usd"),
            mintUnits = listOf("sat"),
            supportedMintMethods = listOf(PaymentMethodKind.Bolt11),
            supportedMeltMethods = listOf(PaymentMethodKind.Bolt11),
            supportsBolt12MintDescription = false,
            supportsAmountlessBolt11Melt = true,
            bolt11Sat = Bolt11SatCapability(canMint = true, canMelt = true, mintMin = 1, mintMax = 1000),
            nutSupport = NutSupport(tokenStateCheck = true, p2pk = true),
            lastUpdatedEpochMillis = 1_000L,
        )

        val timestampOnlyChange = base.copy(lastUpdatedEpochMillis = 999_999L)
        assertFalse(base == timestampOnlyChange)
        assertTrue(base.hasSameContentAs(timestampOnlyChange))

        assertFalse(base.hasSameContentAs(base.copy(name = "Renamed Mint")))
        assertFalse(base.hasSameContentAs(base.copy(units = listOf("sat", "eur"))))
        assertFalse(base.hasSameContentAs(base.copy(nutSupport = NutSupport(tokenStateCheck = true, p2pk = false))))
        assertFalse(base.hasSameContentAs(base.copy(bolt11Sat = Bolt11SatCapability(canMint = true, canMelt = false))))
    }

    @Test
    fun unchangedMintDoesNotDirtyPersistenceAndRespectsFiveMinuteTtl() = runBlocking {
        var now = 1_000_000L
        val staleAt = now - MintInfoRefresher.DEFAULT_TTL_MILLIS - 1_000L
        val refresher = MintInfoRefresher(nowEpochMillis = { now })
        val mint = MintInfo(
            url = "https://mint.example",
            name = "Stable Mint",
            description = "Same",
            supportedMintMethods = listOf(PaymentMethodKind.Bolt11),
            supportedMeltMethods = listOf(PaymentMethodKind.Bolt11),
            nutSupport = NutSupport(tokenStateCheck = true),
            lastUpdatedEpochMillis = staleAt,
        )
        var fetches = 0

        // First passive pass: stale -> fetches, but unchanged semantic fields
        // produce an empty update map (no storage write or state re-emission).
        val first = refresher.refresh(
            mints = listOf(mint),
            force = false,
            fetchMintInfo = {
                fetches += 1
                mint.copy(lastUpdatedEpochMillis = now)
            },
        )
        assertEquals(1, fetches)
        assertTrue(first.isEmpty())

        // Within 5 minutes: in-memory freshness cache suppresses network fetch.
        now += MintInfoRefresher.DEFAULT_TTL_MILLIS - 1L
        val second = refresher.refresh(
            mints = listOf(mint),
            force = false,
            fetchMintInfo = {
                fetches += 1
                mint.copy(lastUpdatedEpochMillis = now)
            },
        )
        assertEquals(1, fetches)
        assertTrue(second.isEmpty())

        // Once 5 minutes elapse: stale again -> fetches.
        now += 1L
        val third = refresher.refresh(
            mints = listOf(mint),
            force = false,
            fetchMintInfo = {
                fetches += 1
                mint.copy(lastUpdatedEpochMillis = now)
            },
        )
        assertEquals(2, fetches)
        assertTrue(third.isEmpty())
    }

    @Test
    fun forceRefreshBypassesFiveMinuteTtl() = runBlocking {
        var now = 1_000_000L
        val refresher = MintInfoRefresher(nowEpochMillis = { now })
        val freshMint = MintInfo(
            url = "https://mint.example",
            name = "Fresh Mint",
            lastUpdatedEpochMillis = now - 1_000L,
        )
        var fetches = 0

        val passive = refresher.refresh(
            mints = listOf(freshMint),
            force = false,
            fetchMintInfo = {
                fetches += 1
                freshMint.copy(name = "Renamed", lastUpdatedEpochMillis = now)
            },
        )
        assertEquals(0, fetches)
        assertTrue(passive.isEmpty())

        val forced = refresher.refresh(
            mints = listOf(freshMint),
            force = true,
            fetchMintInfo = {
                fetches += 1
                freshMint.copy(name = "Renamed", lastUpdatedEpochMillis = now)
            },
        )
        assertEquals(1, fetches)
        assertEquals("Renamed", forced[freshMint.url]?.name)
        assertEquals(now, forced[freshMint.url]?.lastUpdatedEpochMillis)
    }

    @Test
    fun changedMintPreservesBalanceAndUpdatesTimestamp() {
        val existing = MintInfo(
            url = "https://mint.example",
            name = "Old Name",
            balance = 250L,
            isActive = true,
            lastUpdatedEpochMillis = 1_000L,
        )
        val fetched = MintInfo(
            url = "https://mint.example",
            name = "New Name",
            balance = 0L,
            isActive = false,
            nutSupport = NutSupport(p2pk = true),
            lastUpdatedEpochMillis = 50_000L,
        )

        val merged = MintInfoRefresher.mergeIfChanged(existing, fetched, nowEpochMillis = 60_000L)
        assertEquals("New Name", merged?.name)
        assertEquals(250L, merged?.balance)
        assertEquals(true, merged?.isActive)
        assertEquals(NutSupport(p2pk = true), merged?.nutSupport)
        assertEquals(60_000L, merged?.lastUpdatedEpochMillis)

        // Identical semantic fields yield null even with a newer timestamp.
        assertNull(
            MintInfoRefresher.mergeIfChanged(
                existing = checkNotNull(merged),
                fetched = fetched.copy(lastUpdatedEpochMillis = 90_000L),
                nowEpochMillis = 95_000L,
            ),
        )
    }

    @Test
    fun failedFetchDoesNotMarkMintFresh() = runBlocking {
        val now = 1_000_000L
        val staleMint = MintInfo(
            url = "https://mint.example",
            name = "Initial",
            lastUpdatedEpochMillis = now - MintInfoRefresher.DEFAULT_TTL_MILLIS - 1_000L,
        )
        val refresher = MintInfoRefresher(nowEpochMillis = { now })
        var attempts = 0
        var failureLogged = false

        val first = refresher.refresh(
            mints = listOf(staleMint),
            force = false,
            fetchMintInfo = {
                attempts += 1
                error("offline")
            },
            onFetchFailed = { _, _ -> failureLogged = true },
        )
        assertTrue(first.isEmpty())
        assertTrue(failureLogged)
        assertEquals(1, attempts)

        val second = refresher.refresh(
            mints = listOf(staleMint),
            force = false,
            fetchMintInfo = {
                attempts += 1
                staleMint.copy(name = "Recovered")
            },
        )
        assertEquals(2, attempts)
        assertEquals("Recovered", second[staleMint.url]?.name)
    }

    @Test
    fun forgetAndClearResetInMemoryFreshnessAndClockJumpBackRefreshes() {
        var now = 1_000_000L
        val staleAt = now - MintInfoRefresher.DEFAULT_TTL_MILLIS - 1_000L
        val mint = MintInfo(url = "https://mint.example", name = "Mint", lastUpdatedEpochMillis = staleAt)
        val refresher = MintInfoRefresher(nowEpochMillis = { now })

        refresher.recordChecked("https://MINT.example/", checkedAtEpochMillis = now)
        assertFalse(refresher.shouldRefresh(mint, force = false))

        refresher.forget("https://mint.example")
        assertTrue(refresher.shouldRefresh(mint, force = false))

        refresher.recordChecked(mint.url, checkedAtEpochMillis = now)
        assertFalse(refresher.shouldRefresh(mint, force = false))

        // Clock jumped backward -> treat as due rather than stalling indefinitely.
        assertTrue(refresher.shouldRefresh(mint, force = false, now = now - 10_000L))

        refresher.clear()
        assertTrue(refresher.shouldRefresh(mint, force = false))
    }
}
