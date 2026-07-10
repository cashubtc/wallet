package org.cashu.wallet.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import org.cashu.wallet.Core.AmountDisplayPrimary
import org.cashu.wallet.Core.AmountDisplayText
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.rememberReducedMotion
import org.cashu.wallet.ui.theme.withMonoDigits

/** What occupies the status line under the hero number. */
private sealed interface BalanceStatusLine {
    /** Transient "+2,500" received beat (takes over the fiat slot). */
    data class Delta(val text: String) : BalanceStatusLine

    /** The regular fiat/secondary sub-amount. */
    data class Secondary(val text: String) : BalanceStatusLine

    data object None : BalanceStatusLine
}

/**
 * Large hero balance with optional secondary line. Tap to toggle the primary unit.
 * Numbers cross-fade on change via [AmountText].
 *
 * @param receivedDelta transient received-delta beat ("+2,500"): while non-null
 *   it takes over the secondary slot with the sanctioned celebration spring
 *   (scale 0.9 + fade in, fade out), then the fiat line fades back. Same slot,
 *   so the swap never reflows the balance (iOS MainWalletView parity).
 */
@Composable
fun BalanceDisplay(
    amount: AmountDisplayText,
    modifier: Modifier = Modifier,
    onTogglePrimary: ((AmountDisplayPrimary) -> Unit)? = null,
    padding: PaddingValues = PaddingValues(),
    receivedDelta: String? = null,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val reduceMotion = rememberReducedMotion()
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
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
            color = LocalContentColor.current,
        )
        val statusLine: BalanceStatusLine = when {
            receivedDelta != null -> BalanceStatusLine.Delta(receivedDelta)
            amount.secondary != null -> BalanceStatusLine.Secondary(amount.secondary)
            else -> BalanceStatusLine.None
        }
        AnimatedContent(
            targetState = statusLine,
            transitionSpec = {
                // Celebration spring only when the delta beat lands; everything
                // else (fiat return, plain show/hide) is a quiet cross-fade.
                // Reduce-motion collapses the beat to the same cross-fade.
                val enter = if (targetState is BalanceStatusLine.Delta && !reduceMotion) {
                    fadeIn(spring(stiffness = Spring.StiffnessMedium)) + scaleIn(
                        animationSpec = spring(
                            dampingRatio = 0.7f,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                        initialScale = 0.9f,
                    )
                } else {
                    fadeIn(spring(stiffness = Spring.StiffnessMedium))
                }
                enter.togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
            },
            label = "balance-status-line",
        ) { line ->
            when (line) {
                is BalanceStatusLine.Delta ->
                    // Quiet monochrome beat — no green, no checkmark, no bounce:
                    // the rolling balance above is the primary signal.
                    Text(
                        text = line.text,
                        style = MaterialTheme.typography.titleMedium
                            .withMonoDigits()
                            .copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                is BalanceStatusLine.Secondary ->
                    AmountText(
                        text = line.text,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        animated = false,
                    )
                BalanceStatusLine.None ->
                    // No status line configured (fiat hidden): render nothing,
                    // matching the pre-existing collapse behavior and iOS.
                    Box(Modifier)
            }
        }
    }
}
