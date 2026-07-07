package org.cashu.wallet.Core

/**
 * Unit-native amount entry (port of the iOS AmountFormatter entry helpers).
 *
 * For currencies with decimals > 0 the keypad acts as a minor-unit accumulator:
 * each digit shifts left ("5" → "0.05", "50" → "0.50", "500" → "5.00"), so the
 * raw string always shows exactly [decimals] fraction digits. For 0-decimal
 * units the raw string is the plain integer entry used today.
 */
object UnitAmountEntry {
    // Matches the iOS entry cap: minor units stay within 11 digits.
    private const val MAX_MINOR_UNITS = 99_999_999_999L

    /** Parse the raw entry string into base (minor) units. "5.00" @2 → 500. */
    fun baseUnits(raw: String, decimals: Int): Long {
        if (raw.isBlank()) return 0
        if (decimals <= 0) return raw.filter(Char::isDigit).toLongOrNull() ?: 0
        val digits = raw.filter(Char::isDigit).trimStart('0')
        if (digits.isEmpty()) return 0
        return digits.toLongOrNull()?.coerceAtMost(MAX_MINOR_UNITS) ?: MAX_MINOR_UNITS
    }

    /** Append one keypad digit, returning the new raw entry string. */
    fun append(key: String, raw: String, decimals: Int): String {
        val digit = key.singleOrNull()?.takeIf(Char::isDigit) ?: return raw
        if (decimals <= 0) {
            return if (raw == "0" || raw.isEmpty()) digit.toString() else raw + digit
        }
        val minor = baseUnits(raw, decimals)
        if (minor > MAX_MINOR_UNITS / 10) return raw
        return entryString(minor * 10 + (digit - '0'), decimals)
    }

    /** Remove the last-entered digit (shift right for decimal units). */
    fun backspace(raw: String, decimals: Int): String {
        if (decimals <= 0) return raw.dropLast(1)
        val minor = baseUnits(raw, decimals) / 10
        return if (minor == 0L) "" else entryString(minor, decimals)
    }

    /** Render base units as the raw entry string. 500 @2 → "5.00"; 0 → "". */
    fun entryString(baseUnits: Long, decimals: Int): String {
        if (baseUnits <= 0) return ""
        if (decimals <= 0) return baseUnits.toString()
        val digits = baseUnits.toString().padStart(decimals + 1, '0')
        val whole = digits.dropLast(decimals)
        val fraction = digits.takeLast(decimals)
        return "$whole.$fraction"
    }
}
