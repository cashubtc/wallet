package com.cashu.me.Core

internal object WalletStartupPolicy {
    const val keysetRefreshIntervalMillis = 60 * 60 * 1_000L

    fun shouldRefreshKeysets(lastRefreshEpochMillis: Long?, nowEpochMillis: Long): Boolean {
        val lastRefresh = lastRefreshEpochMillis ?: return true
        if (lastRefresh > nowEpochMillis) return true
        return nowEpochMillis - lastRefresh >= keysetRefreshIntervalMillis
    }
}
