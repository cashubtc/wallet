package com.cashu.me.Core

import com.cashu.me.Core.CDK.mintRemovalUrlsMatch
import com.cashu.me.Models.MintTransferLeg
import com.cashu.me.Models.MintTransferRecord
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction

/**
 * A transfer between two held mints is one event to the user, but CDK records
 * it as a Lightning payment at the source and a Lightning receipt at the
 * destination, with nothing connecting them. Given the wallet's own record of
 * the pair, show one row: the source payment, retitled, with the destination
 * receipt folded into its status. Without a record, or without the source
 * payment to anchor on, the CDK rows are left exactly as they are.
 */
internal object MintTransferProjection {
    fun project(
        transactions: List<WalletTransaction>,
        records: List<MintTransferRecord>,
        mintName: (String) -> String,
    ): List<WalletTransaction> {
        if (records.isEmpty()) return transactions
        val rows = transactions.toMutableList()
        val hidden = mutableSetOf<Int>()

        for (record in records) {
            if (record.state == MintTransferRecord.State.Draft) continue
            val meltQuoteId = record.meltQuoteId ?: continue
            val anchor = rows.indexOfFirst {
                it.type == TransactionType.Outgoing && it.transfer == null && it.quoteId == meltQuoteId &&
                    sameMint(it.mintUrl, record.sourceMintUrl)
            }
            if (anchor < 0) continue
            val arrivals = rows.indices.filter {
                rows[it].type == TransactionType.Incoming && rows[it].quoteId == record.mintQuoteId &&
                    sameMint(rows[it].mintUrl, record.destinationMintUrl)
            }
            val issued = record.state == MintTransferRecord.State.Completed ||
                arrivals.any { rows[it].status == TransactionStatus.Completed }

            val payment = rows[anchor]
            val (status, statusNote) = when {
                issued -> TransactionStatus.Completed to null
                // Paid at the source, not yet issued at the destination.
                payment.status == TransactionStatus.Completed ->
                    TransactionStatus.Pending to "Arriving at ${mintName(record.destinationMintUrl)}"
                payment.status == TransactionStatus.Pending -> TransactionStatus.Pending to "Payment in progress"
                else -> payment.status to null
            }
            rows[anchor] = payment.copy(
                transfer = MintTransferLeg(
                    recordId = record.id,
                    sourceMintUrl = record.sourceMintUrl,
                    destinationMintUrl = record.destinationMintUrl,
                    destinationQuoteId = record.mintQuoteId,
                ),
                // The invoice was the wallet's own: not a code to show or pay,
                // and its description is the destination mint's boilerplate.
                invoice = null,
                memo = null,
                status = status,
                statusNote = statusNote,
            )
            hidden += arrivals
        }
        return rows.filterIndexed { index, _ -> index !in hidden }
    }

    private fun sameMint(url: String?, other: String): Boolean =
        url != null && mintRemovalUrlsMatch(url, other)
}
