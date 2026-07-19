package com.cashu.me.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.rememberReducedMotion

/**
 * Pulsing orange clock + neutral status text for a pending payment — shared by
 * the receive rails and the Cashu Request detail screen. iOS parity:
 * `ReceiveLightningView.statusBadge` pending branch.
 */
@Composable
fun WaitingForPaymentRow(text: String = "Waiting for payment…") {
    val reducedMotion = rememberReducedMotion()
    val transition = rememberInfiniteTransition(label = "waiting-pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(
            animation = tween(1100),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "waiting-pulse-alpha",
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
    ) {
        Box(modifier = Modifier.alpha(if (reducedMotion) 1f else alpha)) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = CashuTheme.colors.pending,
                modifier = Modifier.size(CashuTheme.spacing.loose),
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
