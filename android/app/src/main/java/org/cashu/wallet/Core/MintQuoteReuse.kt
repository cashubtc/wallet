package org.cashu.wallet.Core

import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.MintQuoteState
import org.cashu.wallet.Models.PaymentMethodKind

internal fun selectReusableAmountlessOffer(
    quotes: List<MintQuoteInfo>,
    activeMintUrl: String,
): MintQuoteInfo? =
    quotes.firstOrNull { quote ->
        quote.paymentMethod == PaymentMethodKind.Bolt12 &&
            quote.mintUrl == activeMintUrl &&
            quote.amount == null &&
            quote.amountPaid == 0L &&
            quote.amountIssued == 0L
    }

internal fun selectReusableOnchainMintQuote(
    quotes: List<MintQuoteInfo>,
    activeMintUrl: String,
): MintQuoteInfo? =
    quotes.firstOrNull { quote ->
        quote.paymentMethod == PaymentMethodKind.Onchain &&
            quote.mintUrl == activeMintUrl &&
            quote.state != MintQuoteState.Issued
    }
