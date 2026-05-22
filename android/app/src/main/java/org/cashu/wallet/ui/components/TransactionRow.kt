package org.cashu.wallet.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.Money
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.cashu.wallet.Models.TransactionKind
import org.cashu.wallet.Models.TransactionStatus
import org.cashu.wallet.Models.TransactionType
import org.cashu.wallet.Models.WalletTransaction
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

// M3 ListItem rejected here: the pending-refresh affordance + trailing amount
// column don't fit ListItem's single trailingContent slot cleanly. Hand-rolled
// row preserves iOS anatomy.
private val MethodIconSize = 40.dp
private val RefreshButtonSize = 24.dp
private val RefreshIconSize = 16.dp

data class TransactionRowModel(
    val transaction: WalletTransaction,
    val title: String,
    val timestamp: String,
    val primaryAmount: String,
    val secondaryAmount: String?,
)

@Composable
fun TransactionRow(
    model: TransactionRowModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onRefresh: (() -> Unit)? = null,
    isChecking: Boolean = false,
) {
    val tx = model.transaction
    val incoming = tx.type == TransactionType.Incoming
    val amountColor = when (tx.status) {
        TransactionStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant
        TransactionStatus.Completed -> CashuTheme.colors.received
        TransactionStatus.Failed -> MaterialTheme.colorScheme.onSurface
    }
    val signPrefix = if (incoming) "+" else "−"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = CashuTheme.spacing.comfortable, vertical = CashuTheme.spacing.default),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        MethodIconWithStatusBadge(
            kind = tx.kind,
            status = tx.status,
            incoming = incoming,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = model.timestamp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Pending-refresh affordance per iOS TransactionAmountColumn.
        if (tx.status == TransactionStatus.Pending && onRefresh != null) {
            if (isChecking) {
                CircularProgressIndicator(
                    modifier = Modifier.size(RefreshIconSize),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(RefreshButtonSize),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Sync,
                        contentDescription = "Refresh status",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(RefreshIconSize),
                    )
                }
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$signPrefix${model.primaryAmount}",
                style = MaterialTheme.typography.bodyLarge.withMonoDigits(),
                color = amountColor,
            )
            if (model.secondaryAmount != null) {
                Text(
                    text = model.secondaryAmount,
                    style = MaterialTheme.typography.bodySmall.withMonoDigits(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Bare method icon — no bottom-trailing status badge. Status is conveyed by the
 * amount color (One-Green Rule), the +/− sign, and the refresh affordance for
 * pending tx, matching iOS.
 */
@Composable
private fun MethodIconWithStatusBadge(
    kind: TransactionKind,
    @Suppress("UNUSED_PARAMETER") status: TransactionStatus,
    @Suppress("UNUSED_PARAMETER") incoming: Boolean,
) {
    val methodIcon: ImageVector = when (kind) {
        TransactionKind.Ecash -> Icons.Outlined.Money
        TransactionKind.Lightning -> Icons.Filled.Bolt
        TransactionKind.Onchain -> Icons.Outlined.CurrencyBitcoin
    }
    Box(
        modifier = Modifier
            .size(MethodIconSize)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = methodIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(CashuTheme.spacing.loose),
        )
    }
}
