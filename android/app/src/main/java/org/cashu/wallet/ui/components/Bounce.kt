package org.cashu.wallet.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import org.cashu.wallet.ui.theme.rememberReducedMotion

private const val BouncePeakScale = 1.15f
private const val BounceSettleDamping = 0.5f

/**
 * One-shot glyph bounce — the Compose equivalent of iOS
 * `.symbolEffect(.bounce, value:)`. Returns a scale to apply via
 * `graphicsLayer`; it springs 1 → 1.15 → 1 whenever [trigger] changes.
 *
 * @param trigger stable value; each change fires one bounce.
 * @param bounceOnEntry also bounce on first composition (celebration glyphs
 *   that enter together with their state, e.g. the success check). Leave false
 *   for persistent glyphs that only bounce on later changes (copy-confirm).
 *
 * Reduce-motion renders the resting scale, matching the iOS gating.
 */
@Composable
fun rememberBounceScale(trigger: Any?, bounceOnEntry: Boolean = false): Float {
    val reduced by rememberUpdatedState(rememberReducedMotion())
    val scale = remember { Animatable(1f) }
    val firstRun = remember { booleanArrayOf(true) }
    LaunchedEffect(trigger) {
        val isFirst = firstRun[0]
        firstRun[0] = false
        if (reduced || (isFirst && !bounceOnEntry)) return@LaunchedEffect
        scale.snapTo(1f)
        scale.animateTo(BouncePeakScale, spring(stiffness = Spring.StiffnessHigh))
        scale.animateTo(
            1f,
            spring(dampingRatio = BounceSettleDamping, stiffness = Spring.StiffnessMedium),
        )
    }
    return scale.value
}
