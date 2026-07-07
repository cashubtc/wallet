package org.cashu.wallet.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Models.CashuRequest
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

/**
 * Trailing amount for a request row, in the request's own unit: cumulative
 * received when payments landed, the fixed target while waiting, nothing for
 * a waiting any-amount request.
 */
fun requestRowAmount(
    request: CashuRequest,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
): String? {
    val amount = when {
        request.totalReceived > 0L -> request.totalReceived
        request.amount != null && request.amount > 0L -> request.amount
        else -> return null
    }
    return if (request.unit.equals("sat", ignoreCase = true)) {
        formatter.formatWalletSats(amount, useBitcoinSymbol)
    } else {
        CurrencyAmount(amount, CurrencyRegistry.currencyForMintUnit(request.unit)).formatted()
    }
}

/**
 * Cashu Request timeline row, paired with [TransactionRow] in History and Home
 * Recent. A request is structurally an incoming-ecash event in waiting, so the
 * leading icon is the same muted down-arrow. Amount states mirror iOS
 * CashuRequestAmountColumn: fixed+waiting → bare muted amount; received →
 * "+amount" in primary; any-amount+waiting → no trailing element (caller passes
 * null primaryAmountText).
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
    val rowDescription = buildList {
        add("Cashu Request")
        add(if (received) "Received" else "Waiting")
        primaryAmountText?.let { add(if (received) "+$it" else it) }
        secondaryAmountText?.let(::add)
        add(timestamp)
    }.joinToString(", ")
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
            )
            .semantics { contentDescription = rowDescription }
            .padding(horizontal = CashuTheme.spacing.comfortable, vertical = CashuTheme.spacing.default),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        DirectionIcon(incoming = true)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Cashu Request",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
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
                    text = if (received) "+$primaryAmountText" else primaryAmountText,
                    style = MaterialTheme.typography.bodyLarge.withMonoDigits(),
                    fontWeight = FontWeight.SemiBold,
                    color = if (received) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
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
