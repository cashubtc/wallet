package com.cashu.me.ui.send

import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.PaymentMethodKind

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
