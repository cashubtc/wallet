package com.cashu.me.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.ui.theme.withMonoDigits

/**
 * The shared hero number for every live amount-entry screen (Send Ecash,
 * Receive Lightning, Unified Send).
 *
 * Mirrors iOS `CurrencyAmountDisplay` entry mode: one bold number with the unit
 * baked *inline* (`₿1,234` / `1,234 sat`) — no separate unit caption. The
 * **SemiBold** weight is a deliberate carve-out from stock M3 `displayMedium`
 * (W400) for cross-platform brand parity; kept at the `displayMedium` (45sp)
 * size so a long amount stays on one line. See DESIGN-ANDROID.md §1.
 *
 * @param entryRaw the raw typed amount ("" before the first keypress)
 * @param isSat    true for a sat wallet; false routes through the unit code
 * @param unit     effective unit code for non-sat mints (e.g. "USD")
 * @param decimals fractional places for the empty-state placeholder
 * @param color    dims to `onSurfaceVariant` on insufficient balance (Send Ecash)
 */
@Composable
fun AmountEntryHero(
    entryRaw: String,
    isSat: Boolean,
    unit: String,
    decimals: Int,
    useBitcoinSymbol: Boolean,
    formatter: AmountFormatter,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val raw = when {
        entryRaw.isNotEmpty() -> entryRaw
        decimals > 0 -> "0." + "0".repeat(decimals)
        else -> "0"
    }
    AmountText(
        text = formatter.entryDisplay(raw, isSat, unit, useBitcoinSymbol),
        style = MaterialTheme.typography.displayMedium
            .copy(fontWeight = FontWeight.SemiBold)
            .withMonoDigits(),
        color = color,
    )
}
