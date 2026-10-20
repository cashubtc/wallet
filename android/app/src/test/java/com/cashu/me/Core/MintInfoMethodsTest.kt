package com.cashu.me.Core

import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.PaymentMethodKind
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tri-state NUT-04/05 rails on [MintInfo]: null = never fetched (compatibility
 * default applies), empty = reported-absent (stays empty), non-empty = reported.
 */
class MintInfoMethodsTest {

    @Test
    fun bolt12MintDescriptionDefaultsFalseOnUnfetchedRecords() {
        val mint = MintInfo(url = "https://mint.example")
        assertEquals(false, mint.supportsBolt12MintDescription)
    }

    @Test
    fun unknownRailsFallBackToBolt11CompatibilityDefault() {
        val mint = MintInfo(url = "https://mint.example")

        assertEquals(listOf(PaymentMethodKind.Bolt11), mint.effectiveMintMethods)
        assertEquals(listOf(PaymentMethodKind.Bolt11), mint.effectiveMeltMethods)
    }

    @Test
    fun reportedEmptyRailsStayEmpty() {
        val mint = MintInfo(
            url = "https://mint.example",
            supportedMintMethods = emptyList(),
            supportedMeltMethods = emptyList(),
        )

        assertTrue(mint.effectiveMintMethods.isEmpty())
        assertTrue(mint.effectiveMeltMethods.isEmpty())
    }

    @Test
    fun reportedRailsPassThrough() {
        val mint = MintInfo(
            url = "https://mint.example",
            supportedMintMethods = listOf(PaymentMethodKind.Bolt12),
            supportedMeltMethods = listOf(PaymentMethodKind.Bolt11, PaymentMethodKind.Onchain),
        )

        assertEquals(listOf(PaymentMethodKind.Bolt12), mint.effectiveMintMethods)
        assertEquals(listOf(PaymentMethodKind.Bolt11, PaymentMethodKind.Onchain), mint.effectiveMeltMethods)
    }

    @Test
    fun meltSelectionAssumesBolt11OnlyForUnfetchedMints() {
        val unfetched = MintInfo(url = "https://unfetched.example", balance = 100)
        val reportedEmpty = MintInfo(
            url = "https://empty.example",
            balance = 100,
            supportedMeltMethods = emptyList(),
        )

        val bolt11Compatible = compatibleMintsForMeltPayment(
            mints = listOf(unfetched, reportedEmpty),
            paymentMethod = PaymentMethodKind.Bolt11,
        )
        val onchainCompatible = compatibleMintsForMeltPayment(
            mints = listOf(unfetched, reportedEmpty),
            paymentMethod = PaymentMethodKind.Onchain,
        )

        // Unknown (never fetched) keeps the BOLT11 compatibility default…
        assertEquals(listOf(unfetched), bolt11Compatible)
        // …but only for BOLT11, and a mint that reported no melt rails is excluded.
        assertTrue(onchainCompatible.isEmpty())
    }

    @Test
    fun canMeltGatesOnlyAmountlessBolt11OnTheAdvertisement() {
        fun mint(amountless: Boolean?, methods: List<PaymentMethodKind>? = null) = MintInfo(
            url = "https://mint.example",
            supportedMeltMethods = methods,
            supportsAmountlessBolt11Melt = amountless,
        )

        assertTrue(mint(amountless = true).canMelt(PaymentMethodKind.Bolt11, amountless = true))
        assertFalse(mint(amountless = false).canMelt(PaymentMethodKind.Bolt11, amountless = true))
        // Never fetched since this landed: stays eligible and lets the mint decide.
        assertTrue(mint(amountless = null).canMelt(PaymentMethodKind.Bolt11, amountless = true))

        // Invoices that carry an amount, and BOLT12 offers, ignore the flag.
        assertTrue(mint(amountless = false).canMelt(PaymentMethodKind.Bolt11))
        assertTrue(
            mint(amountless = false, methods = listOf(PaymentMethodKind.Bolt12))
                .canMelt(PaymentMethodKind.Bolt12, amountless = true),
        )

        // The method itself is still required.
        assertFalse(
            mint(amountless = true, methods = listOf(PaymentMethodKind.Bolt12))
                .canMelt(PaymentMethodKind.Bolt11, amountless = true),
        )
    }

    @Test
    fun recordsPersistedBeforeTheAmountlessFlagDecodeAsUnknown() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val legacy = json.decodeFromString<MintInfo>("{\"url\":\"https://legacy.example\"}")
        assertNull(legacy.supportsAmountlessBolt11Melt)

        val unsupported = MintInfo(url = "https://no.example", supportsAmountlessBolt11Melt = false)
        assertEquals(
            false,
            json.decodeFromString<MintInfo>(json.encodeToString(unsupported)).supportsAmountlessBolt11Melt,
        )
    }

    @Test
    fun amountlessBolt11SelectionSkipsMintsThatRefuseIt() {
        val refuses = MintInfo(url = "https://refuses.example", balance = 500, supportsAmountlessBolt11Melt = false)
        val pays = MintInfo(url = "https://pays.example", balance = 100, supportsAmountlessBolt11Melt = true)

        assertEquals(
            listOf(pays),
            compatibleMintsForMeltPayment(listOf(refuses, pays), PaymentMethodKind.Bolt11, amountless = true),
        )
        // The wallet's active mint refuses amountless, so the capable mint is chosen instead.
        assertEquals(
            pays,
            selectMintForMeltPayment(
                mints = listOf(refuses, pays),
                selectedMintUrl = null,
                activeMintUrl = refuses.url,
                paymentMethod = PaymentMethodKind.Bolt11,
                minimumAmount = null,
                amountless = true,
            ),
        )
    }
}
