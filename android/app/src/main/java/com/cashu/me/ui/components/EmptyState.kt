package com.cashu.me.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cashu.me.ui.theme.rememberReducedMotion
import com.cashu.me.ui.theme.CashuTheme

// Matches iOS NativeEmptyState's 56pt glyph (component-level, not on the
// spacing scale).
private val EmptyStateIconSize = 56.dp
// iOS renders the glyph hierarchically at 0.62 opacity; dim the tint the same
// amount so the icon sits behind the text instead of competing with it.
private const val EmptyStateIconAlpha = 0.62f
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
 *
 * @param fillHeight when true (default), expands to fill the parent and
 *   centers its content — home/history empty trays. Set false for wrap-content
 *   hosts like a content-fit Send bottom sheet.
 */
@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    fillHeight: Boolean = true,
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
            .then(if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
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
        verticalArrangement = if (fillHeight) Arrangement.Center else Arrangement.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = EmptyStateIconAlpha),
            modifier = Modifier
                .size(EmptyStateIconSize)
                .graphicsLayer {
                    scaleX = iconBounce
                    scaleY = iconBounce
                },
        )
        Spacer(Modifier.height(CashuTheme.spacing.default))
        Text(
            text = title,
            // iOS title2 semibold (22pt); titleLarge is 22sp but ships Normal.
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (supporting != null) {
            Spacer(Modifier.height(CashuTheme.spacing.micro))
            Text(
                text = supporting,
                // iOS body (17pt) with SF's near-zero tracking; bodyLarge's
                // default 0.5sp letter spacing reads looser than the iOS twin.
                style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 0.sp),
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
