package com.cashu.me.Core

import com.cashu.me.Models.Bolt11SatCapability
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.PaymentMethodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** iOS parity: the `testAmountlessBlock…` cases in `MintQuoteDomainTests.swift`. */
class AmountlessMeltBlockTest {

    private val amountless = PaymentRequestDecodeResult.Bolt11(amountSats = null, description = null)

    @Test
    fun onlyAmountlessBolt11CanBeBlocked() {
        val cannot = listOf(mint("https://a.example", amountless = false, balance = 0))

        assertNull(amountlessMeltBlock(PaymentRequestDecodeResult.Bolt11(21, null), cannot))
        assertNull(amountlessMeltBlock(PaymentRequestDecodeResult.Bolt12(null, null), cannot))
        assertNull(amountlessMeltBlock(PaymentRequestDecodeResult.LightningAddress("a@b.example"), cannot))
        assertNull(amountlessMeltBlock(null, cannot))
    }

    @Test
    fun noMintAdvertisingAmountlessIsNoCapableMint() {
        val mints = listOf(
            mint("https://a.example", amountless = false, balance = 103_577),
            mint("https://b.example", amountless = false, balance = 0),
        )

        assertEquals(AmountlessMeltBlock.NoCapableMint, amountlessMeltBlock(amountless, mints))
    }

    @Test
    fun aFundedCapableMintIsNotBlocked() {
        val mints = listOf(
            mint("https://antifiat.example", amountless = false, balance = 103_577),
            mint("https://minibits.example", amountless = true, balance = 1),
        )

        assertNull(amountlessMeltBlock(amountless, mints))
    }

    @Test
    fun anUnfetchedMintStaysEligible() {
        val mints = listOf(mint("https://a.example", amountless = null, balance = 5))

        assertNull(amountlessMeltBlock(amountless, mints))
    }

    /** The reported bug: funds sit in a mint that can't pay, the one that can is empty. */
    @Test
    fun emptyCapableMintNamesTheTransferDestination() {
        val mints = listOf(
            mint("https://antifiat.example", name = "antifiat mint", amountless = false, balance = 103_577),
            mint("https://minibits.example", name = "Minibits mint", amountless = true, balance = 0),
        )

        assertEquals(
            AmountlessMeltBlock.CapableMintsEmpty(
                capableMintNames = listOf("Minibits mint"),
                transferDestinationUrl = "https://minibits.example",
            ),
            amountlessMeltBlock(amountless, mints),
        )
    }

    @Test
    fun noFundedSourceLeavesNothingToTransfer() {
        val mints = listOf(
            mint("https://a.example", amountless = false, balance = 0),
            mint("https://b.example", name = "B", amountless = true, balance = 0),
        )

        assertEquals(
            AmountlessMeltBlock.CapableMintsEmpty(listOf("B"), transferDestinationUrl = null),
            amountlessMeltBlock(amountless, mints),
        )
    }

    @Test
    fun aSourceThatCannotSendOverLightningCannotFundTheTransfer() {
        val mints = listOf(
            mint(
                "https://a.example",
                amountless = false,
                balance = 500,
                capability = Bolt11SatCapability(canMint = true, canMelt = false),
            ),
            mint("https://b.example", name = "B", amountless = true, balance = 0),
        )

        assertEquals(
            AmountlessMeltBlock.CapableMintsEmpty(listOf("B"), transferDestinationUrl = null),
            amountlessMeltBlock(amountless, mints),
        )
    }

    @Test
    fun theFirstCapableMintThatCanReceiveIsTheDestination() {
        val mints = listOf(
            mint(
                "https://b.example",
                name = "B",
                amountless = true,
                balance = 0,
                capability = Bolt11SatCapability(canMint = false, canMelt = true),
            ),
            mint("https://c.example", name = "C", amountless = true, balance = 0),
            mint("https://a.example", amountless = false, balance = 500),
        )

        assertEquals(
            AmountlessMeltBlock.CapableMintsEmpty(listOf("B", "C"), transferDestinationUrl = "https://c.example"),
            amountlessMeltBlock(amountless, mints),
        )
    }

    @Test
    fun presentationCopy() {
        val zero = "₿0"

        assertEquals(
            AmountlessMeltBlockPresentation(
                headline = "Can't pay this invoice.",
                detail = "None of your mints can pay invoices without an amount. Ask for one with the amount set.",
                actionTitle = null,
            ),
            AmountlessMeltBlock.NoCapableMint.presentation(zero),
        )
        assertEquals(
            AmountlessMeltBlockPresentation(
                headline = "Can't pay this invoice.",
                detail = "Only Minibits mint can pay invoices without an amount, and it holds ₿0.",
                actionTitle = "Transfer to Minibits mint",
            ),
            AmountlessMeltBlock.CapableMintsEmpty(listOf("Minibits mint"), "https://m.example").presentation(zero),
        )
        assertEquals(
            AmountlessMeltBlockPresentation(
                headline = "Can't pay this invoice.",
                detail = "Only Minibits mint can pay invoices without an amount, and it holds 0 sat. " +
                    "Ask for one with the amount set.",
                actionTitle = null,
            ),
            AmountlessMeltBlock.CapableMintsEmpty(listOf("Minibits mint"), null).presentation("0 sat"),
        )
        assertEquals(
            AmountlessMeltBlockPresentation(
                headline = "Can't pay this invoice.",
                detail = "The mints that can pay invoices without an amount are empty.",
                actionTitle = "Transfer ecash",
            ),
            AmountlessMeltBlock.CapableMintsEmpty(listOf("A", "B"), "https://a.example").presentation(zero),
        )
        assertEquals(
            AmountlessMeltBlockPresentation(
                headline = "Can't pay this invoice.",
                detail = "The mints that can pay invoices without an amount are empty. " +
                    "Ask for one with the amount set.",
                actionTitle = null,
            ),
            AmountlessMeltBlock.CapableMintsEmpty(listOf("A", "B"), null).presentation(zero),
        )
    }

    @Test
    fun meltUnavailableReasons() {
        val bolt11Only = mint("https://a.example", amountless = false, balance = 0)
        val everything = mint(
            "https://b.example",
            amountless = true,
            balance = 0,
            melt = listOf(PaymentMethodKind.Bolt11, PaymentMethodKind.Bolt12, PaymentMethodKind.Onchain),
        )
        val bolt12Only = mint(
            "https://c.example",
            amountless = null,
            balance = 0,
            melt = listOf(PaymentMethodKind.Bolt12),
        )

        assertNull(meltUnavailableReason(bolt11Only, PaymentMethodKind.Bolt11))
        assertEquals(
            "Can't pay invoices without an amount",
            meltUnavailableReason(bolt11Only, PaymentMethodKind.Bolt11, amountless = true),
        )
        assertEquals("Can't pay reusable invoices", meltUnavailableReason(bolt11Only, PaymentMethodKind.Bolt12))
        assertEquals("Can't send on-chain", meltUnavailableReason(bolt11Only, PaymentMethodKind.Onchain))
        assertEquals("Can't pay Lightning invoices", meltUnavailableReason(bolt12Only, PaymentMethodKind.Bolt11))
        PaymentMethodKind.entries.forEach { method ->
            assertNull(meltUnavailableReason(everything, method, amountless = true))
        }
    }

    private fun mint(
        url: String,
        name: String = url,
        amountless: Boolean?,
        balance: Long,
        melt: List<PaymentMethodKind> = listOf(PaymentMethodKind.Bolt11),
        capability: Bolt11SatCapability? = null,
    ) = MintInfo(
        url = url,
        name = name,
        balance = balance,
        supportedMintMethods = listOf(PaymentMethodKind.Bolt11),
        supportedMeltMethods = melt,
        supportsAmountlessBolt11Melt = amountless,
        bolt11Sat = capability,
    )
}
