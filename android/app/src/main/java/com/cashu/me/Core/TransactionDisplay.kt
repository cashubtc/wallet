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
     * The QR hero appears only while the artifact remains useful. Ecash is
     * shareable only for an unclaimed outgoing send; an incoming pending token
     * is money to claim, not a payment code. One-shot Lightning invoices retire
     * after settlement, reusable BOLT12 offers stay live, and on-chain addresses
     * remain fundable after a payment and therefore always keep their QR.
     */
    fun showsQr(transaction: WalletTransaction): Boolean = when (transaction.kind) {
        TransactionKind.Ecash ->
            transaction.token != null &&
                transaction.type == TransactionType.Outgoing &&
                transaction.status == TransactionStatus.Pending
        TransactionKind.Lightning ->
            transaction.invoice != null &&
                (transaction.status == TransactionStatus.Pending ||
                    transaction.invoice.startsWith("lno", ignoreCase = true))
        TransactionKind.Onchain -> transaction.invoice != null
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
        TransactionKind.Onchain -> "Bitcoin address"
    }

    // Detail rows follow the iOS canon: Status first (monochrome), Date, then
    // conditional essentials — Fee when > 0, Mint, Lightning payment proof,
    // or on-chain Address/Transaction ID. Type/Direction/Unit rows stay dropped
    // (the title names kind + direction; the unit is always sat here).
    fun detailFields(transaction: WalletTransaction): List<TransactionDetailField> =
        buildList {
            add(TransactionDetailField("Status", statusText(transaction)))
            add(TransactionDetailField("Date", formatDetailDate(transaction.dateEpochMillis)))
            if (transaction.fee > 0) add(TransactionDetailField("Fee", "${transaction.fee} sat"))
            transaction.mintUrl?.let { add(TransactionDetailField("Mint", mintHost(it))) }
            if (transaction.kind == TransactionKind.Onchain) {
                transaction.invoice?.let { add(TransactionDetailField("Address", it)) }
                transaction.preimage?.let { add(TransactionDetailField("Transaction ID", it)) }
            } else {
                transaction.preimage?.let { add(TransactionDetailField("Payment Proof", it)) }
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
