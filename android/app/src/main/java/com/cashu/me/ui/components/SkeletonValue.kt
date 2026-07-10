package com.cashu.me.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val PlaceholderWidth = 64.dp
private val PlaceholderHeight = 16.dp
private val PlaceholderCorner = 4.dp
private const val PlaceholderAlpha = 0.6f

/**
 * Skeleton-to-value fill-in — the Compose equivalent of the iOS
 * `.redacted(.placeholder)` + `.animation(.smooth)` pattern on confirm-screen
 * fee rows. While [loading], a quiet rounded bar holds the slot; when the
 * value lands it crossfades in place. No shimmer, matching iOS.
 *
 * Flicker guard: key this composable (or its parent) on the *request identity*
 * (e.g. quote id) so a superseded late response can't replay the crossfade
 * over an already-displayed value.
 */
@Composable
fun SkeletonValue(
    loading: Boolean,
    modifier: Modifier = Modifier,
    placeholderWidth: Dp = PlaceholderWidth,
    placeholderHeight: Dp = PlaceholderHeight,
    content: @Composable () -> Unit,
) {
    AnimatedContent(
        targetState = loading,
        transitionSpec = {
            fadeIn(spring(stiffness = Spring.StiffnessMediumLow))
                .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMediumLow)))
        },
        label = "skeleton-value",
        modifier = modifier,
    ) { isLoading ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .size(width = placeholderWidth, height = placeholderHeight)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = PlaceholderAlpha),
                        shape = RoundedCornerShape(PlaceholderCorner),
                    ),
            )
        } else {
            content()
        }
    }
}
