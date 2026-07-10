package com.cashu.me.Core

import java.net.URI
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction

data class TransactionDetailField(
    val label: String,
    val value: String,
)

object TransactionDisplay {
    // Kind-first, capitalized kind, lowercase verb — single source of truth for
    // rows AND the detail title, so a row and the sheet it opens read identically.
    fun title(transaction: WalletTransaction): String = when (transaction.kind) {
        TransactionKind.Lightning -> if (transaction.type == TransactionType.Incoming) {
            "Lightning received"
        } else {
            "Lightning paid"
        }
        TransactionKind.Onchain -> if (transaction.type == TransactionType.Incoming) {
            "Bitcoin received"
        } else {
            "Bitcoin sent"
        }
        TransactionKind.Ecash -> if (transaction.type == TransactionType.Incoming) {
            "Ecash received"
        } else {
            "Ecash sent"
        }
    }

    // The Status detail row: monochrome value, the hero glyph carries colour.
    fun statusText(transaction: WalletTransaction): String = when (transaction.status) {
        TransactionStatus.Completed -> when (transaction.kind) {
            TransactionKind.Ecash -> "Claimed"
            TransactionKind.Lightning -> "Paid"
            TransactionKind.Onchain -> "Confirmed"
        }
        TransactionStatus.Pending -> "Pending"
        TransactionStatus.Failed -> "Failed"
    }

    fun qrContent(transaction: WalletTransaction): String? =
        transaction.token ?: transaction.invoice

    /**
     * The QR hero appears only for an actionable request: a pending
     * transaction (unclaimed outgoing token, unpaid invoice, pending on-chain
     * address) or a reusable BOLT12 offer. Settled artifacts never re-present
     * as scannable payment codes.
     */
    fun showsQr(transaction: WalletTransaction): Boolean {
        if (qrContent(transaction) == null) return false
        if (transaction.status == TransactionStatus.Pending) return true
        return transaction.invoice?.startsWith("lno", ignoreCase = true) == true
    }

    /**
     * Settled-ecash receipt carve-out: a completed ecash transaction keeps a
     * passive Copy of the raw token as a record — never the QR or Share.
     */
    fun copyableContent(transaction: WalletTransaction): String? = when {
        showsQr(transaction) -> qrContent(transaction)
        transaction.kind == TransactionKind.Ecash &&
            transaction.status == TransactionStatus.Completed -> transaction.token
        else -> null
    }

    fun qrLabel(transaction: WalletTransaction): String = when (transaction.kind) {
        TransactionKind.Ecash -> "Ecash token"
        TransactionKind.Lightning -> "Payment request"
        TransactionKind.Onchain -> if (transaction.preimage == null) "Bitcoin address" else "On-chain request"
    }

    // Detail rows follow the iOS canon: Status first (monochrome), Date, then
    // conditional essentials — Fee when > 0, Mint always, Memo when present,
    // on-chain Address/Transaction ID. Type/Direction/Unit rows stay dropped
    // (the title names kind + direction; the unit is always sat here).
    fun detailFields(transaction: WalletTransaction): List<TransactionDetailField> =
        buildList {
            add(TransactionDetailField("Status", statusText(transaction)))
            add(TransactionDetailField("Date", formatDetailDate(transaction.dateEpochMillis)))
            if (transaction.fee > 0) add(TransactionDetailField("Fee", "${transaction.fee} sat"))
            transaction.mintUrl?.let { add(TransactionDetailField("Mint", mintHost(it))) }
            transaction.memo?.takeIf { it.isNotBlank() }?.let { add(TransactionDetailField("Memo", it)) }
            if (transaction.kind == TransactionKind.Onchain) {
                transaction.invoice?.let { add(TransactionDetailField("Address", it)) }
                transaction.preimage?.let { add(TransactionDetailField("Transaction ID", it)) }
            }
        }

    private fun formatDetailDate(epochMillis: Long): String =
        java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM,
            java.text.DateFormat.SHORT,
        ).format(java.util.Date(epochMillis))

    private fun mintHost(url: String): String =
        runCatching { URI.create(url).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: url
}
