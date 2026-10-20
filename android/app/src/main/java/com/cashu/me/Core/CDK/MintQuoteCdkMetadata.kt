package com.cashu.me.Core.CDK

import com.cashu.me.Core.mintQuoteLocalStorageExpiry
import com.cashu.me.Models.PaymentMethodKind
import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.MintQuote as CdkMintQuote

/** CDK persists status/recovery updates before returning a pre-write version. */
internal suspend fun refreshPersistedMintQuote(
    check: suspend () -> CdkMintQuote,
    reload: suspend () -> CdkMintQuote?,
): CdkMintQuote {
    check()
    return reload() ?: throw CdkGatewayUnavailable("This receive request is no longer available.")
}

/** Keep quote ownership with CDK; a failed upsert must never delete the row. */
internal suspend fun persistMintQuoteMetadata(
    quote: CdkMintQuote,
    method: PaymentMethodKind,
    fallbackAmount: Long?,
    save: suspend (CdkMintQuote) -> Unit,
    reload: suspend () -> CdkMintQuote?,
): CdkMintQuote {
    val normalized = quote.withLocalMintQuoteMetadata(method, fallbackAmount)
    if (normalized == quote) return quote
    save(normalized)
    return reload() ?: throw CdkGatewayUnavailable("This receive request is no longer available.")
}

internal fun CdkMintQuote.withLocalMintQuoteMetadata(
    method: PaymentMethodKind,
    fallbackAmount: Long? = null,
): CdkMintQuote {
    val localExpiry = mintQuoteLocalStorageExpiry(expiry.toLong(), method)
    val localAmount = localMintQuoteAmount(method, fallbackAmount)
    return if (localExpiry == expiry.toLong() && localAmount == amount) {
        this
    } else {
        copy(expiry = localExpiry.toULong(), amount = localAmount)
    }
}

internal fun CdkMintQuote.hasUnissuedOnchainCredit(): Boolean =
    amountPaid.value > amountIssued.value

private fun CdkMintQuote.localMintQuoteAmount(
    method: PaymentMethodKind,
    fallbackAmount: Long?,
): CdkAmount? {
    if (method != PaymentMethodKind.Onchain || amount != null) return amount

    val resolvedAmount = amountPaid.value.takeIf { it > 0uL }
        ?: amountIssued.value.takeIf { it > 0uL }
        ?: fallbackAmount?.takeIf { it > 0 }?.toULong()
        ?: return null

    return CdkAmount(resolvedAmount)
}
