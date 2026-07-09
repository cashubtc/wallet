package org.cashu.wallet.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.cashu.wallet.ui.theme.rememberReducedMotion

// Material 3 shared axis X: both faces travel 30dp in the same direction while
// the outgoing fades out quickly (90ms) and the incoming fades in over the
// remainder (210ms, delayed past the outgoing fade).
private const val SharedAxisDurationMillis = 300
private const val OutgoingFadeMillis = 90
private const val IncomingFadeMillis = 210
private val SharedAxisSlideDistance = 30.dp

/**
 * In-screen face swap used by Send/Receive flows, animated with the Material 3
 * shared axis X pattern (forward = travel toward Start, backward = toward End).
 *
 * Direction is inferred from a caller-provided [forward] predicate evaluated on
 * (initial, target). Default: any transition that increases the step ordinal is
 * forward — callers that don't have an ordinal can supply `{ _, _ -> true }`.
 *
 * Reduced motion collapses the slide to a plain cross-fade.
 */
@Composable
fun <T> TwoFaceScreen(
    targetState: T,
    modifier: Modifier = Modifier,
    forward: (initial: T, target: T) -> Boolean = { _, _ -> true },
    label: String = "two-face",
    content: @Composable (T) -> Unit,
) {
    val reducedMotion = rememberReducedMotion()
    val slidePx = with(LocalDensity.current) { SharedAxisSlideDistance.roundToPx() }
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            if (reducedMotion) {
                fadeIn(tween(SharedAxisDurationMillis))
                    .togetherWith(fadeOut(tween(SharedAxisDurationMillis)))
            } else {
                sharedAxisX(
                    slidePx = slidePx,
                    forward = forward(initialState, targetState),
                )
            }
        },
        label = label,
        content = { content(it) },
    )
}

private fun sharedAxisX(slidePx: Int, forward: Boolean): ContentTransform {
    // Forward: incoming starts +30dp (from End) and settles at 0 while the
    // outgoing continues to -30dp — one continuous leftward motion. Backward
    // mirrors it rightward.
    val enterOffset = if (forward) slidePx else -slidePx
    val exitOffset = if (forward) -slidePx else slidePx
    return (
        slideInHorizontally(
            animationSpec = tween(SharedAxisDurationMillis, easing = FastOutSlowInEasing),
            initialOffsetX = { enterOffset },
        ) + fadeIn(
            tween(
                durationMillis = IncomingFadeMillis,
                delayMillis = OutgoingFadeMillis,
                easing = LinearOutSlowInEasing,
            ),
        )
    ).togetherWith(
        slideOutHorizontally(
            animationSpec = tween(SharedAxisDurationMillis, easing = FastOutSlowInEasing),
            targetOffsetX = { exitOffset },
        ) + fadeOut(
            tween(durationMillis = OutgoingFadeMillis, easing = FastOutLinearInEasing),
        ),
    )
}
