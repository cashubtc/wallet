package com.cashu.me.Core.CDK

import com.cashu.me.Core.toDomainUnit
import com.cashu.me.Models.ReceiveTokenPreview
import com.cashu.me.Models.TokenInfo
import org.cashudevkit.TokenData

internal fun TokenData.toReceiveTokenPreview(): ReceiveTokenPreview {
    val fee = requireNotNull(redeemFee) { "The mint's receive fee is not available. Try again." }.value
    require(fee <= Long.MAX_VALUE.toULong() && value.value <= Long.MAX_VALUE.toULong()) {
        "The mint returned an invalid receive amount or fee."
    }
    return ReceiveTokenPreview(
        info = TokenInfo(value.value.toLong(), mintUrl.url, unit.toDomainUnit(), memo, proofs.size),
        fee = fee.toLong(),
    )
}
