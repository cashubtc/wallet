package com.cashu.me.Core

import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.WalletTransaction

/**
 * Resolve the live transaction for a detail screen that was opened with
 * [openId] (and optional [openQuoteId] from the open-time snapshot).
 *
 * Pending mint-quote rows use `id == quoteId`. After mint, CDK replaces them
 * with a different transaction id that still carries `quoteId`. Matching only
 * on id therefore drops the row mid-refresh ("Transaction not found") and
 * fails to flip Pending → Completed without reopening.
 */
internal fun resolveTransactionForDetail(
    transactions: List<WalletTransaction>,
    openId: String,
    openQuoteId: String? = null,
): WalletTransaction? {
    transactions.firstOrNull { it.id == openId }?.let { return it }

    val keys = buildSet {
        add(openId)
        openQuoteId?.takeIf { it.isNotEmpty() }?.let { add(it) }
    }
    val matches = transactions.filter { tx ->
        tx.id in keys || (tx.quoteId != null && tx.quoteId in keys)
    }
    if (matches.isEmpty()) return null

    // Prefer a completed settlement for this quote, then the newest row.
    return matches.maxWithOrNull(
        compareBy<WalletTransaction> { it.status == TransactionStatus.Completed }
            .thenBy { it.dateEpochMillis },
    )
}
