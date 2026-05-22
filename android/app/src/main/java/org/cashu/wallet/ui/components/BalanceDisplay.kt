package org.cashu.wallet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import org.cashu.wallet.Core.AmountDisplayPrimary
import org.cashu.wallet.Core.AmountDisplayText
import org.cashu.wallet.ui.theme.CashuTheme

/**
 * Large hero balance with optional secondary line. Tap to toggle the primary unit.
 * Numbers animate digit-by-digit via [AmountText].
 */
@Composable
fun BalanceDisplay(
    amount: AmountDisplayText,
    modifier: Modifier = Modifier,
    onTogglePrimary: ((AmountDisplayPrimary) -> Unit)? = null,
    padding: PaddingValues = PaddingValues(),
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val clickModifier = if (onTogglePrimary != null) {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
        ) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            onTogglePrimary(
                if (amount.effectivePrimary == AmountDisplayPrimary.Fiat) AmountDisplayPrimary.Sats
                else AmountDisplayPrimary.Fiat
            )
        }
    } else Modifier
    Column(
        modifier = modifier
            .then(clickModifier)
            .padding(padding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.micro),
    ) {
        AmountText(
            text = amount.primary,
            style = MaterialTheme.typography.displayMedium,
            color = LocalContentColor.current,
        )
        AnimatedVisibility(
            visible = amount.secondary != null,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            AmountText(
                text = amount.secondary.orEmpty(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                animated = false,
            )
        }
    }
}
