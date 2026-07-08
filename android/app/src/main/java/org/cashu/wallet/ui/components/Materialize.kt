package org.cashu.wallet.ui.components

import android.os.Build
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.cashu.wallet.ui.theme.rememberReducedMotion

// iOS AnyTransition.materializeBlur sharpens from a 4pt radius.
private val MaterializeBlurRadius = 4.dp

/**
 * Blur-to-sharp "materialize" entrance — the Compose equivalent of the iOS
 * `AnyTransition.materializeBlur` carve-out used on the success check. Apply
 * alongside the enter transition; the content settles from a 4dp blur to
 * sharp on a medium spring.
 *
 * `RenderEffect` requires API 31; below that (and under reduce-motion) this is
 * a no-op — the paired fade/scale enter still carries the moment.
 */
@Composable
fun Modifier.materializeBlur(): Modifier {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || rememberReducedMotion()) {
        return this
    }
    var materialized by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { materialized = true }
    val radiusPx by animateFloatAsState(
        targetValue = if (materialized) 0f else with(LocalDensity.current) {
            MaterializeBlurRadius.toPx()
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "materialize-blur",
    )
    return this.graphicsLayer {
        renderEffect = if (radiusPx > 0.05f) {
            BlurEffect(radiusPx, radiusPx, TileMode.Decal)
        } else {
            null
        }
    }
}
