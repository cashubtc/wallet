package com.cashu.me.ui.send

import com.cashu.me.Core.CashuPaymentRequestRoute
import com.cashu.me.Core.CashuPaymentRequestSummary
import com.cashu.me.Core.normalizedMintUrlForSelection
import com.cashu.me.Core.selectMintForCashuPaymentRequest
import com.cashu.me.Models.MintInfo

/** Select the mint shown by Send, including before an amount has been entered. */
internal fun cashuRequestDisplayMint(
    route: CashuPaymentRequestRoute?,
    request: CashuPaymentRequestSummary?,
    mints: List<MintInfo>,
    selectedMintUrl: String?,
    activeMintUrl: String?,
    fallbackMint: MintInfo?,
): MintInfo? = when (route) {
    is CashuPaymentRequestRoute.PayWithEcash -> route.mint
    is CashuPaymentRequestRoute.AcquireThenPay -> route.targetMintUrl?.let { target ->
        mints.firstOrNull { normalizedMintUrlForSelection(it.url) == normalizedMintUrlForSelection(target) }
    }
    CashuPaymentRequestRoute.MissingAmount -> request?.let {
        selectMintForCashuPaymentRequest(it, mints, selectedMintUrl, activeMintUrl, amountSats = null)
    }
    else -> fallbackMint
}
