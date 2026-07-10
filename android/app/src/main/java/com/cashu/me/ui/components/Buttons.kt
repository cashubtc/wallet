package com.cashu.me.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cashu.me.ui.theme.CashuTheme

// 58dp min height with 16dp vertical padding matches iOS's large glass capsule buttons.
private val ButtonMinHeight = 58.dp
private val ButtonContentVertical = 16.dp
private val ButtonProgressSize = 24.dp
// Chevron-scale glyph inside GhostButton labels.
private val GhostButtonIconSize = 16.dp
private const val PressedScale = 0.97f
// iOS TextLinkButtonStyle: text links dim to 0.6 while pressed.
private const val TextLinkPressedAlpha = 0.6f

@Composable
private fun rememberPressScale(interactionSource: MutableInteractionSource): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PressedScale else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "press-scale",
    )
    return scale
}

/**
 * Pressed-opacity feedback for text-style buttons — the iOS
 * `TextLinkButtonStyle` (opacity 0.6 while pressed) on a medium spring.
 */
@Composable
private fun rememberPressAlpha(interactionSource: MutableInteractionSource): Float {
    val pressed by interactionSource.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        targetValue = if (pressed) TextLinkPressedAlpha else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "press-alpha",
    )
    return alpha
}

/**
 * The primary full-width CTA: filled M3 button on the theme's primary color
 * (inverted ink: black in light mode, white in dark), spring press-scale,
 * expressive loading indicator.
 *
 * Pass [colors] to override the default filled treatment (e.g. the home
 * screen's tonal Receive/Send pair, which matches the history row's arrow
 * chips instead of using the inverted-ink primary color).
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    colors: ButtonColors? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactionSource)
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ButtonMinHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        enabled = enabled && !loading,
        interactionSource = interactionSource,
        colors = colors ?: ButtonDefaults.buttonColors(),
        contentPadding = PaddingValues(horizontal = CashuTheme.spacing.section, vertical = ButtonContentVertical),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) {
                LoadingIndicator(
                    modifier = Modifier.size(ButtonProgressSize),
                    color = LocalContentColor.current,
                )
            } else {
                AnimatedContent(
                    targetState = text,
                    transitionSpec = {
                        fadeIn(spring(stiffness = Spring.StiffnessMedium))
                            .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
                    },
                    label = "primary-button-text",
                ) { current ->
                    Text(
                        text = current,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                    )
                }
            }
        }
    }
}

/** The secondary full-width CTA: tonal, one step quieter than [PrimaryButton]. */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val scale = rememberPressScale(interactionSource)
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ButtonMinHeight)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            },
        enabled = enabled,
        interactionSource = interactionSource,
        contentPadding = PaddingValues(horizontal = CashuTheme.spacing.section, vertical = ButtonContentVertical),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
    }
}

/** Inline non-emphasized action (Copy, Paste, Restore from seed, etc.). */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingIcon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val alpha = rememberPressAlpha(interactionSource)
    TextButton(
        onClick = onClick,
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        enabled = enabled,
        interactionSource = interactionSource,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
        if (trailingIcon != null) {
            Spacer(Modifier.width(CashuTheme.spacing.micro))
            Icon(
                imageVector = trailingIcon,
                contentDescription = null,
                modifier = Modifier.size(GhostButtonIconSize),
            )
        }
    }
}

/** Destructive inline action (Delete Wallet, Remove Mint). */
@Composable
fun DestructiveTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val alpha = rememberPressAlpha(interactionSource)
    TextButton(
        onClick = onClick,
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}
