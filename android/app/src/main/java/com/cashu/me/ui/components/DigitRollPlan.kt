package com.cashu.me.ui.components

/**
 * Which way changed digits travel: up as the amount grows, down as it shrinks —
 * the direction iOS `.numericText(value:)` reads from the value.
 */
internal enum class DigitRollDirection { Up, Down }

/** A glyph present before and after, whose slide an [DigitRollPiece.Enter] or [DigitRollPiece.Exit] glyph follows. */
internal data class DigitRollAnchor(val from: Int, val to: Int)

/**
 * One drawable stretch of an amount during a digit roll, named by its character
 * offsets in the outgoing (`from`) and incoming (`to`) strings.
 */
internal sealed interface DigitRollPiece {
    /** Unchanged glyphs (separators, the unit, digits that held) that slide with their slot. */
    data class Hold(val from: IntRange, val to: IntRange) : DigitRollPiece

    /** A digit that changed in place: the old one rolls out as the new one rolls in. */
    data class Roll(val from: Int, val to: Int) : DigitRollPiece

    /** A leading glyph the amount grew by; fades in beside [anchor]. */
    data class Enter(val to: Int, val anchor: DigitRollAnchor) : DigitRollPiece

    /** A leading glyph the amount lost; fades out beside [anchor]. */
    data class Exit(val from: Int, val anchor: DigitRollAnchor) : DigitRollPiece
}

internal data class DigitRollPlan(
    val direction: DigitRollDirection,
    val pieces: List<DigitRollPiece>,
)

/**
 * Plans the roll from one formatted amount to the next, or returns null when the
 * change is not a value change within one format — a sats ↔ fiat toggle, a
 * different unit, a different number of decimals, a hidden amount — which the
 * caller cross-fades instead.
 *
 * Old and new are aligned from the right (the units digit), so a growing amount
 * gains glyphs on the left and every digit keeps its place value: 9,990 → 10,010
 * rolls three digits, holds the comma and the last 0, and fades a 1 in. The
 * text around the number (a `$` or `₿` prefix, the ` sat` run) must match
 * exactly and always holds.
 */
internal fun digitRollPlan(from: String, to: String): DigitRollPlan? {
    if (from == to) return null
    val old = AmountFrame.of(from) ?: return null
    val new = AmountFrame.of(to) ?: return null
    if (old.prefix != new.prefix || old.suffix != new.suffix) return null

    val shared = minOf(old.bodyLength, new.bodyLength)
    for (k in 0 until shared) {
        val o = from[old.bodyEnd - 1 - k]
        val n = to[new.bodyEnd - 1 - k]
        // A separator must sit at the same place value on both sides, or the
        // grouping or decimals changed: that is a new format, not a new value.
        if (o.isDigit() != n.isDigit()) return null
        if (!o.isDigit() && o != n) return null
    }

    // The leftmost glyph both sides share; leading glyphs that come and go
    // slide with it.
    val anchor = DigitRollAnchor(from = old.bodyEnd - shared, to = new.bodyEnd - shared)
    val pieces = mutableListOf<DigitRollPiece>()
    if (old.prefix.isNotEmpty()) {
        pieces += DigitRollPiece.Hold(0 until old.prefix.length, 0 until new.prefix.length)
    }
    for (k in maxOf(old.bodyLength, new.bodyLength) - 1 downTo shared) {
        pieces += if (new.bodyLength > old.bodyLength) {
            DigitRollPiece.Enter(to = new.bodyEnd - 1 - k, anchor = anchor)
        } else {
            DigitRollPiece.Exit(from = old.bodyEnd - 1 - k, anchor = anchor)
        }
    }
    for (k in shared - 1 downTo 0) {
        val oldIndex = old.bodyEnd - 1 - k
        val newIndex = new.bodyEnd - 1 - k
        pieces += if (from[oldIndex] == to[newIndex]) {
            DigitRollPiece.Hold(oldIndex..oldIndex, newIndex..newIndex)
        } else {
            DigitRollPiece.Roll(from = oldIndex, to = newIndex)
        }
    }
    if (old.suffix.isNotEmpty()) {
        pieces += DigitRollPiece.Hold(old.bodyEnd until from.length, new.bodyEnd until to.length)
    }

    val direction = if (compareMagnitude(old.digits(from), new.digits(to)) <= 0) {
        DigitRollDirection.Up
    } else {
        DigitRollDirection.Down
    }
    return DigitRollPlan(direction, pieces.mergeHolds())
}

/** Text before the first digit, the number itself, and text after the last digit. */
private class AmountFrame(val prefix: String, val suffix: String, val bodyEnd: Int) {
    val bodyLength: Int get() = bodyEnd - prefix.length

    fun digits(text: String): String =
        text.substring(prefix.length, bodyEnd).filter(Char::isDigit).trimStart('0')

    companion object {
        fun of(text: String): AmountFrame? {
            val first = text.indexOfFirst(Char::isDigit)
            if (first < 0) return null
            val last = text.indexOfLast(Char::isDigit)
            return AmountFrame(
                prefix = text.substring(0, first),
                suffix = text.substring(last + 1),
                bodyEnd = last + 1,
            )
        }
    }
}

/**
 * Both sides share their decimal places (the frame check guarantees it), so the
 * digit strings compare as integers: the longer is larger, and equal lengths
 * compare digit by digit.
 */
private fun compareMagnitude(a: String, b: String): Int =
    if (a.length != b.length) a.length.compareTo(b.length) else a.compareTo(b)

/** Adjacent held glyphs draw as one stretch, so a frame clips and paints fewer times. */
private fun List<DigitRollPiece>.mergeHolds(): List<DigitRollPiece> {
    val merged = mutableListOf<DigitRollPiece>()
    for (piece in this) {
        val previous = merged.lastOrNull()
        if (piece is DigitRollPiece.Hold && previous is DigitRollPiece.Hold &&
            piece.from.first == previous.from.last + 1 &&
            piece.to.first == previous.to.last + 1
        ) {
            merged[merged.lastIndex] = DigitRollPiece.Hold(
                previous.from.first..piece.from.last,
                previous.to.first..piece.to.last,
            )
        } else {
            merged += piece
        }
    }
    return merged
}
