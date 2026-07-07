package org.cashu.wallet.Core

import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.MintNutSupport
import org.cashu.wallet.Models.MintPaymentMethodSetting
import org.cashu.wallet.Models.PaymentMethodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolFeatureCoverageTest {
    @Test
    fun paymentMethodModelCoversLightningBolt12AndOnchain() {
        assertEquals(
            listOf(PaymentMethodKind.Bolt11, PaymentMethodKind.Bolt12, PaymentMethodKind.Onchain),
            PaymentMethodKind.entries.sortedBy { it.sortOrder },
        )
        assertTrue(PaymentMethodKind.Bolt12.supportsOptionalMintAmount)
        assertTrue(PaymentMethodKind.Onchain.requiresMintAmount)
    }

    @Test
    fun mintMetadataModelCoversLatestUserFacingNuts() {
        val nuts = MintNutSupport(
            nut04 = true,
            nut05 = true,
            nut07 = true,
            nut08 = true,
            nut09 = true,
            nut10 = true,
            nut11 = true,
            nut12 = true,
            nut14 = true,
            nut20 = true,
            nut21 = true,
            nut22 = true,
            nut29 = true,
        )
        val mint = MintInfo(
            url = "https://mint.example",
            nuts = nuts,
            units = listOf("sat", "usd"),
            mintUnits = listOf("sat", "usd"),
            supportedMintMethods = listOf(PaymentMethodKind.Bolt11, PaymentMethodKind.Bolt12, PaymentMethodKind.Onchain),
            supportedMeltMethods = listOf(PaymentMethodKind.Bolt11, PaymentMethodKind.Onchain),
            mintMethodSettings = listOf(
                MintPaymentMethodSetting(
                    method = PaymentMethodKind.Bolt12,
                    unit = "sat",
                    supportsAmountless = true,
                    supportsDescription = true,
                ),
            ),
        )

        assertTrue(mint.nuts.nut09)
        assertTrue(mint.nuts.nut10)
        assertTrue(mint.nuts.nut20)
        assertTrue(mint.nuts.nut21)
        assertTrue(mint.supportsMultipleUnits)
        assertEquals("sat", mint.defaultUnit)
        assertEquals(PaymentMethodKind.Bolt12, mint.mintMethodSettings.single().method)
    }

    @Test
    fun genericMintUnitsAlwaysFormat() {
        val currency = CurrencyRegistry.currencyForMintUnit("points")
        assertEquals("POINTS", currency.code)
        assertEquals("42 POINTS", CurrencyAmount(42, currency).formatted())
    }
}
