package org.cashu.wallet.Core

import org.cashu.wallet.Core.CDK.CdkWalletGateway
import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.MintQuoteState
import org.cashu.wallet.Models.PaymentMethodKind

internal data class MintQuoteSyncResult(
    val minted: Boolean,
    val quote: MintQuoteInfo? = null,
    val eventAmount: Long = 0,
)

internal class WalletMintQuoteSyncService(
    private val gateway: CdkWalletGateway,
    private val walletStore: WalletStore,
) {
    private val mintQuoteSyncsInFlight = mutableSetOf<String>()

    suspend fun syncPendingMintQuote(
        quoteId: String,
        allowPendingOnchainMintAttempt: Boolean,
    ): MintQuoteSyncResult {
        if (!mintQuoteSyncsInFlight.add(quoteId)) return MintQuoteSyncResult(minted = false)
        return try {
            val updatedQuote = gateway.checkMintQuote(quoteId).also { rememberMintQuoteTimestamp(it.id) }
            val shouldAttemptMint = updatedQuote.state == MintQuoteState.Paid ||
                updatedQuote.state == MintQuoteState.Issued ||
                (allowPendingOnchainMintAttempt && updatedQuote.paymentMethod == PaymentMethodKind.Onchain)
            if (!shouldAttemptMint) return MintQuoteSyncResult(minted = false, quote = updatedQuote)

            if (updatedQuote.paymentMethod == PaymentMethodKind.Bolt12 &&
                updatedQuote.amountPaid > 0 &&
                updatedQuote.amountIssued >= updatedQuote.amountPaid
            ) {
                return MintQuoteSyncResult(minted = false, quote = updatedQuote)
            }

            runCatching { gateway.mintTokens(quoteId) }
                .fold(
                    onSuccess = { amount ->
                        MintQuoteSyncResult(minted = true, quote = updatedQuote, eventAmount = amount)
                    },
                    onFailure = { error ->
                        if (isAlreadyIssuedMintError(error)) {
                            MintQuoteSyncResult(minted = true, quote = updatedQuote)
                        } else if (
                            updatedQuote.paymentMethod == PaymentMethodKind.Onchain &&
                            updatedQuote.state == MintQuoteState.Pending
                        ) {
                            MintQuoteSyncResult(minted = false, quote = updatedQuote)
                        } else {
                            AppLogger.wallet.error("Failed to mint pending quote $quoteId", error)
                            MintQuoteSyncResult(minted = false, quote = updatedQuote)
                        }
                    },
                )
        } catch (error: Throwable) {
            if (!isMissingQuoteError(error)) {
                AppLogger.wallet.error("Failed to refresh pending quote $quoteId", error)
            }
            MintQuoteSyncResult(minted = false)
        } finally {
            mintQuoteSyncsInFlight.remove(quoteId)
        }
    }

    fun rememberMintQuoteTimestamp(quoteId: String) {
        val current = walletStore.loadMintQuoteTimestamps()
        if (quoteId !in current) {
            walletStore.saveMintQuoteTimestamps(current + (quoteId to System.currentTimeMillis()))
        }
    }

    fun isAlreadyIssuedMintError(error: Throwable): Boolean {
        val message = "${error.message.orEmpty()} ${error}".lowercase()
        if (
            message.contains("already being minted") ||
            message.contains("not issued") ||
            message.contains("not yet") ||
            message.contains("unissued")
        ) {
            return false
        }
        return message.contains("already issued") ||
            message.contains("already minted") ||
            message.contains("quote is issued") ||
            message.contains("state=issued") ||
            message.contains("tokens already issued")
    }

    private fun isMissingQuoteError(error: Throwable): Boolean {
        val message = "${error.message.orEmpty()} ${error}".lowercase()
        return message.contains("not found") ||
            message.contains("no stored mint quote") ||
            message.contains("missing quote")
    }
}
