package org.cashu.wallet.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.cashu.wallet.ui.theme.rememberReducedMotion
import org.cashu.wallet.ui.theme.CashuTheme

// M3 empty-state icon is 48dp (component-level, not on the spacing scale).
private val EmptyStateIconSize = 48.dp
private const val EmptyStateActionWidthFraction = 0.7f
// iOS NativeEmptyState entrance: opacity 0→1, scale 0.96→1, rise from 8pt.
private const val EntranceInitialScale = 0.96f
private val EntranceRise = 8.dp
private const val EntranceDamping = 0.82f

/**
 * Quiet tray empty state (iOS NativeEmptyState). Settles in on mount — fade,
 * scale from 0.96, and an 8dp rise on a gently-damped spring — and the glyph
 * bounces once. Reduce-motion keeps the fade only. The centering layout lives
 * outside the animated layer, so the entrance can't drag content in from the
 * top-left (see the 2026-07 fly-in bug history).
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val reduceMotion = rememberReducedMotion()
    var appeared by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { appeared = true }
    val alpha by animateFloatAsState(
        targetValue = if (appeared) 1f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "empty-entrance-alpha",
    )
    val settle by animateFloatAsState(
        targetValue = if (appeared || reduceMotion) 1f else 0f,
        animationSpec = spring(
            dampingRatio = EntranceDamping,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "empty-entrance-settle",
    )
    val risePx = with(LocalDensity.current) { EntranceRise.toPx() }
    val iconBounce = rememberBounceScale(trigger = Unit, bounceOnEntry = true)
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.comfortable)
            .graphicsLayer {
                this.alpha = alpha
                if (!reduceMotion) {
                    val scale = EntranceInitialScale + (1f - EntranceInitialScale) * settle
                    scaleX = scale
                    scaleY = scale
                    translationY = risePx * (1f - settle)
                }
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .size(EmptyStateIconSize)
                .graphicsLayer {
                    scaleX = iconBounce
                    scaleY = iconBounce
                },
        )
        Spacer(Modifier.height(CashuTheme.spacing.comfortable))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (supporting != null) {
            Spacer(Modifier.height(CashuTheme.spacing.snug))
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(CashuTheme.spacing.section))
            PrimaryButton(
                text = actionLabel,
                onClick = onAction,
                // Deliberately narrower than a full-width CTA: the empty-state
                // action is an invitation, not the screen's primary commit.
                modifier = Modifier.fillMaxWidth(EmptyStateActionWidthFraction),
            )
        }
    }
}
