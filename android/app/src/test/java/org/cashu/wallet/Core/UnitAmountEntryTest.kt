package org.cashu.wallet.Core

import org.junit.Assert.assertEquals
import org.junit.Test

class UnitAmountEntryTest {
    @Test
    fun zeroDecimalEntryMatchesPlainIntegerBehavior() {
        assertEquals("5", UnitAmountEntry.append("5", "", 0))
        assertEquals("5", UnitAmountEntry.append("5", "0", 0))
        assertEquals("50", UnitAmountEntry.append("0", "5", 0))
        assertEquals("5", UnitAmountEntry.backspace("50", 0))
        assertEquals(500L, UnitAmountEntry.baseUnits("500", 0))
    }

    @Test
    fun decimalEntryAccumulatesMinorUnits() {
        var raw = ""
        raw = UnitAmountEntry.append("5", raw, 2)
        assertEquals("0.05", raw)
        raw = UnitAmountEntry.append("0", raw, 2)
        assertEquals("0.50", raw)
        raw = UnitAmountEntry.append("0", raw, 2)
        assertEquals("5.00", raw)
        assertEquals(500L, UnitAmountEntry.baseUnits(raw, 2))
    }

    @Test
    fun decimalBackspaceShiftsRightAndCollapsesToEmpty() {
        assertEquals("0.50", UnitAmountEntry.backspace("5.00", 2))
        assertEquals("0.05", UnitAmountEntry.backspace("0.50", 2))
        assertEquals("", UnitAmountEntry.backspace("0.05", 2))
    }

    @Test
    fun entryStringRoundTrips() {
        assertEquals("", UnitAmountEntry.entryString(0, 2))
        assertEquals("0.09", UnitAmountEntry.entryString(9, 2))
        assertEquals("12.34", UnitAmountEntry.entryString(1_234, 2))
        assertEquals("1234", UnitAmountEntry.entryString(1_234, 0))
        assertEquals(1_234L, UnitAmountEntry.baseUnits("12.34", 2))
    }

    @Test
    fun appendIgnoresOverflowBeyondCap() {
        val maxed = UnitAmountEntry.entryString(99_999_999_999L, 2)
        assertEquals(maxed, UnitAmountEntry.append("9", maxed, 2))
    }

    @Test
    fun nonDigitKeysAreIgnored() {
        assertEquals("5.00", UnitAmountEntry.append(".", "5.00", 2))
        assertEquals("500", UnitAmountEntry.append("x", "500", 0))
    }
}
