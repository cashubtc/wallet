package com.cashu.me.Core.CDK

import com.cashu.me.Core.NPCQuote
import org.cashudevkit.Amount
import org.cashudevkit.MintQuote
import org.cashudevkit.QuoteState
import org.cashudevkit.npubcashQuoteToMintQuote

internal fun npcMintQuote(quote: NPCQuote, mintUrl: String): MintQuote {
    val original = requireNotNull(quote.cdkQuote) { "Missing original npub.cash quote." }
    require(original.id == quote.id) { "npub.cash quote ID changed." }
    val converted = npubcashQuoteToMintQuote(original.copy(mintUrl = mintUrl))

    // CDK 0.18.2 treats PENDING as UNPAID and derives paid amounts solely
    // from paidAt. Preserve the existing Android state and accounting policy.
    val state = when (original.state?.uppercase()) {
        "PAID" -> QuoteState.PAID
        "PENDING" -> QuoteState.PENDING
        "ISSUED" -> QuoteState.ISSUED
        else -> QuoteState.UNPAID
    }
    val amount = converted.amount ?: Amount(0uL)
    return converted.copy(
        state = state,
        amountPaid = if (state == QuoteState.PAID || state == QuoteState.ISSUED) amount else Amount(0uL),
        amountIssued = if (state == QuoteState.ISSUED) amount else Amount(0uL),
    )
}
