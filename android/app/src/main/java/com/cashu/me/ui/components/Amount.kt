package com.cashu.me.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import com.cashu.me.ui.theme.withMonoDigits

/**
 * Monospaced-digit amount text. Use everywhere balances, amounts, and fees
 * appear. Cross-fades on change — the same quiet, no-slide transition the
 * app's other amount swaps already use (see [AmountFlipDisplay], [BalanceDisplay]).
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
    AnimatedContent(
        targetState = text,
        transitionSpec = {
            fadeIn(spring(stiffness = Spring.StiffnessMedium))
                .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
        },
        modifier = modifier,
        label = "amount-text",
    ) { targetText ->
        Text(text = targetText, style = finalStyle)
    }
}
