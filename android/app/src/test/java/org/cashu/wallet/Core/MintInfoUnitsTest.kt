package org.cashu.wallet.Core

import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.MintContactInfo
import org.cashu.wallet.Models.MintNutSupport
import org.cashu.wallet.Models.MintPaymentMethodSetting
import org.cashu.wallet.Models.PaymentMethodKind
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

    @Test
    fun richMintMetadataDefaultsAndStoresFullInfo() {
        val m = MintInfo(
            url = "https://mint.example",
            pubkey = "a".repeat(64),
            descriptionLong = "Long description",
            motd = "Message",
            tosUrl = "https://mint.example/terms",
            softwareName = "nutshell",
            softwareVersion = "0.17",
            contacts = listOf(MintContactInfo(method = "email", info = "hello@example.com")),
            nuts = MintNutSupport(nut04 = true, nut05 = true, nut10 = true, nut20 = true),
            mintMethodSettings = listOf(
                MintPaymentMethodSetting(
                    method = PaymentMethodKind.Bolt11,
                    unit = "sat",
                    minAmount = 1,
                    maxAmount = 21,
                    supportsDescription = true,
                ),
            ),
        )

        assertEquals("a".repeat(64), m.pubkey)
        assertEquals("Long description", m.descriptionLong)
        assertEquals("Message", m.motd)
        assertEquals("nutshell", m.softwareName)
        assertTrue(m.nuts.nut10)
        assertTrue(m.nuts.nut20)
        assertEquals(PaymentMethodKind.Bolt11, m.mintMethodSettings.single().method)
        assertTrue(m.mintMethodSettings.single().supportsDescription == true)
    }
}
