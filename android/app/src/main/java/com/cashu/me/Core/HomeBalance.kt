package com.cashu.me.Core

import com.cashu.me.Core.Protocols.CurrencyRegistry

/**
 * Pure logic for the home balance unit pager (port of iOS HomeBalance).
 *
 * Held balances determine visibility independently of advertised payment units.
 */
object HomeBalance {
    /** Pager page order: sat first, then held non-sat units sorted. */
    fun homeBalanceUnits(balancesByUnit: Map<String, Long>): List<String> {
        val heldNonSat = balancesByUnit
            .filterKeys { it.lowercase() != "sat" }
            .filterValues { it > 0 }
            .keys
            .sorted()
        return listOf("sat") + heldNonSat
    }

    /** Clamp a persisted unit selection back to sat when it no longer holds balance. */
    fun resolvedUnit(unit: String, units: List<String>): String =
        if (units.contains(unit)) unit else "sat"

    fun showsUnitPager(
        balancesByUnit: Map<String, Long>,
    ): Boolean = homeBalanceUnits(balancesByUnit).size > 1
}

/**
 * What the home balance shows: its own unit (bitcoin on the sat page), the
 * fiat conversion, or nothing at all (private mode).
 */
enum class HomeBalanceStep { Unit, Fiat, Hidden }

/** The two stored settings a [HomeBalanceStep] lands on. */
data class HomeBalanceSettings(val primary: AmountDisplayPrimary, val hidden: Boolean)

/**
 * The home balance tap cycle (port of iOS HomeBalanceCycle): bitcoin → fiat →
 * private → bitcoin on the sat page, skipping fiat when there is no fiat line,
 * and unit → private → unit on every other page.
 *
 * Private is its own setting rather than a third [AmountDisplayPrimary]: that
 * enum also drives payment entry, and [AmountDisplayPrimary.fromRaw] rewrites
 * any value it doesn't know to fiat.
 */
object HomeBalanceCycle {
    const val HiddenLabel = "Balance hidden"

    /**
     * Derived from the *effective* primary, not the stored preference, so a
     * price that disappears while fiat is primary falls back to the unit and
     * the next tap goes to private.
     */
    fun current(
        isHidden: Boolean,
        effectivePrimary: AmountDisplayPrimary,
        isSatPage: Boolean,
    ): HomeBalanceStep = when {
        isHidden -> HomeBalanceStep.Hidden
        isSatPage && effectivePrimary == AmountDisplayPrimary.Fiat -> HomeBalanceStep.Fiat
        else -> HomeBalanceStep.Unit
    }

    fun next(after: HomeBalanceStep, hasFiat: Boolean, isSatPage: Boolean): HomeBalanceStep = when (after) {
        HomeBalanceStep.Unit -> if (isSatPage && hasFiat) HomeBalanceStep.Fiat else HomeBalanceStep.Hidden
        HomeBalanceStep.Fiat -> HomeBalanceStep.Hidden
        HomeBalanceStep.Hidden -> HomeBalanceStep.Unit
    }

    /** Whether a sat-page display has a fiat side to cycle through. */
    fun hasFiat(display: AmountDisplayText): Boolean =
        display.effectivePrimary == AmountDisplayPrimary.Fiat || display.secondary != null

    /**
     * Entering private keeps the stored primary untouched; leaving it always
     * lands on bitcoin, from any page.
     */
    fun settings(step: HomeBalanceStep, keeping: AmountDisplayPrimary): HomeBalanceSettings = when (step) {
        HomeBalanceStep.Unit -> HomeBalanceSettings(AmountDisplayPrimary.Sats, hidden = false)
        HomeBalanceStep.Fiat -> HomeBalanceSettings(AmountDisplayPrimary.Fiat, hidden = false)
        HomeBalanceStep.Hidden -> HomeBalanceSettings(keeping, hidden = true)
    }

    /**
     * What a tap does next, in words, for TalkBack ("hide balance", "make
     * $0.50 primary", "show balance in sats"). Lowercase, as on iOS; callers
     * capitalize it for a click label.
     */
    fun actionPhrase(next: HomeBalanceStep, fiatText: String?, unitName: String): String = when (next) {
        HomeBalanceStep.Hidden -> "hide balance"
        HomeBalanceStep.Fiat -> "make ${fiatText.orEmpty()} primary"
        HomeBalanceStep.Unit -> "show balance in $unitName"
    }

    /** The unit a page's "show balance in …" phrase names. */
    fun unitName(unit: String): String =
        if (CurrencyRegistry.isSatoshiUnit(unit)) "sats" else CurrencyRegistry.currencyForMintUnit(unit).code
}
