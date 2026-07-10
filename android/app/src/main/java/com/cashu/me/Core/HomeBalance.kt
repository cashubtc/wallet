package com.cashu.me.Core

/**
 * Pure logic for the home balance unit pager (port of iOS HomeBalance).
 *
 * The pager appears only when the active (default) mint advertises multiple
 * units AND a non-sat balance is actually held; a sat-only default mint renders
 * the single hero even if a non-sat balance exists at another mint (that
 * balance still shows on Send + Mint Detail).
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
        activeMintSupportsMultipleUnits: Boolean,
        balancesByUnit: Map<String, Long>,
    ): Boolean = activeMintSupportsMultipleUnits && homeBalanceUnits(balancesByUnit).size > 1
}
