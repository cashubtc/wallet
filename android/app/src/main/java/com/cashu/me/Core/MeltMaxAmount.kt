package com.cashu.me.Core

import com.cashu.me.Models.MeltQuoteInfo
import com.cashu.me.Models.MeltQuoteState
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Winning Send Max result: the largest amount the mint can pay from its
 * balance paired with the [MeltQuoteInfo] that proved it, so Confirm can reuse
 * the quote instead of creating a duplicate one.
 */
data class PayableMeltQuote(
    val amount: Long,
    val quote: MeltQuoteInfo,
)

/**
 * Winning Send Max melt quote held between the Amount and Confirm steps so
 * tapping Continue reuses it when still unexpired and matching the selected
 * destination, amount, and mint.
 */
data class CachedMaxMeltQuote(
    val request: String,
    val amount: Long,
    val mintUrl: String,
    val quote: MeltQuoteInfo,
) {
    fun matches(
        request: String,
        amount: Long,
        mintUrl: String?,
        nowEpochSeconds: Long = System.currentTimeMillis() / 1000,
    ): Boolean {
        if (mintUrl == null) return false
        val normalizedTarget = normalizedMintUrlForSelection(mintUrl)
        if (this.request != request ||
            this.amount != amount ||
            quote.amount != amount ||
            quote.state != MeltQuoteState.Unpaid ||
            normalizedMintUrlForSelection(this.mintUrl) != normalizedTarget ||
            normalizedMintUrlForSelection(quote.mintUrl) != normalizedTarget
        ) {
            return false
        }
        val expiry = quote.expiryEpochSeconds
        return expiry == null || expiry <= 0L || nowEpochSeconds <= expiry
    }
}

/**
 * What Max means for a Lightning or on-chain send: the largest amount a mint
 * can pay from [balance] once its melt fee reserve is added, paired with the
 * winning [MeltQuoteInfo] so Confirm can reuse it without a second round-trip.
 * [quoteForAmount] quotes the mint for a candidate amount; its
 * [MeltQuoteInfo.totalAmount] is amount + fee reserve. The reserve usually
 * tracks the amount (a percentage with a floor), so each round steps down by
 * the overshoot and a second quote normally settles it. Null when nothing fits.
 * Ecash Max stays the gross balance: its fees are the receiver's.
 * iOS parity: `largestPayableMeltAmount` in LightningService.swift.
 */
suspend fun largestPayableMeltAmount(
    balance: Long,
    rounds: Int = 4,
    quoteForAmount: suspend (Long) -> MeltQuoteInfo,
): PayableMeltQuote? {
    var amount = balance
    repeat(rounds) {
        if (amount <= 0L) return null
        currentCoroutineContext().ensureActive()
        val quote = quoteForAmount(amount)
        currentCoroutineContext().ensureActive()
        if (quote.amount < 0L || quote.feeReserve < 0L || quote.amount > Long.MAX_VALUE - quote.feeReserve) {
            error("The mint returned an invalid payment amount or fee.")
        }
        val total = quote.totalAmount
        if (total <= balance) return PayableMeltQuote(amount = amount, quote = quote)
        amount = (amount - (total - balance)).coerceAtLeast(0L)
    }
    if (amount <= 0L) return null
    error("Couldn’t calculate the maximum amount. Try again or enter an amount.")
}
