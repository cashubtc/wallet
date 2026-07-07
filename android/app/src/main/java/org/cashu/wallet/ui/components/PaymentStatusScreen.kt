package org.cashu.wallet.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.cashu.wallet.ui.theme.CashuTheme

// 64dp terminal glyph per the iOS PaymentStatusView spec; 40dp working spinner.
private val StatusGlyphSize = 64.dp
private val SpinnerSize = 40.dp

enum class PaymentStatusPhase { Processing, Success, Failure }

/**
 * The shared full-screen terminal for every pay flow (iOS PaymentStatusView):
 * processing → success/failure on the bare canvas. The glyph slot morphs
 * spinner → 64dp green check / red X with a smooth fade + a single gentle
 * scale-in (0.9 → 1, the one celebration beat — nothing else springs).
 * Success/failure require an explicit Done tap; processing shows no actions.
 */
@Composable
fun PaymentStatusScreen(
    phase: PaymentStatusPhase,
    title: String,
    detail: String? = null,
    modifier: Modifier = Modifier,
    doneLabel: String = "Done",
    onDone: (() -> Unit)? = null,
) {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(phase) {
        when (phase) {
            PaymentStatusPhase.Success -> haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            PaymentStatusPhase.Failure -> haptics.performHapticFeedback(HapticFeedbackType.Reject)
            PaymentStatusPhase.Processing -> Unit
        }
    }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = CashuTheme.spacing.page),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AnimatedContent(
                targetState = phase,
                transitionSpec = {
                    // The check/X grows in gently from 0.9; the spinner just fades.
                    val enter = if (targetState == PaymentStatusPhase.Processing) {
                        fadeIn(tween(200))
                    } else {
                        fadeIn(tween(200)) + scaleIn(
                            animationSpec = spring(
                                dampingRatio = 0.7f,
                                stiffness = Spring.StiffnessMediumLow,
                            ),
                            initialScale = 0.9f,
                        )
                    }
                    enter togetherWith fadeOut(tween(150))
                },
                label = "payment-status-glyph",
            ) { current ->
                Box(
                    modifier = Modifier.size(StatusGlyphSize),
                    contentAlignment = Alignment.Center,
                ) {
                    when (current) {
                        PaymentStatusPhase.Processing -> CircularProgressIndicator(
                            modifier = Modifier.size(SpinnerSize),
                            strokeWidth = 3.dp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        PaymentStatusPhase.Success -> Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = "Success",
                            tint = CashuTheme.colors.received,
                            modifier = Modifier.size(StatusGlyphSize),
                        )
                        PaymentStatusPhase.Failure -> Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = "Failed",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(StatusGlyphSize),
                        )
                    }
                }
            }
            Spacer(Modifier.height(CashuTheme.spacing.section))
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            if (detail != null) {
                Spacer(Modifier.height(CashuTheme.spacing.snug))
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (phase != PaymentStatusPhase.Processing && onDone != null) {
            PrimaryButton(
                text = doneLabel,
                onClick = onDone,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = CashuTheme.spacing.comfortable)
                    .navigationBarsPadding()
                    .padding(bottom = CashuTheme.spacing.comfortable),
            )
        }
    }
}
