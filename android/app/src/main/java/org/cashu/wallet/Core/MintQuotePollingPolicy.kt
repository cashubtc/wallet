package org.cashu.wallet.Core

import org.cashu.wallet.Models.MintQuoteState
import org.cashu.wallet.Models.PaymentMethodKind

internal fun shouldPollMintQuote(
    state: MintQuoteState,
    expiryEpochSeconds: Long?,
    nowEpochSeconds: Long,
): Boolean {
    if (state == MintQuoteState.Paid || state == MintQuoteState.Issued || state == MintQuoteState.Failed) {
        return false
    }
    val expiry = expiryEpochSeconds?.takeIf { it > 0 } ?: return true
    return nowEpochSeconds < expiry
}

internal fun initialMintQuotePollIntervalMillis(method: PaymentMethodKind): Long =
    when (method) {
        PaymentMethodKind.Bolt11 -> 5_000L
        PaymentMethodKind.Bolt12 -> 10_000L
        PaymentMethodKind.Onchain -> 30_000L
    }

internal fun nextMintQuotePollIntervalMillis(
    currentIntervalMillis: Long,
    method: PaymentMethodKind,
): Long =
    (currentIntervalMillis + 1_000L).coerceAtMost(
        if (method == PaymentMethodKind.Bolt11) 15_000L else 30_000L,
    )
