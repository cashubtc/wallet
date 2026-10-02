package com.cashu.me.Core

import java.time.Instant
import java.time.temporal.ChronoUnit
import com.cashu.me.Core.Protocols.CurrencyRegistry
import com.cashu.me.Models.CashuRequest
import com.cashu.me.Models.MeltQuoteInfo
import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction

data class TechnicalDetailRow(
    val label: String,
    // Display form; opaque references use the decoder's 8…6 short form.
    val value: String,
    // Untruncated value for tap-to-copy and Copy all.
    val fullValue: String = value,
    val copyable: Boolean = false,
)

data class TechnicalDetailSection(
    val title: String,
    val rows: List<TechnicalDetailRow>,
)

/**
 * The identifiers behind one transaction — what a developer needs to trace a
 * payment through CDK and the mint (quote ID, request, proof, saga). Built from
 * the row plus local quote snapshots only, so opening it never touches the
 * network. The domain quote models carry no NUT-20 secret key, and a spendable
 * token is never included, so Copy all is safe to paste into a bug report.
 * iOS `TransactionTechnicalDetails` parity: same sections, labels and order.
 */
object TransactionTechnicalDetails {
    fun sections(
        transaction: WalletTransaction,
        mintQuote: MintQuoteInfo? = null,
        meltQuote: MeltQuoteInfo? = null,
    ): List<TechnicalDetailSection> = buildList {
        val unit = transaction.unit
        add(
            TechnicalDetailSection(
                "Transaction",
                transactionRows(transaction, quoteMethod = mintQuote?.paymentMethod ?: meltQuote?.paymentMethod),
            ),
        )

        transaction.quoteId?.let { quoteId ->
            val rows = buildList {
                add(reference("Quote ID", quoteId))
                add(plain("Type", if (transaction.type == TransactionType.Incoming) "Mint quote" else "Melt quote"))
                (mintQuote?.request ?: meltQuote?.request ?: transaction.invoice)
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { add(reference("Request", it)) }
                if (mintQuote != null) {
                    addAll(mintQuoteRows(mintQuote, unit))
                } else if (meltQuote != null) {
                    add(plain("State", meltQuote.state.name))
                    add(plain("Quote amount", nativeAmount(meltQuote.amount, unit)))
                    add(plain("Fee reserve", nativeAmount(meltQuote.feeReserve, unit)))
                    add(expiryRow(meltQuote.expiryEpochSeconds))
                }
            }
            add(TechnicalDetailSection("Quote", rows))
        }

        val proof = transaction.preimage?.takeIf { it.isNotEmpty() }
            ?: meltQuote?.paymentProof?.takeIf { it.isNotEmpty() }
        if (proof != null) {
            when (transaction.kind) {
                TransactionKind.Lightning ->
                    add(TechnicalDetailSection("Payment", listOf(reference("Payment Proof", proof))))
                TransactionKind.Onchain ->
                    add(TechnicalDetailSection("Payment", listOf(reference("Transaction ID", proof))))
                TransactionKind.Ecash -> Unit
            }
        }
    }

    /** Receive-artifact details retain the offer's identity and payment breakdown. */
    fun requestSections(request: CashuRequest, mintQuote: MintQuoteInfo? = null): List<TechnicalDetailSection> = buildList {
        val method = when (request.quoteKind?.lowercase()) {
            "bolt11" -> "Lightning (BOLT11)"
            "bolt12" -> "Lightning (BOLT12)"
            "onchain" -> "On-chain"
            else -> "Ecash"
        }
        add(TechnicalDetailSection("Request", buildList {
            add(reference("ID", request.id))
            add(plain("Method", method))
            add(dateRow("Created", request.createdAtEpochMillis))
            add(plain("Amount", request.amount?.let { nativeAmount(it, request.unit) } ?: "Any"))
            add(plain("Total received", nativeAmount(request.totalReceived, request.unit)))
            request.mints.forEachIndexed { index, mint ->
                add(TechnicalDetailRow(if (request.mints.size == 1) "Mint" else "Mint ${index + 1}", mint, copyable = true))
            }
            if (request.quoteId == null) add(reference("Request", request.encoded))
        }))
        request.quoteId?.let { quoteID ->
            add(TechnicalDetailSection("Quote", buildList {
                add(reference("Quote ID", quoteID))
                add(plain("Type", "Mint quote"))
                add(reference("Request", mintQuote?.request ?: request.encoded))
                if (mintQuote != null) addAll(mintQuoteRows(mintQuote, request.unit))
            }))
        }
        request.receivedPayments.forEachIndexed { index, payment ->
            add(TechnicalDetailSection("Payment ${index + 1}", listOf(
                reference("Transaction ID", payment.transactionId),
                plain("Amount", nativeAmount(payment.amount, request.unit)),
                dateRow("Date", payment.receivedAtEpochMillis),
            )))
        }
    }

    /** Navigate to the same resolved txid that the Payment section displays. */
    fun explorerUrl(transaction: WalletTransaction, sections: List<TechnicalDetailSection>): String? {
        if (transaction.kind != TransactionKind.Onchain) return null
        val txid = sections.firstOrNull { it.title == "Payment" }?.rows
            ?.firstOrNull { it.label == "Transaction ID" }?.fullValue?.takeIf { it.isNotEmpty() }
        return if (txid != null) {
            OnchainExplorer.transactionWebUrl(txid, transaction.invoice, transaction.mintUrl)
        } else {
            transaction.invoice?.let { OnchainExplorer.addressWebUrl(it, transaction.mintUrl) }
        }
    }

    private fun mintQuoteRows(quote: MintQuoteInfo, unit: String): List<TechnicalDetailRow> = buildList {
        add(plain("State", quote.state.name))
        add(plain("Amount paid", nativeAmount(quote.amountPaid, unit)))
        add(plain("Amount issued", nativeAmount(quote.amountIssued, unit)))
        add(expiryRow(quote.expiryEpochSeconds))
        if (quote.updatedAtEpochSeconds > 0) add(dateRow("Last updated", quote.updatedAtEpochSeconds * 1000))
    }

    /** Plain-text dump for bug reports: each section title, then its
     *  `Label: value` lines, with untruncated values. */
    fun copyAllText(sections: List<TechnicalDetailSection>): String =
        sections.joinToString("\n\n") { section ->
            (listOf(section.title) + section.rows.map { "${it.label}: ${it.fullValue}" }).joinToString("\n")
        }

    /** The tap-to-copy toast for a row (Copy all reads "Copied details"). */
    fun copyConfirmation(label: String): String = when (label) {
        "ID" -> "Copied ID"
        "Mint" -> "Copied mint URL"
        "Payment Proof" -> "Copied payment proof"
        else -> "Copied ${label.replaceFirstChar { it.lowercase() }}"
    }

    fun methodLabel(transaction: WalletTransaction, quoteMethod: PaymentMethodKind? = null): String =
        when (transaction.kind) {
            TransactionKind.Ecash -> "Ecash"
            TransactionKind.Onchain -> "On-chain"
            TransactionKind.Lightning -> {
                val request = transaction.invoice?.lowercase().orEmpty()
                val inferred = when {
                    request.startsWith("lno") -> PaymentMethodKind.Bolt12
                    request.startsWith("ln") -> PaymentMethodKind.Bolt11
                    else -> null
                }
                when (transaction.paymentMethod ?: quoteMethod ?: inferred) {
                    PaymentMethodKind.Bolt11 -> "Lightning (BOLT11)"
                    PaymentMethodKind.Bolt12 -> "Lightning (BOLT12)"
                    PaymentMethodKind.Onchain, null -> "Lightning"
                }
            }
        }

    private fun transactionRows(
        transaction: WalletTransaction,
        quoteMethod: PaymentMethodKind?,
    ): List<TechnicalDetailRow> = buildList {
        // App-synthesized rows (a quote awaiting payment, a held token) have no
        // CDK transaction yet; their id is the quote/token id shown elsewhere.
        if (!transaction.isPendingReceiveToken && transaction.id != transaction.quoteId) {
            add(reference("ID", transaction.id))
        }
        add(plain("Method", methodLabel(transaction, quoteMethod)))
        add(plain("Direction", if (transaction.type == TransactionType.Incoming) "Incoming" else "Outgoing"))
        add(plain("Status", transaction.status.displayText))
        transaction.statusNote
            ?.takeIf { transaction.status == TransactionStatus.Pending && it.isNotEmpty() }
            ?.let { add(plain("Status detail", it)) }
        add(dateRow("Date", transaction.dateEpochMillis))
        add(plain("Amount", nativeAmount(transaction.amount, transaction.unit)))
        add(plain("Fee", nativeAmount(transaction.fee, transaction.unit)))
        // A URL stays legible; the row wraps instead of cutting it 8…6.
        transaction.mintUrl?.takeIf { it.isNotEmpty() }?.let {
            add(TechnicalDetailRow("Mint", it, it, copyable = true))
        }
        transaction.sagaId?.takeIf { it.isNotEmpty() }?.let { add(reference("Saga ID", it)) }
    }

    private fun plain(label: String, value: String) = TechnicalDetailRow(label, value)

    private fun reference(label: String, value: String) =
        TechnicalDetailRow(label, TransactionDisplay.middleTruncated(value), value, copyable = true)

    // Localized with seconds on screen; ISO 8601 UTC in Copy all, so a pasted
    // report lines up with mint and CDK logs regardless of the reader's locale.
    private fun dateRow(label: String, epochMillis: Long) = TechnicalDetailRow(
        label = label,
        value = java.text.DateFormat.getDateTimeInstance(
            java.text.DateFormat.MEDIUM,
            java.text.DateFormat.MEDIUM,
        ).format(java.util.Date(epochMillis)),
        fullValue = Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS).toString(),
    )

    // Null/0 is CDK's "no expiry"; reusable BOLT12 offers can carry a
    // far-future sentinel (9999-12-31) for the same meaning.
    private fun expiryRow(expiryEpochSeconds: Long?): TechnicalDetailRow {
        val expiry = expiryEpochSeconds ?: 0
        if (expiry <= 0 || expiry >= 253_402_300_799) return plain("Expiry", "Never")
        return dateRow("Expiry", expiry * 1000)
    }

    // Grouped like every other sat amount in the app, so a deposit reads the
    // same here as on its History row.
    private fun nativeAmount(amount: Long, unit: String): String =
        if (CurrencyRegistry.isSatoshiUnit(unit)) {
            AmountFormatter().formatSats(amount, includeUnit = true, useBitcoinSymbol = false)
        } else {
            TransactionDisplay.formatNativeAmount(amount, unit)
        }
}
