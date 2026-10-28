package com.cashu.me.ui.components

import com.cashu.me.Core.AmountDisplayPrimary
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.Core.displayText
import com.cashu.me.ui.components.DigitRollDirection.Down
import com.cashu.me.ui.components.DigitRollDirection.Up
import com.cashu.me.ui.components.DigitRollPiece.Enter
import com.cashu.me.ui.components.DigitRollPiece.Exit
import com.cashu.me.ui.components.DigitRollPiece.Hold
import com.cashu.me.ui.components.DigitRollPiece.Roll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

class DigitRollPlanTest {
    @Test
    fun sameLengthRollsOnlyTheDigitsThatChanged() {
        val plan = digitRollPlan("12,345 sat", "12,350 sat")

        assertEquals(
            DigitRollPlan(
                Up,
                listOf(Hold(0..3, 0..3), Roll(4, 4), Roll(5, 5), Hold(6..9, 6..9)),
            ),
            plan,
        )
    }

    @Test
    fun growingAmountAlignsFromTheUnitsDigitAndFadesInTheNewLeadingDigit() {
        val plan = digitRollPlan("9,990", "10,010")

        val anchor = DigitRollAnchor(from = 0, to = 1)
        assertEquals(
            DigitRollPlan(
                Up,
                listOf(
                    Enter(0, anchor),
                    Roll(0, 1),
                    Hold(1..1, 2..2),
                    Roll(2, 3),
                    Roll(3, 4),
                    Hold(4..4, 5..5),
                ),
            ),
            plan,
        )
    }

    @Test
    fun shrinkingAmountFadesOutTheLostLeadingGlyphsAndRollsDown() {
        val plan = digitRollPlan("1,000 sat", "999 sat")

        val anchor = DigitRollAnchor(from = 2, to = 0)
        assertEquals(
            DigitRollPlan(
                Down,
                listOf(
                    Exit(0, anchor),
                    Exit(1, anchor),
                    Roll(2, 0),
                    Roll(3, 1),
                    Roll(4, 2),
                    Hold(5..8, 3..6),
                ),
            ),
            plan,
        )
    }

    @Test
    fun aNewGroupingSeparatorEntersWithItsDigit() {
        val plan = notNull(digitRollPlan("999,999", "1,000,000"))

        assertEquals(Up, plan.direction)
        val anchor = DigitRollAnchor(from = 0, to = 2)
        assertEquals(listOf(Enter(0, anchor), Enter(1, anchor)), plan.pieces.take(2))
        // The comma that was already there holds at its place value.
        assertEquals(Hold(3..3, 5..5), plan.pieces[5])
    }

    @Test
    fun theUnitRunHoldsAndMustMatch() {
        val plan = notNull(digitRollPlan("2,100 sat", "2,600 sat"))
        // The held trailing zeros and the unit draw as one stretch.
        assertEquals(Hold(3..8, 3..8), plan.pieces.last())

        assertNull(digitRollPlan("2,100 sat", "2,100 eur"))
        assertNull(digitRollPlan("2,100 sat", "2,600 sats"))
    }

    @Test
    fun fiatDecimalsRollWithTheSymbolHeld() {
        assertEquals(
            DigitRollPlan(Up, listOf(Hold(0..2, 0..2), Roll(3, 3), Roll(4, 4))),
            digitRollPlan("$1.34", "$1.40"),
        )

        val growing = notNull(digitRollPlan("€9.99", "€10.01"))
        assertEquals(Up, growing.direction)
        assertEquals(Hold(0..0, 0..0), growing.pieces.first())
        assertEquals(Enter(1, DigitRollAnchor(from = 1, to = 2)), growing.pieces[1])

        assertEquals(Down, notNull(digitRollPlan("$12.50", "$9.99")).direction)
        assertEquals(Up, notNull(digitRollPlan("$0.99", "$1.01")).direction)
        assertEquals(Up, notNull(digitRollPlan("₿0.00002500", "₿0.00003000")).direction)
    }

    @Test
    fun aChangedDecimalStructureIsANewFormatNotANewValue() {
        assertNull(digitRollPlan("0.5", "0.25"))
        assertNull(digitRollPlan("1000", "1,000"))
    }

    @Test
    fun satsFiatToggleCrossFadesInsteadOfRolling() {
        val formatter = AmountFormatter(Locale.US)
        fun display(primary: AmountDisplayPrimary) = formatter.displayText(
            amountSats = 2_500L,
            preferredPrimary = primary.rawValue,
            showFiat = true,
            btcPrice = 50_000.0,
            currencyCode = "USD",
            useBitcoinSymbol = false,
        )
        val sats = display(AmountDisplayPrimary.Sats)
        val fiat = display(AmountDisplayPrimary.Fiat)

        assertNull(digitRollPlan(sats.primary, fiat.primary))
        assertNull(digitRollPlan(fiat.primary, sats.primary))
        assertNull(digitRollPlan(notNull(sats.secondary), notNull(fiat.secondary)))
    }

    @Test
    fun nothingToRollWithoutDigitsOrWithoutAChange() {
        assertNull(digitRollPlan("****", "1 sat"))
        assertNull(digitRollPlan("2,100 sat", "2,100 sat"))
    }

    private fun <T : Any> notNull(value: T?): T = checkNotNull(value) { "expected a value" }
}
