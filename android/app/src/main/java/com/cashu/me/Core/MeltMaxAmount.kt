package com.cashu.me.Core

import com.cashu.me.Core.CDK.LightningAddressResolutionException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * What Max means for a Lightning or on-chain send: the largest amount a mint
 * can pay from [balance] once its melt fee reserve is added. [requiredTotal]
 * quotes the mint and returns amount + fee reserve. The reserve usually tracks
 * the amount (a percentage with a floor), so each round steps down by the
 * overshoot and a second quote normally settles it. Null when nothing fits.
 * Ecash Max stays the gross balance: its fees are the receiver's.
 * iOS parity: `largestPayableMeltAmount` in LightningService.swift.
 */
suspend fun largestPayableMeltAmount(
    balance: Long,
    rounds: Int = 4,
    requiredTotal: suspend (Long) -> Long,
): Long? {
    var amount = balance
    repeat(rounds) {
        if (amount <= 0L) return null
        currentCoroutineContext().ensureActive()
        val total = try {
            requiredTotal(amount)
        } catch (limit: LightningAddressResolutionException.AmountOutOfRange) {
            val maximum = limit.maxMsat / 1_000
            if (maximum <= 0 || maximum >= amount) throw limit
            amount = maximum
            return@repeat
        }
        currentCoroutineContext().ensureActive()
        if (total <= balance) return amount
        amount = (amount - (total - balance)).coerceAtLeast(0L)
    }
    if (amount <= 0L) return null
    error("Couldn’t calculate the maximum amount. Try again or enter an amount.")
}
