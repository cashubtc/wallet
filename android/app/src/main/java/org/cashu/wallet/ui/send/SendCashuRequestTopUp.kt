package org.cashu.wallet.ui.send

import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.PaymentMethodKind

internal suspend fun createExternalTopUpQuote(
    mintUrl: String,
    requestedAmountSats: Long,
    createMintQuoteForMint: suspend (
        mintUrl: String,
        amount: Long?,
        method: PaymentMethodKind,
        unit: String,
    ) -> MintQuoteInfo,
): MintQuoteInfo =
    createMintQuoteForMint(
        mintUrl,
        requestedAmountSats,
        PaymentMethodKind.Bolt11,
        "sat",
    )
