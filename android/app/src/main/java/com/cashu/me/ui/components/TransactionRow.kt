package com.cashu.me.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.withMonoDigits

// Leading muted directional arrow on a soft circle. Glyph slightly larger
// than half the 40dp circle for clearer direction without growing the pad.
private val DirectionIconCircle = 40.dp
private val DirectionIconSize = 24.dp

data class TransactionRowModel(
    val transaction: WalletTransaction,
    val title: String,
    val timestamp: String,
    val primaryAmount: String,
    val secondaryAmount: String?,
    /**
     * Private mode: the amount becomes fixed asterisks with no sign (the colour
     * stays) and reads "Amount hidden", so the real strings never reach the
     * screen or the semantics tree.
     */
    val amountHidden: Boolean = false,
)

/**
 * Canonical timeline row. Leading muted directional arrow (direction is the
 * arrow's orientation, never colour); kind is named in the title. The amount is
 * the ledger signal: received is green with a plus, sent is primary with no
 * sign, and failed/pending/expired are muted with no sign. Failed uses an X.
 * Status remains in accessibility semantics without adding another visible line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TransactionRow(
    model: TransactionRowModel,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
) {
    val tx = model.transaction
    val incoming = tx.type == TransactionType.Incoming
    val isTransfer = tx.transfer != null
    val unsettled = tx.isUnsettled
    val amountColor = when {
        unsettled || tx.status == TransactionStatus.Failed -> MaterialTheme.colorScheme.onSurfaceVariant
        // Match iOS TransactionAmountColumn's .green ledger amounts.
        incoming -> CashuTheme.colors.received
        else -> MaterialTheme.colorScheme.onSurface
    }
    val hidden = model.amountHidden
    val amountText = when {
        hidden -> AmountFormatter.HiddenDigits
        tx.status == TransactionStatus.Completed && incoming -> "+${model.primaryAmount}"
        else -> model.primaryAmount
    }
    // An unfunded deposit address has no amount yet: no trailing element, like
    // an any-amount Cashu Request that is still waiting.
    val showsAmount = !tx.isUnfundedAddress
    val semanticAmount = (if (hidden) AmountFormatter.HiddenAmountLabel else amountText).takeIf { showsAmount }
    val semanticParts = listOfNotNull(
        model.title,
        // A transfer is neither; its title already says what it is.
        if (isTransfer) null else if (incoming) "Incoming" else "Outgoing",
        tx.displayStatusText,
        semanticAmount,
        model.secondaryAmount?.takeIf { showsAmount && !hidden },
        model.timestamp,
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = semanticParts.joinToString(", ")
            }
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = onLongClickLabel,
            )
            // Slightly looser than the original 16pt so home Recent + History
            // breathe between rows without going sparse.
            .padding(horizontal = CashuTheme.spacing.comfortable, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        DirectionIcon(incoming = incoming, isTransfer = isTransfer, isFailed = tx.status == TransactionStatus.Failed)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = model.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
            Text(
                text = model.timestamp,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showsAmount) Column(
            horizontalAlignment = Alignment.End,
            // The row's own description already says "Amount hidden"; keep the
            // fading-out digits from being read alongside it.
            modifier = if (hidden) Modifier.clearAndSetSemantics {} else Modifier,
        ) {
            AmountText(
                text = amountText,
                style = MaterialTheme.typography.bodyLarge
                    .copy(fontWeight = FontWeight.Medium)
                    .withMonoDigits(),
                color = amountColor,
                maxLines = 1,
            )
            if (model.secondaryAmount != null) {
                AmountText(
                    // A no-break space, not nothing: the line keeps its height
                    // and the fiat value fades out rather than snapping away.
                    text = if (hidden) "\u00A0" else model.secondaryAmount,
                    style = MaterialTheme.typography.bodyMedium
                        .copy(fontWeight = FontWeight.Normal)
                        .withMonoDigits(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * Muted directional arrow, or failure X, on a soft neutral circle (iOS
 * TransactionIcon). A transfer between the user's own mints is neither
 * received nor sent, so it takes a two-way arrow.
 */
@Composable
internal fun DirectionIcon(incoming: Boolean, isTransfer: Boolean = false, isFailed: Boolean = false) {
    val (glyph, description) = when {
        // The row already announces failure; keep the X decorative.
        isFailed -> Icons.Filled.Close to null
        // The title already opens with "Transfer"; the glyph adds nothing to hear.
        isTransfer -> Icons.Filled.SwapHoriz to null
        incoming -> Icons.Filled.ArrowDownward to "Incoming"
        else -> Icons.Filled.ArrowUpward to "Outgoing"
    }
    Box(
        modifier = Modifier
            .size(DirectionIconCircle)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = glyph,
            contentDescription = description,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(DirectionIconSize)
                .then(if (isFailed) Modifier.testTag("transaction-failure-icon") else Modifier),
        )
    }
}
