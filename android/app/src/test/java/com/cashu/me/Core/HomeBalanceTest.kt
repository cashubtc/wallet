package com.cashu.me.Core

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
    fun pagerShowsHeldCurrenciesIndependentlyOfMintAdvertisements() {
        val held = mapOf("sat" to 100L, "eur" to 200L)
        assertTrue(HomeBalance.showsUnitPager(balancesByUnit = held))
        // No held non-sat balance means there is only one page.
        assertFalse(
            HomeBalance.showsUnitPager(
                balancesByUnit = mapOf("sat" to 100L, "eur" to 0L),
            ),
        )
    }

    // Home balance tap cycle (iOS HomeBalanceCycle parity).

    @Test
    fun satPageWithFiatCyclesBitcoinFiatPrivate() {
        assertEquals(HomeBalanceStep.Fiat, HomeBalanceCycle.next(HomeBalanceStep.Unit, hasFiat = true, isSatPage = true))
        assertEquals(HomeBalanceStep.Hidden, HomeBalanceCycle.next(HomeBalanceStep.Fiat, hasFiat = true, isSatPage = true))
        assertEquals(HomeBalanceStep.Unit, HomeBalanceCycle.next(HomeBalanceStep.Hidden, hasFiat = true, isSatPage = true))
    }

    @Test
    fun withoutFiatTheCycleIsBitcoinAndPrivate() {
        assertEquals(HomeBalanceStep.Hidden, HomeBalanceCycle.next(HomeBalanceStep.Unit, hasFiat = false, isSatPage = true))
        assertEquals(HomeBalanceStep.Unit, HomeBalanceCycle.next(HomeBalanceStep.Hidden, hasFiat = false, isSatPage = true))
    }

    @Test
    fun otherUnitPagesNeverOfferFiat() {
        assertEquals(HomeBalanceStep.Hidden, HomeBalanceCycle.next(HomeBalanceStep.Unit, hasFiat = true, isSatPage = false))
        assertEquals(HomeBalanceStep.Unit, HomeBalanceCycle.next(HomeBalanceStep.Hidden, hasFiat = true, isSatPage = false))
    }

    @Test
    fun currentStepFollowsWhatIsShownNotWhatIsStored() {
        // Hidden wins over any primary.
        assertEquals(
            HomeBalanceStep.Hidden,
            HomeBalanceCycle.current(isHidden = true, effectivePrimary = AmountDisplayPrimary.Fiat, isSatPage = true),
        )
        assertEquals(
            HomeBalanceStep.Fiat,
            HomeBalanceCycle.current(isHidden = false, effectivePrimary = AmountDisplayPrimary.Fiat, isSatPage = true),
        )
        // Stored fiat with no price: displayText falls back to sats, so the
        // step is the unit and the next tap goes to private.
        val noPrice = AmountFormatter(java.util.Locale.US).displayText(
            amountSats = 500,
            preferredPrimary = AmountDisplayPrimary.Fiat.rawValue,
            showFiat = true,
            btcPrice = null,
            currencyCode = "USD",
            useBitcoinSymbol = false,
        )
        val current = HomeBalanceCycle.current(isHidden = false, effectivePrimary = noPrice.effectivePrimary, isSatPage = true)
        assertEquals(HomeBalanceStep.Unit, current)
        assertFalse(HomeBalanceCycle.hasFiat(noPrice))
        assertEquals(HomeBalanceStep.Hidden, HomeBalanceCycle.next(current, HomeBalanceCycle.hasFiat(noPrice), isSatPage = true))
        // Another unit page is never "fiat", whatever the sat page prefers.
        assertEquals(
            HomeBalanceStep.Unit,
            HomeBalanceCycle.current(isHidden = false, effectivePrimary = AmountDisplayPrimary.Fiat, isSatPage = false),
        )
    }

    @Test
    fun leavingPrivateLandsOnBitcoinAndEnteringKeepsThePrimary() {
        assertEquals(
            HomeBalanceSettings(AmountDisplayPrimary.Sats, hidden = false),
            HomeBalanceCycle.settings(HomeBalanceStep.Unit, keeping = AmountDisplayPrimary.Fiat),
        )
        assertEquals(
            HomeBalanceSettings(AmountDisplayPrimary.Fiat, hidden = false),
            HomeBalanceCycle.settings(HomeBalanceStep.Fiat, keeping = AmountDisplayPrimary.Sats),
        )
        assertEquals(
            HomeBalanceSettings(AmountDisplayPrimary.Fiat, hidden = true),
            HomeBalanceCycle.settings(HomeBalanceStep.Hidden, keeping = AmountDisplayPrimary.Fiat),
        )
    }

    @Test
    fun actionPhrasesNameTheNextState() {
        assertEquals("hide balance", HomeBalanceCycle.actionPhrase(HomeBalanceStep.Hidden, fiatText = null, unitName = "sats"))
        assertEquals("make \$0.50 primary", HomeBalanceCycle.actionPhrase(HomeBalanceStep.Fiat, fiatText = "\$0.50", unitName = "sats"))
        assertEquals("show balance in sats", HomeBalanceCycle.actionPhrase(HomeBalanceStep.Unit, fiatText = null, unitName = HomeBalanceCycle.unitName("sat")))
        assertEquals("show balance in USD", HomeBalanceCycle.actionPhrase(HomeBalanceStep.Unit, fiatText = null, unitName = HomeBalanceCycle.unitName("usd")))
        assertEquals("show balance in FOO", HomeBalanceCycle.actionPhrase(HomeBalanceStep.Unit, fiatText = null, unitName = HomeBalanceCycle.unitName("foo")))
    }
}
