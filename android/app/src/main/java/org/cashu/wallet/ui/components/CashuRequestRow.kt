package org.cashu.wallet.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.outlined.Money
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import org.cashu.wallet.Models.CashuRequest
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

// Matches TransactionRow geometry for vertically-aligned timeline rendering.
private val RequestIconSize = 40.dp

/**
 * Cashu Request timeline row, paired with [TransactionRow] in History and Home Recent.
 * Mirrors iOS CashuRequestAmountColumn variants — fixed-amount vs any-amount,
 * waiting vs received.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CashuRequestRow(
    request: CashuRequest,
    timestamp: String,
    primaryAmountText: String?,
    secondaryAmountText: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val received = request.receivedPayments.isNotEmpty()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .padding(horizontal = CashuTheme.spacing.comfortable, vertical = CashuTheme.spacing.default),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        RequestIconWithStatusBadge(received = received)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Cashu Request",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = timestamp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (primaryAmountText != null) {
                Text(
                    text = "+$primaryAmountText",
                    style = MaterialTheme.typography.bodyLarge.withMonoDigits(),
                    color = if (received) CashuTheme.colors.received
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (secondaryAmountText != null) {
                Text(
                    text = secondaryAmountText,
                    style = MaterialTheme.typography.bodySmall.withMonoDigits(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Bare ecash icon — no bottom-trailing status badge, matching iOS. */
@Composable
private fun RequestIconWithStatusBadge(@Suppress("UNUSED_PARAMETER") received: Boolean) {
    Box(
        modifier = Modifier
            .size(RequestIconSize)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Money,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(CashuTheme.spacing.loose),
        )
    }
}
