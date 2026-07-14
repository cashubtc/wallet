package com.cashu.me.Core

import com.cashu.me.Core.CDK.CdkWalletGateway
import com.cashu.me.Models.ClaimedToken
import com.cashu.me.Models.MeltPaymentResult
import com.cashu.me.Models.MeltSettlement
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.PendingReceiveToken
import com.cashu.me.Models.PendingToken
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction

internal data class WalletTransactionLoadResult(
    val transactions: List<WalletTransaction>,
    val pendingTokens: List<PendingToken>,
    val pendingReceiveTokens: List<PendingReceiveToken>,
    val claimedTokens: List<ClaimedToken>,
)

internal data class TokenHistoryMutation(
    val pendingTokens: List<PendingToken>,
    val claimedTokens: List<ClaimedToken>,
)

internal class WalletTransactionLoader(
    private val walletStore: WalletStore,
    private val gateway: CdkWalletGateway,
) {
    suspend fun load(mints: List<MintInfo>): WalletTransactionLoadResult {
        val mintUrls = mints.map { it.url }
        val trackedMintUrls = mintUrls.toSet()
        val preimages = walletStore.loadPaymentPreimages()
        val meltFees = walletStore.loadMeltQuoteFees()
        val pendingTokens = walletStore.loadPendingTokens()
        val pendingReceiveTokens = walletStore.loadPendingReceiveTokens()
        val claimedTokens = walletStore.loadClaimedTokens()
        val remote = runCatching { gateway.listTransactions(transactionUnitsByMint(mints)) }.getOrDefault(emptyList())
            .map { it.withStoredMeltMetadata(preimages, meltFees) }
        val completedQuoteIds = remote.mapNotNull { it.quoteId }.toSet()
        val mintQuoteTimestamps = walletStore.loadMintQuoteTimestamps().toMutableMap()
        val unissuedMintQuotes = runCatching { gateway.listUnissuedMintQuotes() }
            .getOrDefault(emptyList())
        val reusableBolt12QuoteIds = unissuedMintQuotes
            .asSequence()
            .filter { it.paymentMethod == PaymentMethodKind.Bolt12 }
            .mapTo(mutableSetOf()) { it.id }
        val pendingQuoteTransactions = observePendingOnchainMintQuotes(
            pendingMintQuoteTransactions(
                quotes = unissuedMintQuotes,
                trackedMintUrls = trackedMintUrls,
                completedQuoteIds = completedQuoteIds,
                timestamps = mintQuoteTimestamps,
                nowEpochMillis = System.currentTimeMillis(),
            ).map { it.withStoredMeltMetadata(preimages, meltFees) },
        )
        val storedMeltTransactions = runCatching { gateway.listMeltQuotes() }
            .getOrDefault(emptyList())
            .let { quotes ->
                storedMeltQuoteTransactions(
                    quotes = quotes,
                    trackedMintUrls = trackedMintUrls,
                    completedQuoteIds = completedQuoteIds,
                    timestamps = mintQuoteTimestamps,
                    nowEpochMillis = System.currentTimeMillis(),
                    preimages = preimages,
                    fees = meltFees,
                )
            }
        val receiveTokenTransactions = pendingReceiveTokenTransactions(pendingReceiveTokens)
        val cached = walletStore.loadTransactions()
            .filterNot { it.isPendingToken }
            // Once CDK reports individual BOLT12 payments, discard the old
            // synthetic quote row. Otherwise it would be counted alongside the
            // real payments when the reusable request aggregates its history.
            .filterNot { transaction ->
                transaction.quoteId in reusableBolt12QuoteIds &&
                    transaction.id == transaction.quoteId &&
                    transaction.quoteId in completedQuoteIds
            }
            .map { it.withStoredMeltMetadata(preimages, meltFees) }
        // Dedupe remote/cached first so sent tokens fold into the surviving CDK
        // row, then merge — otherwise each send lists twice (CDK row + local
        // pending-token row).
        val merged = mergeSentTokenTransactions(
            transactions = deduplicateWalletTransactions(
                transactions = remote + pendingQuoteTransactions + storedMeltTransactions + receiveTokenTransactions + cached,
                reusableBolt12QuoteIds = reusableBolt12QuoteIds,
            ),
            pendingTokens = pendingTokens,
            claimedTokens = claimedTokens,
        ).sortedByDescending { it.dateEpochMillis }
        walletStore.saveTransactions(merged)
        walletStore.saveMintQuoteTimestamps(pruneMintQuoteTimestamps(merged, mintQuoteTimestamps))
        return WalletTransactionLoadResult(
            transactions = merged,
            pendingTokens = pendingTokens,
            pendingReceiveTokens = pendingReceiveTokens,
            claimedTokens = claimedTokens,
        )
    }

    fun markPendingTokenClaimed(pendingToken: PendingToken): TokenHistoryMutation {
        val pending = walletStore.loadPendingTokens().filterNot { it.tokenId == pendingToken.tokenId }
        val claimedToken = ClaimedToken(
            tokenId = pendingToken.tokenId,
            token = pendingToken.token,
            amount = pendingToken.amount,
            fee = pendingToken.fee,
            dateEpochMillis = pendingToken.dateEpochMillis,
            mintUrl = pendingToken.mintUrl,
            memo = pendingToken.memo,
            claimedDateEpochMillis = System.currentTimeMillis(),
            unit = pendingToken.unit,
        )
        val claimed = walletStore.loadClaimedTokens()
            .filterNot { it.tokenId == pendingToken.tokenId } + claimedToken
        walletStore.savePendingTokens(pending)
        walletStore.saveClaimedTokens(claimed)
        return TokenHistoryMutation(pendingTokens = pending, claimedTokens = claimed)
    }

    fun saveMeltPaymentMetadata(
        quoteId: String,
        result: MeltPaymentResult,
        persistFee: Boolean = result.settlement == MeltSettlement.Settled,
    ) {
        result.preimage?.let { preimage ->
            walletStore.savePaymentPreimages(walletStore.loadPaymentPreimages() + (quoteId to preimage))
        }
        if (persistFee) {
            walletStore.saveMeltQuoteFees(walletStore.loadMeltQuoteFees() + (quoteId to result.feePaid))
        }

        val current = walletStore.loadTransactions()
        val existing = current.firstOrNull { it.quoteId == quoteId || it.id == quoteId }
        val transaction = WalletTransaction(
            id = existing?.id ?: quoteId,
            amount = result.amount,
            type = TransactionType.Outgoing,
            kind = when (result.paymentMethod) {
                PaymentMethodKind.Onchain -> TransactionKind.Onchain
                else -> TransactionKind.Lightning
            },
            dateEpochMillis = existing?.dateEpochMillis ?: System.currentTimeMillis(),
            memo = existing?.memo,
            status = if (result.settlement == MeltSettlement.Pending) {
                TransactionStatus.Pending
            } else {
                TransactionStatus.Completed
            },
            mintUrl = result.mintUrl,
            preimage = result.preimage ?: existing?.preimage,
            invoice = result.request ?: existing?.invoice,
            fee = if (persistFee) result.feePaid else existing?.fee ?: result.feePaid,
            unit = existing?.unit ?: "sat",
            quoteId = quoteId,
        )
        walletStore.saveTransactions(
            listOf(transaction) + current.filterNot { it.id == transaction.id || it.quoteId == quoteId },
        )
    }

    private suspend fun observePendingOnchainMintQuotes(
        transactions: List<WalletTransaction>,
    ): List<WalletTransaction> =
        transactions.map { transaction ->
            if (
                transaction.type != TransactionType.Incoming ||
                transaction.kind != TransactionKind.Onchain ||
                transaction.invoice == null
            ) {
                return@map transaction
            }

            val observation = OnchainExplorer.observePayment(
                address = transaction.invoice,
                mintUrl = transaction.mintUrl,
                expectedAmount = transaction.amount,
                createdAfterEpochMillis = transaction.dateEpochMillis,
            )

            if (observation != null) {
                val key = transaction.quoteId ?: transaction.id
                val currentPreimages = walletStore.loadPaymentPreimages()
                if (currentPreimages[key] != observation.txid) {
                    walletStore.savePaymentPreimages(currentPreimages + (key to observation.txid))
                }
                transaction.copy(
                    preimage = observation.txid,
                    statusNote = observation.statusText,
                )
            } else if (transaction.preimage != null) {
                transaction.copy(statusNote = transaction.statusNote ?: "Payment detected on-chain")
            } else {
                transaction
            }
        }
}

/**
 * A normal mint quote represents one history entry, so quote id is its stable
 * merge key. An amountless BOLT12 quote represents an open offer and can have
 * many incoming payments; retain each of those by transaction id instead.
 */
internal fun deduplicateWalletTransactions(
    transactions: List<WalletTransaction>,
    reusableBolt12QuoteIds: Set<String>,
): List<WalletTransaction> = transactions.distinctBy { transaction ->
    val keepsIndividualPayment = transaction.type == TransactionType.Incoming &&
        transaction.kind == TransactionKind.Lightning &&
        transaction.quoteId in reusableBolt12QuoteIds
    val identity = if (keepsIndividualPayment) transaction.id else transaction.quoteId ?: transaction.id
    "${transaction.mintUrl.orEmpty()}|$identity"
}

/** CDK stores an independent wallet per (mint, unit), including transaction history. */
internal fun transactionUnitsByMint(mints: List<MintInfo>): Map<String, List<String>> =
    mints.associate { mint ->
        val units = buildList {
            add("sat")
            addAll(mint.units)
        }.map(String::trim)
            .filter(String::isNotEmpty)
            .distinctBy(String::lowercase)
        mint.url to units
    }

internal fun WalletTransaction.withStoredMeltMetadata(
    preimages: Map<String, String>,
    meltFees: Map<String, Long>,
): WalletTransaction {
    val key = quoteId ?: id
    return copy(
        preimage = preimage ?: preimages[key],
        fee = meltFees[key] ?: fee,
    )
}
