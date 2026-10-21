package com.cashu.me.Core

import com.cashu.me.Core.MintTransferEligibility.Blocker
import com.cashu.me.Models.Bolt11SatCapability
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.PaymentMethodKind
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iOS parity: `MintTransferEligibilityTests.swift`. */
class MintTransferEligibilityTest {

    /**
     * Records written before the capability existed must read as "unknown",
     * not as a mint that supports nothing.
     */
    @Test
    fun recordWithoutCapabilityDecodesAsUnknown() {
        val stored = """{"url":"https://mint.example","name":"Mint","isActive":true,"balance":21}"""
        val mint = Json.decodeFromString(MintInfo.serializer(), stored)

        assertNull(mint.bolt11Sat)
        assertTrue(MintTransferEligibility.canSend(mint))
        assertTrue(MintTransferEligibility.canReceive(mint))
    }

    @Test
    fun capabilitySurvivesPersistence() {
        val mint = mint(
            "https://mint.example",
            capability = Bolt11SatCapability(canMint = true, canMelt = false, mintMin = 1, mintMax = 500_000),
        )

        val decoded = Json.decodeFromString(
            MintInfo.serializer(),
            Json.encodeToString(MintInfo.serializer(), mint),
        )

        assertEquals(mint.bolt11Sat, decoded.bolt11Sat)
    }

    @Test
    fun twoCapableMintsAreEligible() {
        val source = mint("https://a.example", capability = capable())
        val destination = mint("https://b.example", capability = capable())

        assertNull(MintTransferEligibility.blocker(source, destination))
        assertTrue(MintTransferEligibility.eligible(source, destination))
    }

    @Test
    fun equivalentUrlsAreTheSameMint() {
        val source = mint("https://Mint.Example:443/", capability = capable())
        val destination = mint("https://mint.example", capability = capable())

        assertEquals(Blocker.SameMint, MintTransferEligibility.blocker(source, destination))
    }

    @Test
    fun sourceThatCannotMeltBlocksOnlyThatDirection() {
        val receiveOnly = mint("https://a.example", capability = capable(canMelt = false))
        val other = mint("https://b.example", capability = capable())

        assertEquals(Blocker.SourceCannotSend, MintTransferEligibility.blocker(receiveOnly, other))
        assertNull(MintTransferEligibility.blocker(other, receiveOnly))
    }

    @Test
    fun destinationThatCannotMintBlocksOnlyThatDirection() {
        val sendOnly = mint("https://a.example", capability = capable(canMint = false))
        val other = mint("https://b.example", capability = capable())

        assertEquals(Blocker.DestinationCannotReceive, MintTransferEligibility.blocker(other, sendOnly))
        assertNull(MintTransferEligibility.blocker(sendOnly, other))
    }

    /**
     * An empty source is a valid selection — it has nothing to send, which the
     * amount check reports, but the pair itself is not blocked.
     */
    @Test
    fun emptySourceIsNotABlocker() {
        val source = mint("https://a.example", balance = 0, capability = capable())
        val destination = mint("https://b.example", capability = capable())

        assertNull(MintTransferEligibility.blocker(source, destination))
    }

    @Test
    fun unfetchedMintFallsBackToItsMethodLists() {
        val meltless = mint("https://a.example").copy(supportedMeltMethods = listOf(PaymentMethodKind.Bolt12))
        val nonSat = mint("https://b.example").copy(mintUnits = listOf("usd"))
        val noBolt11Mint = mint("https://c.example").copy(supportedMintMethods = listOf(PaymentMethodKind.Onchain))

        assertFalse(MintTransferEligibility.canSend(meltless))
        assertFalse(MintTransferEligibility.canReceive(nonSat))
        assertFalse(MintTransferEligibility.canReceive(noBolt11Mint))
    }

    /** Once fetched, the capability wins over the coarser method lists. */
    @Test
    fun fetchedCapabilityOverridesMethodLists() {
        val paused = mint("https://a.example", capability = capable(canMint = false, canMelt = false)).copy(
            supportedMintMethods = listOf(PaymentMethodKind.Bolt11),
            supportedMeltMethods = listOf(PaymentMethodKind.Bolt11),
        )

        assertFalse(MintTransferEligibility.canSend(paused))
        assertFalse(MintTransferEligibility.canReceive(paused))
    }

    @Test
    fun amountRangeIntersectsDestinationMintAndSourceMeltLimits() {
        val source = mint("https://a.example", capability = capable(meltMin = 10, meltMax = 250_000))
        val destination = mint("https://b.example", capability = capable(mintMin = 100, mintMax = 500_000))

        assertEquals(100L..250_000L, MintTransferEligibility.amountRange(source, destination))
    }

    /**
     * The other mint's limits are irrelevant: a source's mint limits and a
     * destination's melt limits do not constrain this direction.
     */
    @Test
    fun amountRangeIgnoresTheOppositeDirectionsLimits() {
        val source = mint("https://a.example", capability = capable(mintMin = 5_000, mintMax = 6_000))
        val destination = mint("https://b.example", capability = capable(meltMin = 7_000, meltMax = 8_000))

        assertEquals(1L..Long.MAX_VALUE, MintTransferEligibility.amountRange(source, destination))
    }

    @Test
    fun amountRangeIsOpenWhenLimitsAreUnknown() {
        assertEquals(
            1L..Long.MAX_VALUE,
            MintTransferEligibility.amountRange(mint("https://a.example"), mint("https://b.example")),
        )
    }

    @Test
    fun amountRangeIsNullWhenLimitsDoNotOverlap() {
        val source = mint("https://a.example", capability = capable(meltMax = 50))
        val destination = mint("https://b.example", capability = capable(mintMin = 100))

        assertNull(MintTransferEligibility.amountRange(source, destination))
    }

    private fun mint(
        url: String,
        balance: Long = 1_000,
        capability: Bolt11SatCapability? = null,
    ) = MintInfo(url = url, name = "Mint", balance = balance, bolt11Sat = capability)

    private fun capable(
        canMint: Boolean = true,
        canMelt: Boolean = true,
        mintMin: Long? = null,
        mintMax: Long? = null,
        meltMin: Long? = null,
        meltMax: Long? = null,
    ) = Bolt11SatCapability(canMint, canMelt, mintMin, mintMax, meltMin, meltMax)
}
