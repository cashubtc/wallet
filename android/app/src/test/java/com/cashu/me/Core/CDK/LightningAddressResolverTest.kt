package com.cashu.me.Core.CDK

import com.cashu.me.Core.Wallet.WalletErrorMessages
import kotlinx.coroutines.runBlocking
import org.cashudevkit.FfiException
import org.junit.Assert.*
import org.junit.Test

class LightningAddressResolverTest {
    private val discovery = "Failed to request invoice from Lightning address service: Failed to fetch pay request data: Http transport error Some("
    private fun failure(message: String) = FfiException.Cdk(50000u, message)

    @Test
    fun lnurlWinsForBothBolt11OnlyAndBolt12Mints() = runBlocking {
        for (supportsBolt12 in listOf(false, true)) {
            val calls = mutableListOf<String>()
            val quote = LightningAddressResolver.quote(
                lnurl = { calls += "lnurl"; "bolt11" },
                bip353 = { calls += "bip353"; check(supportsBolt12); "bolt12" },
            )
            assertEquals("bolt11", quote)
            assertEquals(listOf("lnurl"), calls)
        }
    }

    @Test
    fun onlyMissingDiscoveryEndpointFallsBack() = runBlocking {
        for (status in listOf(404, 410)) {
            var fallbackCalls = 0
            val quote = LightningAddressResolver.quote(
                lnurl = { throw failure("$discovery$status): missing") },
                bip353 = { fallbackCalls++; "bolt12" },
            )
            assertEquals("bolt12", quote)
            assertEquals(1, fallbackCalls)
        }
    }

    @Test
    fun temporaryAndInvalidRecipientResponsesNeverFallBack() = runBlocking {
        val prefix = "Failed to request invoice from Lightning address service: "
        for (message in listOf(
            "${discovery}502): Bad Gateway", "${discovery}429): Slow down",
            "${prefix}Failed to fetch pay request data: Http transport error None: DNS failed",
            "${prefix}Http transport error Some(404): callback missing",
            "${prefix}Returned invoice amount 1 msat does not match requested amount 1000 msat",
            "${prefix}Invalid LNURL callback URL: callback host must match lightning address domain",
            "${prefix}Failed to fetch pay request data: Http transport error None: invalid JSON",
            "Http transport error Some(404): mint quote missing",
        )) {
            val original = failure(message)
            var fallbackCalls = 0
            val thrown = runCatching {
                LightningAddressResolver.quote<String>(
                    lnurl = { throw original }, bip353 = { fallbackCalls++; "bolt12" },
                )
            }.exceptionOrNull()
            assertSame(original, thrown)
            assertEquals(0, fallbackCalls)
        }
    }

    @Test
    fun errorsKeepRecipientDnsAndMintOrigins() = runBlocking {
        val unavailable = failure("${discovery}502): Bad Gateway")
        assertEquals("The recipient’s Lightning address service is unavailable (HTTP 502). Try again later.", WalletErrorMessages.classify(unavailable).text)
        val dns = failure("Failed to resolve BIP353 address: DNS timeout")
        val thrown = runCatching {
            LightningAddressResolver.quote<String>(lnurl = { throw failure("${discovery}404): missing") }, bip353 = { throw dns })
        }.exceptionOrNull()!!
        assertSame(dns, thrown)
        assertEquals("Couldn't resolve the recipient’s BIP-353 address. Check the address or try again later.", WalletErrorMessages.classify(thrown).text)
        assertEquals("Couldn't reach the mint. Check your connection and try again.", WalletErrorMessages.classify(failure("Http transport error None: connection refused")).text)
    }
}
