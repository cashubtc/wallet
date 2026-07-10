package com.cashu.me.Core

import com.cashu.me.Models.MintInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MintInfoUnitsTest {
    private fun mint(
        units: List<String> = listOf("sat"),
        mintUnits: List<String> = emptyList(),
    ) = MintInfo(url = "https://mint.example", units = units, mintUnits = mintUnits)

    @Test
    fun defaultUnitPrefersSatThenFirstSorted() {
        assertEquals("sat", mint(units = listOf("usd", "sat", "eur")).defaultUnit)
        assertEquals("eur", mint(units = listOf("usd", "eur")).defaultUnit)
        assertEquals("sat", mint(units = emptyList()).defaultUnit)
    }

    @Test
    fun resolvedUnitFallsBackWhenUnknown() {
        val m = mint(units = listOf("sat", "usd"))
        assertEquals("usd", m.resolvedUnit("usd"))
        assertEquals("sat", m.resolvedUnit("eur"))
        assertEquals("sat", m.resolvedUnit(null))
    }

    @Test
    fun effectiveMintUnitsFallsBackToFullUnitSetForOldRecords() {
        // Records stored before multi-unit landed have no mintUnits.
        val legacy = mint(units = listOf("sat", "eur"))
        assertEquals(listOf("sat", "eur"), legacy.effectiveMintUnits)
        assertTrue(legacy.supportsMultipleMintUnits)

        val fresh = mint(units = listOf("sat", "eur"), mintUnits = listOf("sat"))
        assertEquals(listOf("sat"), fresh.effectiveMintUnits)
        assertFalse(fresh.supportsMultipleMintUnits)
    }

    @Test
    fun mintUnitResolutionUsesMintableUnits() {
        val m = mint(units = listOf("sat", "eur", "usd"), mintUnits = listOf("sat", "usd"))
        assertEquals("usd", m.resolvedMintUnit("usd"))
        assertEquals("sat", m.resolvedMintUnit("eur"))
        assertEquals("sat", m.defaultMintUnit)
    }

    @Test
    fun multiUnitGates() {
        assertFalse(mint(units = listOf("sat")).supportsMultipleUnits)
        assertTrue(mint(units = listOf("sat", "usd")).supportsMultipleUnits)
    }
}
