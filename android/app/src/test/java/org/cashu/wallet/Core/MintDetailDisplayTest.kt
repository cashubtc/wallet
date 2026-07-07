package org.cashu.wallet.Core

import org.cashu.wallet.Models.MintContactInfo
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.MintNutSupport
import org.cashu.wallet.Models.MintPaymentMethodSetting
import org.cashu.wallet.Models.PaymentMethodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MintDetailDisplayTest {
    @Test
    fun capabilitySummaryMapsNutsAndPaymentMethods() {
        val mint = MintInfo(
            url = "https://mint.example",
            supportedMintMethods = listOf(PaymentMethodKind.Bolt11, PaymentMethodKind.Onchain),
            supportedMeltMethods = listOf(PaymentMethodKind.Bolt12),
            nuts = MintNutSupport(nut11 = true, nut14 = true, nut20 = true),
        )

        assertEquals(
            "Lightning, On-chain, Locked ecash, HTLC, WebSockets",
            mintCapabilitySummary(mint),
        )
    }

    @Test
    fun capabilitySummaryFallsBackForBasicMint() {
        val mint = MintInfo(
            url = "https://mint.example",
            supportedMintMethods = emptyList(),
            supportedMeltMethods = emptyList(),
            nuts = MintNutSupport(),
        )

        assertEquals("Basic ecash", mintCapabilitySummary(mint))
    }

    @Test
    fun paymentMethodSettingLabelIncludesRangesAndFlags() {
        val label = mintPaymentMethodSettingLabel(
            MintPaymentMethodSetting(
                method = PaymentMethodKind.Bolt12,
                unit = "usd",
                minAmount = 100,
                maxAmount = 2_500,
                supportsDescription = true,
                supportsAmountless = true,
            ),
        )

        assertEquals("USD · min 100 · max 2500 · description · amountless", label)
    }

    @Test
    fun contactTargetMapsKnownContactTypes() {
        assertEquals(
            "mailto:hello@example.com",
            mintContactTarget(MintContactInfo(method = "email", info = "hello@example.com")),
        )
        assertEquals(
            "https://mint.example",
            mintContactTarget(MintContactInfo(method = "web", info = "mint.example")),
        )
        assertEquals(
            "https://x.com/cashu",
            mintContactTarget(MintContactInfo(method = "x", info = "@cashu")),
        )
        assertEquals(
            "https://t.me/cashu",
            mintContactTarget(MintContactInfo(method = "telegram", info = "cashu")),
        )
        assertEquals(
            "nostr:npub1example",
            mintContactTarget(MintContactInfo(method = "nostr", info = "nostr:npub1example")),
        )
    }

    @Test
    fun contactTargetRejectsUnknownOrNonNostrSpecialCases() {
        assertNull(mintContactTarget(MintContactInfo(method = "matrix", info = "@cashu:example.com")))
        assertNull(mintContactTarget(MintContactInfo(method = "nostr", info = "npub1example")))
    }

    @Test
    fun externalTargetAddsHttpsWhenMissing() {
        assertEquals("https://mint.example/terms", externalTargetWithHttpsFallback("mint.example/terms"))
        assertEquals("http://mint.example", externalTargetWithHttpsFallback("http://mint.example"))
        assertEquals("mailto:hello@example.com", externalTargetWithHttpsFallback("mailto:hello@example.com"))
    }
}
