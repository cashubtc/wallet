package com.cashu.me.Core

import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction

/**
 * Recent includes completed payments and funds awaiting settlement or reclaim.
 */
internal fun recentPaymentTransactions(
    transactions: List<WalletTransaction>,
    limit: Int,
): List<WalletTransaction> = transactions
    .asSequence()
    // Recent is a keyed lazy list; a repeated id there is a launch-time crash.
    .distinctBy { it.id }
    // An in-flight deposit is money moving; an address nothing has reached
    // yet is only a request, like an unpaid invoice.
    .filter {
        it.status == TransactionStatus.Completed ||
            (it.kind == TransactionKind.Ecash && it.type == TransactionType.Outgoing && it.status == TransactionStatus.Pending) ||
            (it.kind == TransactionKind.Onchain && it.status == TransactionStatus.Pending && !it.isUnfundedAddress)
    }
    .sortedByDescending { it.dateEpochMillis }
    .take(limit.coerceAtLeast(0))
    .toList()
