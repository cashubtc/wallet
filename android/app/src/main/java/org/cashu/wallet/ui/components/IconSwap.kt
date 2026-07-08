package org.cashu.wallet.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

private const val SwapInitialScale = 0.8f

/**
 * Animated glyph replacement — the Compose equivalent of iOS
 * `.contentTransition(.symbolEffect(.replace))`. The outgoing icon fades while
 * the incoming one grows in from 0.8 on a medium spring. Used for copy-confirm
 * checks, selection circles, method badges, and restore result glyphs.
 *
 * Identity is the [icon] itself: pass a stable [ImageVector] per state so the
 * swap only animates on a real state change (never mid-display).
 */
@Composable
fun IconSwap(
    icon: ImageVector,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = Color.Unspecified,
) {
    AnimatedContent(
        targetState = icon,
        transitionSpec = {
            (
                fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
                    scaleIn(
                        animationSpec = spring(stiffness = Spring.StiffnessMedium),
                        initialScale = SwapInitialScale,
                    )
                ).togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
        },
        label = "icon-swap",
        modifier = modifier,
    ) { current ->
        Icon(
            imageVector = current,
            contentDescription = contentDescription,
            tint = if (tint == Color.Unspecified) LocalContentColor.current else tint,
        )
    }
}
