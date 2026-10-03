package com.cashu.me.Core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnchainExplorerTest {
    @Test
    fun mainnetAddressUsesMempoolSpace() {
        assertEquals(
            "https://mempool.space/address/bc1qexample",
            OnchainExplorer.addressWebUrl("bitcoin:bc1qexample?amount=1", mintUrl = null),
        )
    }

    @Test
    fun testnetAddressUsesSignetMempoolSpace() {
        assertEquals(
            "https://mempool.space/signet/address/tb1qexample",
            OnchainExplorer.addressWebUrl("tb1qexample", mintUrl = null),
        )
    }

    @Test
    fun cdkOnchainMintUsesMutinynet() {
        assertEquals(
            "https://mutinynet.com/tx/abc123",
            OnchainExplorer.transactionWebUrl(
                txid = "abc123",
                address = null,
                mintUrl = "https://onchain.cashudevkit.org",
            ),
        )
    }

    @Test
    fun apiUrlUsesMatchingExplorerNetwork() {
        assertEquals(
            "https://mempool.space/api/address/bc1qexample/txs",
            OnchainExplorer.addressTransactionsApiUrl("bitcoin:bc1qexample?amount=1", mintUrl = null),
        )
        assertEquals(
            "https://mempool.space/signet/api/address/tb1qexample/txs",
            OnchainExplorer.addressTransactionsApiUrl("tb1qexample", mintUrl = null),
        )
        assertEquals(
            "https://mutinynet.com/api/address/tb1qexample/txs",
            OnchainExplorer.addressTransactionsApiUrl(
                address = "tb1qexample",
                mintUrl = "https://onchain.cashudevkit.org",
            ),
        )
    }

    @Test
    fun cacheBustingPreservesExistingQueryString() {
        assertEquals(
            "https://mempool.space/api/blocks?_=123",
            OnchainExplorer.cacheBustedUrl("https://mempool.space/api/blocks", nowEpochMillis = 123),
        )
        assertEquals(
            "https://mempool.space/api/tx?id=abc&_=123",
            OnchainExplorer.cacheBustedUrl("https://mempool.space/api/tx?id=abc", nowEpochMillis = 123),
        )
    }

    @Test
    fun confirmationCountMatchesSwiftFallbacks() {
        assertNull(OnchainExplorer.confirmations(confirmed = false, blockHeight = 9, tipHeight = 10))
        assertEquals(1, OnchainExplorer.confirmations(confirmed = true, blockHeight = null, tipHeight = 10))
        assertEquals(1, OnchainExplorer.confirmations(confirmed = true, blockHeight = 12, tipHeight = 10))
        assertEquals(3, OnchainExplorer.confirmations(confirmed = true, blockHeight = 8, tipHeight = 10))
    }

    @Test
    fun regtestAddressesHaveNoPublicExplorer() {
        assertEquals(null, OnchainExplorer.addressWebUrl("bcrt1qregtest", mintUrl = "http://localhost:3339"))
    }

    @Test
    fun unknownNetworkWithoutMintHasNoExplorer() {
        assertNull(OnchainExplorer.addressWebUrl("unknown", mintUrl = null))
    }
}
