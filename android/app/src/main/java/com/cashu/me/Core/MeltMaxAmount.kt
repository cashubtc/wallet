package com.cashu.me.Core

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
        val total = requiredTotal(amount)
        if (total <= balance) return amount
        amount = (amount - (total - balance)).coerceAtLeast(0L)
    }
    return null
}
