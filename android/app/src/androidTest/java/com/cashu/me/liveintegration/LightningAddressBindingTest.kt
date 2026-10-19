package com.cashu.me.liveintegration

import android.system.Os
import androidx.test.platform.app.InstrumentationRegistry
import com.cashu.me.Core.CDK.CdkWalletGatewayImpl
import com.cashu.me.Core.Wallet.userFacingWalletMessage
import com.cashu.me.Models.PaymentMethodKind
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Production gateway -> generated Kotlin binding -> native CDK -> network stubs. */
class LightningAddressBindingTest {
    private lateinit var fixture: String
    private lateinit var gateway: CdkWalletGatewayImpl
    private lateinit var database: File
    private val mint get() = fixture

    @Before fun setup() = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Run the Lightning Address fixture harness", args.getString("lightningFixtures") == "true")
        fixture = args.getString("lightningFixtureUrl") ?: "http://127.0.0.1:18444"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val certificate = File(context.cacheDir, "lightning-fixture-ca.pem")
        URL("$fixture/ca.pem").openStream().use { input -> certificate.outputStream().use { input.copyTo(it) } }
        Os.setenv("SSL_CERT_FILE", certificate.absolutePath, true)
        database = File(context.cacheDir, "lightning-${UUID.randomUUID()}.db")
        gateway = CdkWalletGatewayImpl()
    }

    @After fun cleanup() = runBlocking {
        if (::gateway.isInitialized) gateway.closeWalletRepository()
        if (::database.isInitialized) for (suffix in listOf("", "-wal", "-shm")) File(database.path + suffix).delete()
        Unit
    }

    private fun call(path: String, body: JsonObject? = null): JsonObject {
        val connection = (URL(fixture + path).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 20000
            if (body != null) { requestMethod = "POST"; doOutput = true; setRequestProperty("Content-Type", "application/json") }
        }
        return try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray()) }
            assertEquals(200, connection.responseCode)
            Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally { connection.disconnect() }
    }

    private suspend fun scenario(body: JsonObject = buildJsonObject {}) {
        call("/scenario", body)
        gateway.openWalletRepository(gateway.generateMnemonic(), database.path)
        gateway.ensureWallet(mint)
    }

    private fun assertTrace(method: String?, dns: Boolean) {
        val trace = call("/state").getValue("events").jsonArray.map { it.jsonObject }
        val quotes = trace.filter { it["kind"]?.jsonPrimitive?.content == "mint-quote" }
        assertEquals(method?.let { listOf(it) }.orEmpty(), quotes.map { it.getValue("method").jsonPrimitive.content })
        assertEquals(dns, trace.any { it["kind"]?.jsonPrimitive?.content == "dns" })
        assertEquals(1, trace.count { it["kind"]?.jsonPrimitive?.content == "lnurl-discovery" })
    }

    @Test fun dualNameWithBolt11OnlyMintUsesLnurl() = runBlocking {
        scenario(buildJsonObject { put("bolt12", false) })
        val quote = gateway.createMeltQuote("test@twelve.cash", 100, mint)
        assertEquals(PaymentMethodKind.Bolt11, quote.paymentMethod)
        assertEquals(100L, quote.amount)
        assertTrace("bolt11", false)
    }

    @Test fun dualNameWithBolt12MintStillUsesLnurl() = runBlocking {
        scenario()
        val quote = gateway.createMeltQuote("test@twelve.cash", 100, mint)
        assertEquals(PaymentMethodKind.Bolt11, quote.paymentMethod)
        assertTrace("bolt11", false)
    }

    @Test fun recipient502DoesNotFallBackOrReachMint() = runBlocking {
        scenario(buildJsonObject { put("lnurl_status", 502) })
        val failure = runCatching { gateway.createMeltQuote("test@twelve.cash", 100, mint) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("The recipient’s Lightning address service is unavailable (HTTP 502). Try again later.", failure!!.userFacingWalletMessage)
        assertTrace(null, false)
    }

    @Test fun absentLnurlUsesValidatedBip353AndBolt12() = runBlocking {
        scenario(buildJsonObject { put("lnurl_status", 404) })
        val quote = gateway.createMeltQuote("matt@mattcorallo.com", 100, mint)
        assertEquals(PaymentMethodKind.Bolt12, quote.paymentMethod)
        assertEquals(100L, quote.amount)
        assertTrace("bolt12", true)
    }

    @Test fun actualMintConnectionFailureKeepsMintMessage() = runBlocking {
        scenario(buildJsonObject { put("mint_failure", true) })
        val failure = runCatching { gateway.createMeltQuote("test@twelve.cash", 100, mint) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("Couldn't reach the mint. Check your connection and try again.", failure!!.userFacingWalletMessage)
        assertTrace("bolt11", false)
    }

    @Test fun dnsFailureKeepsBip353Origin() = runBlocking {
        scenario(buildJsonObject { put("lnurl_status", 404); put("dns_failure", true) })
        val failure = runCatching { gateway.createMeltQuote("test@twelve.cash", 100, mint) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("Couldn't resolve the recipient’s BIP-353 address. Check the address or try again later.", failure!!.userFacingWalletMessage)
        assertTrace(null, true)
    }

    @Test fun absentLnurlCannotUseBolt11OnlyMintForBip353() = runBlocking {
        scenario(buildJsonObject { put("lnurl_status", 404); put("bolt12", false) })
        val failure = runCatching { gateway.createMeltQuote("test@twelve.cash", 100, mint) }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals("No mint supports BOLT12 payments required for this address.", failure!!.userFacingWalletMessage)
        assertTrace(null, false)
    }
}
