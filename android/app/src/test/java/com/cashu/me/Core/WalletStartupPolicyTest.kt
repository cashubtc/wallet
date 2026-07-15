package com.cashu.me.Core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletStartupPolicyTest {
    @Test
    fun refreshesKeysetsWhenTimestampIsMissingOrStale() {
        val now = 10_000_000L

        assertTrue(WalletStartupPolicy.shouldRefreshKeysets(lastRefreshEpochMillis = null, nowEpochMillis = now))
        assertTrue(
            WalletStartupPolicy.shouldRefreshKeysets(
                lastRefreshEpochMillis = now - WalletStartupPolicy.keysetRefreshIntervalMillis,
                nowEpochMillis = now,
            ),
        )
    }

    @Test
    fun skipsFreshKeysetRefreshAndRetriesFutureTimestamp() {
        val now = 10_000_000L

        assertFalse(
            WalletStartupPolicy.shouldRefreshKeysets(
                lastRefreshEpochMillis = now - WalletStartupPolicy.keysetRefreshIntervalMillis + 1,
                nowEpochMillis = now,
            ),
        )
        assertTrue(
            WalletStartupPolicy.shouldRefreshKeysets(
                lastRefreshEpochMillis = now + 1,
                nowEpochMillis = now,
            ),
        )
    }
}
