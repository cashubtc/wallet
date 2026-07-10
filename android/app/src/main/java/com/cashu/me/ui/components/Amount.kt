package com.cashu.me.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.cashu.me.ui.theme.withMonoDigits

/** Composition-scoped holder tracking whether the amount is going up or down. */
private class TickerDirection(var lastValue: Double?) {
    var goingUp: Boolean = true
}

/**
 * Monospaced-digit amount text. Use everywhere balances, amounts, and fees
 * appear. Animates digit positions independently (Tabular Figure Rule) — the
 * Compose equivalent of iOS `.contentTransition(.numericText(value:))`.
 *
 * Slot identity is anchored to the position from the *right* end of the
 * string, so a length change (`999 → 1,000`, a separator shifting) only
 * animates the digits that actually changed and inserts new leading slots,
 * instead of re-animating every character to the right of the change.
 *
 * @param value optional numeric value behind [text]. When provided, all digit
 *   rolls share one odometer direction (up when the value increases), matching
 *   `.numericText(value:)`. Without it, direction falls back to per-digit
 *   comparison.
 */
@Composable
fun AmountText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    animated: Boolean = true,
    value: Double? = null,
) {
    val resolvedColor = if (color == Color.Unspecified) LocalContentColor.current else color
    val finalStyle = style.withMonoDigits().copy(color = resolvedColor)
    if (!animated) {
        Text(text = text, style = finalStyle, modifier = modifier)
        return
    }
    val direction = remember { TickerDirection(value) }
    if (value != null) {
        val last = direction.lastValue
        if (last != null && value != last) direction.goingUp = value > last
        direction.lastValue = value
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        text.forEachIndexed { index, ch ->
            // Identity from the right: the units digit is always slot 1, tens
            // slot 2, … so leading insertions don't cascade re-animations.
            val slot = text.length - index
            key(slot) {
                AnimatedContent(
                    targetState = ch,
                    transitionSpec = {
                        if (targetState.isDigit() && initialState.isDigit()) {
                            val goingUp = if (value != null) {
                                direction.goingUp
                            } else {
                                targetState.digitToIntOrNull()?.let { t ->
                                    initialState.digitToIntOrNull()?.let { i -> t > i }
                                } ?: true
                            }
                            val from = if (goingUp) -1 else 1
                            val to = if (goingUp) 1 else -1
                            (slideInVertically(tween(220)) { it * from } + fadeIn(tween(220)))
                                .togetherWith(
                                    slideOutVertically(tween(220)) { it * to } + fadeOut(tween(220)),
                                )
                                .using(SizeTransform(clip = false))
                        } else {
                            fadeIn(tween(120)).togetherWith(fadeOut(tween(120)))
                        }
                    },
                    label = "amount-digit",
                ) { char ->
                    Text(text = char.toString(), style = finalStyle)
                }
            }
        }
    }
}
