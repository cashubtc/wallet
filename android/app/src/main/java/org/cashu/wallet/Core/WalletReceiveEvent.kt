package org.cashu.wallet.Core

import java.util.concurrent.atomic.AtomicLong

enum class WalletReceiveSource {
    Ecash,
    Lightning,
    Onchain,
    CashuRequest,
    NPC,
}

data class WalletReceiveEvent(
    val id: Long,
    val amount: Long,
    val unit: String,
    val source: WalletReceiveSource,
)

internal object WalletReceiveEventIds {
    private val counter = AtomicLong()
    fun next(): Long = counter.incrementAndGet()
}

fun WalletReceiveEvent.showsHomeSatDelta(): Boolean =
    amount > 0 && unit.equals("sat", ignoreCase = true)
