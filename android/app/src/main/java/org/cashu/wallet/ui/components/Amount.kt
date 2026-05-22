package org.cashu.wallet.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import org.cashu.wallet.ui.theme.withMonoDigits

/**
 * Monospaced-digit amount text. Use everywhere balances, amounts, and fees appear.
 * Animates digit positions independently (Tabular Figure Rule).
 */
@Composable
fun AmountText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = Color.Unspecified,
    animated: Boolean = true,
) {
    val resolvedColor = if (color == Color.Unspecified) LocalContentColor.current else color
    val finalStyle = style.withMonoDigits().copy(color = resolvedColor)
    if (!animated) {
        Text(text = text, style = finalStyle, modifier = modifier)
        return
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        text.forEachIndexed { index, ch ->
            AnimatedContent(
                targetState = ch,
                transitionSpec = {
                    if (targetState.isDigit() && initialState.isDigit()) {
                        val goingUp = targetState.digitToIntOrNull()?.let { t ->
                            initialState.digitToIntOrNull()?.let { i -> t > i }
                        } ?: true
                        val from = if (goingUp) -1 else 1
                        val to = if (goingUp) 1 else -1
                        (slideInVertically(tween(220)) { it * from } + fadeIn(tween(220)))
                            .togetherWith(slideOutVertically(tween(220)) { it * to } + fadeOut(tween(220)))
                            .using(SizeTransform(clip = false))
                    } else {
                        fadeIn(tween(120)).togetherWith(fadeOut(tween(120)))
                    }
                },
                label = "amount-digit-$index",
            ) { char ->
                Text(text = char.toString(), style = finalStyle)
            }
        }
    }
}
