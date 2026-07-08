package org.cashu.wallet.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * In-screen face swap used by Send/Receive flows. Mirrors the iOS pattern from
 * UX_SPEC §17.3:
 *   forward  → enter from trailing  + opacity, leave to leading
 *   backward → enter from leading   + opacity, leave to trailing
 *
 * Direction is inferred from a caller-provided [forward] predicate evaluated on
 * (initial, target). Default: any transition that increases the step ordinal is
 * forward — callers that don't have an ordinal can supply `{ _, _ -> true }` and
 * stick with the slide-from-trailing default.
 */
@Composable
fun <T> TwoFaceScreen(
    targetState: T,
    modifier: Modifier = Modifier,
    forward: (initial: T, target: T) -> Boolean = { _, _ -> true },
    duration: Int = 320,
    label: String = "two-face",
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            slideTransition(
                duration = duration,
                forward = forward(initialState, targetState),
            )
        },
        label = label,
        content = { content(it) },
    )
}

private fun <S> AnimatedContentTransitionScope<S>.slideTransition(
    duration: Int,
    forward: Boolean,
): ContentTransform {
    val enterFrom = if (forward) AnimatedContentTransitionScope.SlideDirection.Start
    else AnimatedContentTransitionScope.SlideDirection.End
    val leaveTo = if (forward) AnimatedContentTransitionScope.SlideDirection.End
    else AnimatedContentTransitionScope.SlideDirection.Start
    return (
        slideIntoContainer(
            towards = enterFrom,
            animationSpec = tween(duration),
        ) + fadeIn(tween(duration))
    ).togetherWith(
        slideOutOfContainer(
            towards = leaveTo,
            animationSpec = tween(duration),
        ) + fadeOut(tween(duration))
    )
}
