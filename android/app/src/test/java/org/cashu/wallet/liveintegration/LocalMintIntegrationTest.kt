package org.cashu.wallet.liveintegration

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.cashu.wallet.Core.LightningRequestParser
import org.cashu.wallet.Core.PaymentRequestBuilder
import org.cashu.wallet.Core.PaymentRequestDecodeResult
import org.cashu.wallet.Core.PaymentRequestDecoder
import org.cashu.wallet.Core.TokenParser
import org.cashu.wallet.Core.WalletMintMetadataFetcher
import org.cashu.wallet.Models.PaymentMethodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class LocalMintIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val nutshellMintUrl: String =
        System.getProperty("cashu.nutshellMintUrl") ?: "http://localhost:3338"
    private val cdkMintUrl: String =
        System.getProperty("cashu.cdkMintUrl") ?: "http://localhost:3339"

    @Test
    fun localMintInfoEndpointsExposeExpectedCapabilities() = runBlocking {
        assumeLiveMintTask()
        assertMintEndpointReady(nutshellMintUrl)
        assertMintEndpointReady(cdkMintUrl)

        val fetcher = WalletMintMetadataFetcher()
        val nutshell = fetcher.fetchRawMintInfo(nutshellMintUrl)
        assertTrue(nutshell.supportedMintMethods.contains(PaymentMethodKind.Bolt11))
        assertTrue(nutshell.effectiveMintUnits.contains("sat"))

        val cdk = fetcher.fetchRawMintInfo(cdkMintUrl)
        assertEquals("CDK Test Mint", cdk.name)
        assertTrue(cdk.supportedMintMethods.contains(PaymentMethodKind.Bolt11))
        assertTrue(cdk.supportedMintMethods.contains(PaymentMethodKind.Bolt12))
        assertTrue(cdk.supportedMintMethods.contains(PaymentMethodKind.Onchain))
        assertTrue(cdk.effectiveMintUnits.contains("sat"))
        assertTrue(cdk.effectiveMintUnits.contains("usd"))
        assertTrue(cdk.supportsMultipleMintUnits)
    }

    @Test
    fun localMintKeysetsExposeSatAndUsdCoverage() {
        assumeLiveMintTask()

        val nutshellUnits = keysetUnits(nutshellMintUrl)
        assertTrue(nutshellUnits.contains("sat"))

        val cdkUnits = keysetUnits(cdkMintUrl)
        assertTrue(cdkUnits.contains("sat"))
        assertTrue(cdkUnits.contains("usd"))
    }

    @Test
    fun localMintQuoteEndpointsCreateBolt11QuotesForNutshellAndCdk() {
        assumeLiveMintTask()

        val nutshellQuote = postJson(
            mintUrl = nutshellMintUrl,
            path = "/v1/mint/quote/bolt11",
            body = """{"amount":12,"unit":"sat"}""",
        )
        assertMintQuote(nutshellQuote, amount = 12, unit = "sat")
        assertEquals(
            PaymentMethodKind.Bolt11,
            LightningRequestParser.parse(nutshellQuote.requiredString("request")).method,
        )

        val cdkQuote = postJson(
            mintUrl = cdkMintUrl,
            path = "/v1/mint/quote/bolt11",
            body = """{"amount":12,"unit":"sat"}""",
        )
        assertMintQuote(cdkQuote, amount = 12, unit = "sat")
        assertEquals(
            PaymentMethodKind.Bolt11,
            LightningRequestParser.parse(cdkQuote.requiredString("request")).method,
        )
    }

    @Test
    fun cdkQuoteEndpointsCoverBolt12OnchainAndMultiUnit() {
        assumeLiveMintTask()
        val pubkey = "02aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

        val bolt12 = postJson(
            mintUrl = cdkMintUrl,
            path = "/v1/mint/quote/bolt12",
            body = """{"unit":"sat","pubkey":"$pubkey"}""",
        )
        assertNotNull(bolt12.quoteId())
        val offer = bolt12.requiredString("request")
        assertEquals(PaymentMethodKind.Bolt12, LightningRequestParser.parse(offer).method)

        val onchain = postJson(
            mintUrl = cdkMintUrl,
            path = "/v1/mint/quote/onchain",
            body = """{"amount":11,"unit":"sat","pubkey":"$pubkey"}""",
        )
        assertMintQuote(onchain, amount = 11, unit = "sat", requireAmount = false)
        assertTrue(onchain.requiredString("request").startsWith("bcrt", ignoreCase = true))

        val usdQuote = postJson(
            mintUrl = cdkMintUrl,
            path = "/v1/mint/quote/bolt11",
            body = """{"amount":125,"unit":"usd"}""",
        )
        assertMintQuote(usdQuote, amount = 125, unit = "usd")
        assertEquals(
            PaymentMethodKind.Bolt11,
            LightningRequestParser.parse(usdQuote.requiredString("request")).method,
        )
    }

    @Test
    fun androidParsersHandleLocalCashuRequestsAndTokenPrefixes() {
        assumeLiveMintTask()

        val cashuRequest = PaymentRequestBuilder.build(
            id = "local-cdk-request",
            amount = 7,
            unit = "sat",
            mints = listOf(cdkMintUrl),
            description = "Local CDK cashu request",
            nostrPubkeyHex = "1".repeat(64),
            relays = emptyList(),
        )
        val decoded = PaymentRequestDecoder.decode(
            cashuRequest,
            includeCashuPaymentRequests = true,
        ) as? PaymentRequestDecodeResult.CashuPaymentRequest

        assertNotNull(decoded)
        assertEquals(7L, decoded?.summary?.amount)
        assertEquals("sat", decoded?.summary?.unit)
        assertEquals(listOf(cdkMintUrl), decoded?.summary?.mints)
        assertEquals("cashuBtest", TokenParser.normalizedToken("cashu://cashuBtest"))
        assertFalse(TokenParser.isCashuToken(cashuRequest))
    }

    private fun assumeLiveMintTask() {
        assumeTrue(
            "Run with :app:androidLocalMintIntegrationTest to enable live local mint coverage.",
            System.getProperty("cashu.localMintIntegration") == "true",
        )
    }

    private fun assertMintEndpointReady(mintUrl: String) {
        val connection = (URL("$mintUrl/v1/info").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 2_000
            readTimeout = 2_000
        }
        try {
            assertTrue("Mint endpoint is not ready: $mintUrl", connection.responseCode in 200..299)
        } finally {
            connection.disconnect()
        }
    }

    private fun keysetUnits(mintUrl: String): Set<String> {
        val keysets = getJson(mintUrl, "/v1/keys")["keysets"]?.jsonArray.orEmpty()
        assertTrue("Expected at least one keyset for $mintUrl", keysets.isNotEmpty())
        return keysets.mapNotNull { keyset ->
            runCatching { keyset.jsonObject["unit"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }.toSet()
    }

    private fun assertMintQuote(
        fields: JsonObject,
        amount: Long,
        unit: String,
        requireAmount: Boolean = true,
    ) {
        assertNotNull(fields.quoteId())
        assertTrue(fields.requiredString("request").isNotBlank())
        val responseAmount = fields["amount"]?.jsonPrimitive?.longOrNull
        if (requireAmount || responseAmount != null) {
            assertEquals(amount, responseAmount)
        }
        assertEquals(unit, fields["unit"]?.jsonPrimitive?.contentOrNull)
        fields["state"]?.jsonPrimitive?.contentOrNull?.let { state ->
            assertTrue(state.equals("UNPAID", ignoreCase = true) || state.equals("PENDING", ignoreCase = true))
        }
    }

    private fun getJson(mintUrl: String, path: String): JsonObject = requestJson(mintUrl, path)

    private fun postJson(mintUrl: String, path: String, body: String): JsonObject =
        requestJson(mintUrl, path, method = "POST", body = body)

    private fun requestJson(
        mintUrl: String,
        path: String,
        method: String = "GET",
        body: String? = null,
    ): JsonObject {
        val connection = (URL("$mintUrl$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 3_000
            readTimeout = 5_000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }
            val responseBody = responseBody(connection)
            assertTrue(
                "$method $mintUrl$path returned HTTP ${connection.responseCode}: $responseBody",
                connection.responseCode in 200..299,
            )
            return json.parseToJsonElement(responseBody).jsonObject
        } finally {
            connection.disconnect()
        }
    }

    private fun responseBody(connection: HttpURLConnection): String {
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        return stream?.bufferedReader()?.use { it.readText() }.orEmpty()
    }

    private fun JsonObject.quoteId(): String? =
        this["quote"]?.jsonPrimitive?.contentOrNull ?: this["id"]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.requiredString(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull ?: error("Missing JSON field: $name")
}
