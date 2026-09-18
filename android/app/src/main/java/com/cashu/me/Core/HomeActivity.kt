package com.cashu.me.Core

import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.WalletTransaction

/**
 * Recent includes completed payments and on-chain payments awaiting settlement.
 */
internal fun recentPaymentTransactions(
    transactions: List<WalletTransaction>,
    limit: Int,
): List<WalletTransaction> = transactions
    .asSequence()
    .filter {
        it.status == TransactionStatus.Completed ||
            (it.kind == TransactionKind.Onchain && it.status == TransactionStatus.Pending)
    }
    .sortedByDescending { it.dateEpochMillis }
    .take(limit.coerceAtLeast(0))
    .toList()
