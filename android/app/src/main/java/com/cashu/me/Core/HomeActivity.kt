package com.cashu.me.Core

import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.WalletTransaction

/**
 * Home is a compact settled-ledger view, not an operational queue. Generated
 * receive artifacts and every non-completed state remain available in History.
 */
internal fun recentCompletedTransactions(
    transactions: List<WalletTransaction>,
    limit: Int,
): List<WalletTransaction> = transactions
    .asSequence()
    .filter { it.status == TransactionStatus.Completed }
    .sortedByDescending { it.dateEpochMillis }
    .take(limit.coerceAtLeast(0))
    .toList()
