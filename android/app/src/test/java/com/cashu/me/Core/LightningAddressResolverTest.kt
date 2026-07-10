package com.cashu.me.Core

import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class LightningAddressResolverTest {
    @Test
    fun resolvesLud16InvoiceAndReplacesCallbackAmount() = kotlinx.coroutines.runBlocking {
        val requestedUrls = mutableListOf<HttpUrl>()
        val resolver = LightningAddressResolver(
            fetcher = { url ->
                requestedUrls += url
                if (url.host == "example.com") {
                    """{"callback":"https://pay.example.net/callback?amount=1&tag=wallet","minSendable":1000,"maxSendable":500000000,"tag":"payRequest"}"""
                } else {
                    """{"pr":"lnbc-valid-invoice"}"""
                }
            },
            metadataDecoder = { request ->
                LightningRequestMetadata(
                    normalizedRequest = request,
                    paymentMethod = com.cashu.me.Models.PaymentMethodKind.Bolt11,
                    amountSats = 250_000,
                    amountMsat = 250_000_000,
                )
            },
        )

        val invoice = resolver.resolveBolt11Invoice("Alice@example.com", 250_000_000)

        assertEquals("lnbc-valid-invoice", invoice)
        assertEquals("example.com", requestedUrls.first().host)
        assertEquals("/.well-known/lnurlp/Alice", requestedUrls.first().encodedPath)
        assertEquals("250000000", requestedUrls.last().queryParameter("amount"))
        assertEquals("wallet", requestedUrls.last().queryParameter("tag"))
        assertEquals(1, requestedUrls.last().queryParameterValues("amount").size)
    }

    @Test
    fun missingLnurlEndpointExplicitlyAllowsBip353Fallback() = kotlinx.coroutines.runBlocking {
        val resolver = LightningAddressResolver(fetcher = {
            throw LightningAddressResolverError.NoLnurlPayEndpoint("not found")
        })

        try {
            resolver.resolveBolt11Invoice("alice@example.com", 1_000)
            fail("Expected resolver error")
        } catch (error: LightningAddressResolverError) {
            assertTrue(error.indicatesNoLnurlPayEndpoint)
        }
    }

    @Test
    fun definitiveLnurlAmountErrorDoesNotFallBackToBip353() = kotlinx.coroutines.runBlocking {
        val resolver = LightningAddressResolver(fetcher = {
            """{"callback":"https://pay.example.net/callback","minSendable":2000,"maxSendable":3000,"tag":"payRequest"}"""
        })

        try {
            resolver.resolveBolt11Invoice("alice@example.com", 1_000)
            fail("Expected amount error")
        } catch (error: LightningAddressResolverError) {
            assertFalse(error.indicatesNoLnurlPayEndpoint)
            assertTrue(error.message.orEmpty().contains("outside"))
        }
    }

    @Test
    fun rejectsAddressDomainsThatCouldChangeTheRequestedHost() = kotlinx.coroutines.runBlocking {
        val resolver = LightningAddressResolver(fetcher = { error("must not fetch") })

        try {
            resolver.resolveBolt11Invoice("alice@example.com@evil.test", 1_000)
            fail("Expected invalid address")
        } catch (error: LightningAddressResolverError) {
            assertFalse(error.indicatesNoLnurlPayEndpoint)
            assertTrue(error is LightningAddressResolverError.InvalidAddress)
        }
    }
}
