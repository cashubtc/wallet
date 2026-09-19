package com.cashu.me.Core

import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction

/** A BOLT11 receipt represents one quote; CDK retains every mint attempt for recovery. */
internal object MintReceiptProjection {
    private data class Key(val mint: String, val unit: String, val quote: String)

    fun project(transactions: List<WalletTransaction>): List<WalletTransaction> {
        val groups = transactions.indices.filter { index ->
            val tx = transactions[index]
            tx.type == TransactionType.Incoming && tx.kind == TransactionKind.Lightning &&
                tx.paymentMethod == PaymentMethodKind.Bolt11 && tx.sagaId != null &&
                !tx.mintUrl.isNullOrEmpty() && !tx.quoteId.isNullOrEmpty()
        }.groupBy { index ->
            val tx = transactions[index]
            Key(requireNotNull(tx.mintUrl), tx.unit.lowercase(), requireNotNull(tx.quoteId))
        }
        val hidden = mutableSetOf<Int>()
        for (indices in groups.values.filter { it.size > 1 }) {
            val completed = indices.filter { transactions[it].status == TransactionStatus.Completed }
            // Conflicting settlements or payloads need investigation, not concealment.
            val invoices = indices.mapNotNull { transactions[it].invoice?.lowercase() }.toSet()
            if (completed.size > 1 || invoices.size > 1 || indices.map { transactions[it].amount }.toSet().size > 1) continue
            val pending = indices.filter { transactions[it].status == TransactionStatus.Pending }
            val candidates = completed.ifEmpty { pending.ifEmpty { indices } }
            val winner = candidates.maxWith(compareBy<Int> { transactions[it].dateEpochMillis }.thenBy { transactions[it].id })
            hidden.addAll(indices.filter { it != winner })
        }
        return transactions.filterIndexed { index, _ -> index !in hidden }
    }
}
