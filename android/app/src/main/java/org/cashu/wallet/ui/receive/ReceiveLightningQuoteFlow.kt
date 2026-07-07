package org.cashu.wallet.ui.receive

import org.cashu.wallet.Core.CashuRequestStore
import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.MintQuoteState
import org.cashu.wallet.Models.PaymentMethodKind

internal suspend fun chooseReceiveLightningQuote(
    requestMethod: PaymentMethodKind,
    requestAmount: Long?,
    effectiveUnit: String,
    forceNew: Boolean,
    existingAmountlessOffer: suspend () -> MintQuoteInfo?,
    existingOnchainMintQuote: suspend () -> MintQuoteInfo?,
    createMintQuote: suspend (amount: Long?, method: PaymentMethodKind, unit: String) -> MintQuoteInfo,
): MintQuoteInfo =
    when {
        !forceNew && requestMethod == PaymentMethodKind.Bolt12 && requestAmount == null ->
            existingAmountlessOffer()
                ?: createMintQuote(null, PaymentMethodKind.Bolt12, effectiveUnit)

        !forceNew && requestMethod == PaymentMethodKind.Onchain ->
            existingOnchainMintQuote()
                ?: createMintQuote(null, PaymentMethodKind.Onchain, "sat")

        else -> createMintQuote(
            requestAmount,
            requestMethod,
            if (requestMethod == PaymentMethodKind.Onchain) "sat" else effectiveUnit,
        )
    }

internal fun persistReceiveLightningQuoteIntent(
    cashuRequestStore: CashuRequestStore,
    quote: MintQuoteInfo,
    fallbackMintUrl: String?,
) {
    cashuRequestStore.upsertQuoteIntent(
        id = quote.id,
        quoteId = quote.id,
        quoteKind = quote.paymentMethod.rawValue,
        amount = quote.amount,
        unit = quote.unit,
        mints = listOfNotNull(quote.mintUrl ?: fallbackMintUrl),
        memo = quote.paymentMethod.receiveQuoteIntentMemo,
        encoded = quote.request,
    )
}

internal interface ReceiveLightningSettlementGateway {
    suspend fun refreshBalance()
    suspend fun loadTransactions()
    suspend fun mintTokens(quoteId: String): Long
}

internal suspend fun settleReceiveLightningQuote(
    quote: MintQuoteInfo,
    settlementGateway: ReceiveLightningSettlementGateway,
    cashuRequestStore: CashuRequestStore,
): Result<Long> =
    runCatching {
        val amount = if (quote.state == MintQuoteState.Issued && quote.amountIssued > 0) {
            settlementGateway.refreshBalance()
            settlementGateway.loadTransactions()
            quote.amountIssued
        } else {
            settlementGateway.mintTokens(quote.id)
        }
        if (amount > 0) {
            cashuRequestStore.attachPaymentByQuoteId(
                quoteId = quote.id,
                transactionId = quote.id,
                amount = amount,
            )
        }
        amount
    }

internal val PaymentMethodKind.receiveQuoteIntentMemo: String
    get() = when (this) {
        PaymentMethodKind.Bolt11 -> "Lightning invoice"
        PaymentMethodKind.Bolt12 -> "Reusable invoice"
        PaymentMethodKind.Onchain -> "Bitcoin address"
    }
