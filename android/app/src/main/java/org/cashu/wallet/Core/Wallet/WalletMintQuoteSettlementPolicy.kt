package org.cashu.wallet.Core

import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.MintQuoteState
import org.cashu.wallet.Models.PaymentMethodKind

internal fun shouldAttemptMintQuoteSettlement(
    quote: MintQuoteInfo,
    allowPendingOnchainMintAttempt: Boolean,
): Boolean =
    quote.state == MintQuoteState.Paid ||
        quote.state == MintQuoteState.Issued ||
        (allowPendingOnchainMintAttempt && quote.paymentMethod == PaymentMethodKind.Onchain)

internal fun isMintQuoteAlreadySettledByGateway(quote: MintQuoteInfo): Boolean =
    quote.paymentMethod == PaymentMethodKind.Bolt12 &&
        quote.amountPaid > 0 &&
        quote.amountIssued >= quote.amountPaid
