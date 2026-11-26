package com.cashu.me.Core

import kotlinx.coroutines.runBlocking
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

    @Test
    fun observePaymentSkipsTipHeightWhenAddressHasNoTransactions() = runBlocking {
        OnchainExplorer.clearTipHeightCacheForTesting()
        val requestedUrls = mutableListOf<String>()

        val result = OnchainExplorer.observePayment(
            address = "bc1qempty",
            mintUrl = null,
            expectedAmount = 1,
            createdAfterEpochMillis = 0,
            nowEpochMillis = { 1_000L },
            fetch = { url ->
                requestedUrls += url
                "[]"
            },
        )

        assertNull(result)
        assertEquals(listOf("https://mempool.space/api/address/bc1qempty/txs"), requestedUrls)
    }

    @Test
    fun observePaymentSkipsTipHeightForUnconfirmedTransaction() = runBlocking {
        OnchainExplorer.clearTipHeightCacheForTesting()
        val requestedUrls = mutableListOf<String>()
        val txsJson = """[{"txid":"mempool-tx","status":{"confirmed":false},"vout":[{"scriptpubkey_address":"bc1qunconfirmed","value":2500}]}]"""

        val result = OnchainExplorer.observePayment(
            address = "bc1qunconfirmed",
            mintUrl = null,
            expectedAmount = 1,
            createdAfterEpochMillis = 0,
            nowEpochMillis = { 1_000L },
            fetch = { url ->
                requestedUrls += url
                txsJson
            },
        )

        assertEquals(OnchainPaymentObservation("mempool-tx", 2500, confirmed = false, confirmations = null), result)
        assertEquals(listOf("https://mempool.space/api/address/bc1qunconfirmed/txs"), requestedUrls)
    }

    @Test
    fun observePaymentCachesTipHeightWithinTtl() = runBlocking {
        OnchainExplorer.clearTipHeightCacheForTesting()
        val requestedUrls = mutableListOf<String>()
        var now = 10_000L
        val txsJson = """[{"txid":"confirmed-tx","status":{"confirmed":true,"block_height":100,"block_time":5},"vout":[{"scriptpubkey_address":"bc1qconfirmed","value":1000}]}]"""

        suspend fun fetch(url: String): String {
            requestedUrls += url
            return if (url.endsWith("/blocks/tip/height")) "102" else txsJson
        }

        val first = OnchainExplorer.observePayment("bc1qconfirmed", null, 1, 0, { now }, ::fetch)
        now += 15_000L
        val second = OnchainExplorer.observePayment("bc1qconfirmed", null, 1, 0, { now }, ::fetch)
        now += 35_000L
        val third = OnchainExplorer.observePayment("bc1qconfirmed", null, 1, 0, { now }, ::fetch)

        assertEquals(3, first?.confirmations)
        assertEquals(3, second?.confirmations)
        assertEquals(3, third?.confirmations)
        assertEquals(2, requestedUrls.count { it.endsWith("/blocks/tip/height") })
    }
}
