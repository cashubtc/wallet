package com.cashu.me.liveintegration

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.cashu.me.Core.CDK.CdkWalletGatewayImpl
import com.cashu.me.Core.CDK.MintRestoreInterrupted
import com.cashu.me.Core.WalletStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Long-running seed restore against a fakewallet mint holding a wallet with tens
 * of thousands of derived secrets, fronted by `CI/restore-fault-proxy.py`.
 * Skipped unless the stress fixture is configured (see `CI/README.md`):
 *   cashu.restoreStress.mintUrl     proxied mint URL (e.g. http://127.0.0.1:3341)
 *   cashu.restoreStress.controlUrl  same proxy, used for fault control
 *   cashu.restoreStress.mnemonic    seed that `CI/restore-stress` funded
 *   cashu.restoreStress.expected    expected recovered sats
 * iOS twin: RestoreResumeIntegrationTests.
 */
class RestoreResumeInstrumentedTest {
    private val args = InstrumentationRegistry.getArguments()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val workDir = File(context.filesDir, "restore-resume-stress")
    private val storeName = "restore_resume_stress"
    private val json = Json { ignoreUnknownKeys = true }

    @After
    fun cleanUp() {
        workDir.deleteRecursively()
        context.deleteSharedPreferences(storeName)
        File(context.filesDir, "datastore/$storeName.preferences_pb").delete()
    }

    private fun arg(name: String): String? = args.getString("cashu.restoreStress.$name")

    private class Fixture(val mintUrl: String, val controlUrl: String, val mnemonic: String, val expected: Long)

    private fun fixture(): Fixture {
        val mintUrl = arg("mintUrl")
        val controlUrl = arg("controlUrl")
        val mnemonic = arg("mnemonic")
        val expected = arg("expected")?.toLongOrNull()
        assumeTrue("Restore stress fixture not configured", mintUrl != null && controlUrl != null && mnemonic != null && expected != null)
        return Fixture(mintUrl!!, controlUrl!!, mnemonic!!, expected!!)
    }

    private data class ProxyStats(val forwarded: Int, val failed: Int)

    private fun control(fixture: Fixture, action: String, body: String = "{}"): ProxyStats {
        val connection = (URL("${fixture.controlUrl}/__restore/$action").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 30_000
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        val obj = json.parseToJsonElement(text).jsonObject
        return ProxyStats(obj.getValue("forwarded").jsonPrimitive.int, obj.getValue("failed").jsonPrimitive.int)
    }

    private suspend fun freshGateway(fixture: Fixture, label: String): CdkWalletGatewayImpl {
        workDir.mkdirs()
        val gateway = CdkWalletGatewayImpl()
        gateway.initializeLogging("warn")
        gateway.openWalletRepository(fixture.mnemonic, File(workDir, "$label.db").absolutePath)
        gateway.ensureWallet(fixture.mintUrl)
        return gateway
    }

    @Test
    fun interruptedRestoreResumesWithoutRepeatingBatchesAndRecoversEverything() = runBlocking {
        val fixture = fixture()
        control(fixture, "heal")

        // Baseline: how many restore requests a clean scan takes.
        val baselineStart = control(fixture, "stats")
        val baselineGateway = freshGateway(fixture, "baseline")
        val baselineStore = WalletStore(context, storeName)
        val baseline = try {
            baselineGateway.restoreMint(fixture.mintUrl, baselineStore)
        } finally {
            baselineGateway.closeWalletRepository()
        }
        val baselineRequests = control(fixture, "stats").forwarded - baselineStart.forwarded
        assertEquals("uninterrupted restore must recover everything", fixture.expected, baseline.unspent)
        assertTrue("fixture is too small to exercise a long restore: $baselineRequests", baselineRequests > 100)
        assertNull(baselineStore.load(fixture.mintUrl))

        // Interrupted: cut the mint off a third of the way through.
        val gateway = freshGateway(fixture, "resumed")
        val store = WalletStore(context, storeName)
        try {
            val cutoff = baselineRequests / 3
            val start = control(fixture, "stats")
            control(fixture, "config", """{"fail_after": $cutoff, "mode": "drop"}""")
            try {
                gateway.restoreMint(fixture.mintUrl, store)
                fail("Restore should have been interrupted")
            } catch (error: MintRestoreInterrupted) {
                assertTrue(error.checkpoint.nextKeysetIndices.isNotEmpty())
                assertTrue((error.checkpoint.nextKeysetIndices.values.maxOrNull() ?: 0L) > 0L)
            }
            val saved = store.load(fixture.mintUrl)
            assertNotNull("checkpoint must be persisted", saved)
            val firstLeg = control(fixture, "stats").forwarded - start.forwarded
            assertTrue("partial progress must be reported", saved!!.unspent > 0L)
            assertTrue("partial progress must be less than everything", saved.unspent < fixture.expected)

            // Resume on the same wallet once the mint is reachable again.
            control(fixture, "heal")
            val resumeStart = control(fixture, "stats")
            val resumed = gateway.restoreMint(fixture.mintUrl, store)
            val secondLeg = control(fixture, "stats").forwarded - resumeStart.forwarded

            assertEquals("resumed totals must match an uninterrupted restore", fixture.expected, resumed.unspent)
            assertEquals(baseline.spent, resumed.spent)
            assertNull("checkpoint must be cleared on success", store.load(fixture.mintUrl))
            assertEquals(fixture.expected, gateway.totalBalance(fixture.mintUrl))

            // No batch scanned twice (allow the scan's own trailing gap batches).
            assertTrue(
                "resume repeated finished batches: $firstLeg + $secondLeg vs $baselineRequests",
                firstLeg + secondLeg <= baselineRequests + 8,
            )
        } finally {
            gateway.closeWalletRepository()
        }
    }
}
