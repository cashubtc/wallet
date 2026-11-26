package com.cashu.me.Core

import com.cashu.me.Models.PendingReceiveToken
import com.cashu.me.Models.WalletTransaction

internal fun nextTransactionUpdateVersion(current: Long): Long =
    if (current == Long.MAX_VALUE) 1 else current + 1

/**
 * Applies a fresh transaction load to [WalletState]. Always increments
 * [WalletState.transactionUpdateVersion] on the initial load (when the version
 * is still `0`, so history readiness signals such as `HomeArrival` flip once)
 * and whenever `transactions` or `pendingReceiveTokens` actually changed.
 * Identical polling reloads return `this` unchanged so `StateFlow` emits
 * nothing.
 */
internal fun WalletState.withLoadedTransactions(
    loadedTransactions: List<WalletTransaction>,
    loadedPendingReceiveTokens: List<PendingReceiveToken>,
): WalletState {
    val changed = transactions != loadedTransactions ||
        pendingReceiveTokens != loadedPendingReceiveTokens
    if (transactionUpdateVersion != 0L && !changed) return this
    return copy(
        transactions = loadedTransactions,
        pendingReceiveTokens = loadedPendingReceiveTokens,
        transactionUpdateVersion = nextTransactionUpdateVersion(transactionUpdateVersion),
    )
}

