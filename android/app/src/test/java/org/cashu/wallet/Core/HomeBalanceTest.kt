package org.cashu.wallet.Core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeBalanceTest {
    @Test
    fun unitsAreSatFirstThenHeldNonSatSorted() {
        val units = HomeBalance.homeBalanceUnits(
            mapOf("sat" to 100L, "usd" to 500L, "eur" to 200L, "chf" to 0L),
        )
        assertEquals(listOf("sat", "eur", "usd"), units)
    }

    @Test
    fun satIsAlwaysPresentEvenWithZeroSatBalance() {
        assertEquals(listOf("sat"), HomeBalance.homeBalanceUnits(mapOf("sat" to 0L)))
        assertEquals(listOf("sat"), HomeBalance.homeBalanceUnits(emptyMap()))
    }

    @Test
    fun resolvedUnitClampsBackToSat() {
        val units = listOf("sat", "eur")
        assertEquals("eur", HomeBalance.resolvedUnit("eur", units))
        assertEquals("sat", HomeBalance.resolvedUnit("usd", units))
    }

    @Test
    fun pagerRequiresMultiUnitActiveMintAndHeldNonSatBalance() {
        val held = mapOf("sat" to 100L, "eur" to 200L)
        assertTrue(HomeBalance.showsUnitPager(activeMintSupportsMultipleUnits = true, balancesByUnit = held))
        // Sat-only default mint stays a single hero even when eur is held elsewhere.
        assertFalse(HomeBalance.showsUnitPager(activeMintSupportsMultipleUnits = false, balancesByUnit = held))
        // Multi-unit mint with no non-sat balance stays a single hero.
        assertFalse(
            HomeBalance.showsUnitPager(
                activeMintSupportsMultipleUnits = true,
                balancesByUnit = mapOf("sat" to 100L, "eur" to 0L),
            ),
        )
    }
}
