package com.cashu.me.ui.journeys

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.graphics.writeToTestStorage
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cashu.me.Core.CDK.CdkWalletGatewayImpl
import com.cashu.me.Models.MintQuoteState
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.test.UiFailureArtifactsRule
import com.cashu.me.test.WalletJourneyRobot
import com.cashu.me.test.fixtures.AppTestFixture
import com.cashu.me.test.fixtures.FixtureMode
import com.cashu.me.test.fixtures.LaunchedFixture
import com.cashu.me.ui.testing.UiTestTags
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real WalletManager, CDK, local mint HTTP requests, SQLite, and production History UI. */
@RunWith(AndroidJUnit4::class)
class LiveMintRetryHistoryJourneyTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val artifacts = UiFailureArtifactsRule(compose) { launched?.close() }
    private var launched: LaunchedFixture? = null
    private val args get() = InstrumentationRegistry.getArguments()
    private val robot by lazy { WalletJourneyRobot(compose) }

    @Test fun repeatedMintFailuresThenSuccessShowOneActualInvoiceInHistory() {
        assumeTrue("Start CI/mint-history-retry-proxy.py and the local test mint",
            args.getString("cashu.liveMintHistory") == "true")
        val mint = args.getString("cashu.nutshellMintUrl") ?: "http://127.0.0.1:3344"
        require(URL(mint).host in setOf("127.0.0.1", "localhost"))
        launched = AppTestFixture.launch(FixtureMode.LiveLocalMint,
            mnemonic = runBlocking { CdkWalletGatewayImpl().generateMnemonic() })
        val fixture = launched!!
        val manager = fixture.container.walletManager
        val gateway = fixture.container.cdkGateway
        robot.awaitTag(UiTestTags.WalletScreen)
        val quoteId = runBlocking {
            val quote = manager.createMintQuote(64, PaymentMethodKind.Bolt11, "sat", null)
            control(mint, "reject", quote.id)
            var paid = false
            repeat(50) {
                if (!paid) {
                    paid = manager.checkMintQuote(quote.id).state == MintQuoteState.Paid
                    if (!paid) delay(100)
                }
            }
            assertTrue("Local FakeWallet must settle the real invoice", paid)
            repeat(5) {
                assertTrue("The proxy must reject the real mint request", runCatching { manager.mintTokens(quote.id) }.isFailure)
                manager.loadTransactions()
            }
            val failures = gateway.listTransactions(mapOf(mint to listOf("sat")))
            assertEquals(5, failures.size)
            assertTrue(failures.all { it.status == TransactionStatus.Failed && it.quoteId == quote.id })
            assertEquals(0L, gateway.totalBalance(mint))
            control(mint, "accept", quote.id)
            assertEquals(64L, manager.mintTokens(quote.id))
            val stored = gateway.listTransactions(mapOf(mint to listOf("sat")))
            assertEquals(6, stored.size)
            assertEquals(6, stored.map { it.id }.toSet().size)
            assertEquals(1, stored.count { it.status == TransactionStatus.Completed })
            assertTrue(stored.all { it.quoteId == quote.id && it.invoice == quote.request })
            assertEquals(64L, gateway.totalBalance(mint))
            val stats = control(mint, "stats", quote.id)
            assertEquals(5, stats.getInt("rejected"))
            assertEquals(1, stats.getInt("successful"))
            quote.id
        }
        robot.tapText("History").awaitTag(UiTestTags.HistoryScreen)
        compose.waitForIdle()
        val phase = args.getString("cashu.historyPhase") ?: "fixed"
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            .writeToTestStorage("mint-history-$phase")
        // On the unfixed app this asserts against six genuinely minted-attempt rows,
        // not manually seeded transactions. Failure artifacts include the actual screen.
        compose.onAllNodesWithText("Lightning received").assertCountEquals(1)
        val receipt = manager.state.value.transactions.single()
        assertEquals(quoteId, receipt.quoteId)
        assertEquals(TransactionStatus.Completed, receipt.status)
        compose.onNodeWithTag(UiTestTags.transactionRow(receipt.id)).assertIsDisplayed()
        robot.tapTag(UiTestTags.transactionRow(receipt.id)).awaitText("Completed")
        robot.pressSystemBack().tapText("Wallet").awaitTag(UiTestTags.WalletScreen)
        fixture.scenario.recreate()
        runBlocking { manager.loadTransactions() }
        robot.awaitTag(UiTestTags.WalletScreen).tapText("History").awaitTag(UiTestTags.HistoryScreen)
        compose.onAllNodesWithText("Lightning received").assertCountEquals(1)
        runBlocking {
            assertEquals(6, gateway.listTransactions(mapOf(mint to listOf("sat"))).size)
            assertEquals(64L, gateway.totalBalance(mint))
            assertEquals(1, control(mint, "stats", quoteId).getInt("successful"))
        }
    }

    @Test fun unpaidInvoicesWithRepeatedAmountsRemainDistinctWithoutMultiplyingOnReload() {
        assumeTrue(args.getString("cashu.liveUnpaidHistory") == "true")
        val mint = args.getString("cashu.nutshellMintUrl") ?: "http://127.0.0.1:3347"
        require(URL(mint).host in setOf("127.0.0.1", "localhost"))
        launched = AppTestFixture.launch(FixtureMode.LiveLocalMint,
            mnemonic = runBlocking { CdkWalletGatewayImpl().generateMnemonic() })
        val fixture = launched!!
        val manager = fixture.container.walletManager
        val gateway = fixture.container.cdkGateway
        robot.awaitTag(UiTestTags.WalletScreen)
        runBlocking {
            val amounts = listOf(99_000L, 99_000L, 98_000L, 98_000L, 99_000L, 98_000L, 99_000L, 98_000L, 98_100L)
            val quotes = amounts.map { manager.createMintQuote(it, PaymentMethodKind.Bolt11, "sat", null) }
            assertEquals(9, quotes.map { it.id }.toSet().size)
            assertEquals(9, quotes.map { it.request }.toSet().size)
            repeat(5) {
                manager.loadTransactions()
                val rows = manager.state.value.transactions
                assertEquals(9, rows.size)
                assertEquals(quotes.map { it.id }.toSet(), rows.map { it.id }.toSet())
                assertTrue(rows.all { it.isUnpaidInvoice && it.status == TransactionStatus.Pending })
                assertEquals(0L, gateway.totalBalance(mint))
                assertTrue(gateway.listTransactions(mapOf(mint to listOf("sat"))).isEmpty())
            }
        }
        robot.tapText("History").awaitTag(UiTestTags.HistoryScreen)
        compose.waitForIdle()
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            .writeToTestStorage("unpaid-invoices-repeated-amounts")
        assertTrue(compose.onAllNodesWithText("Lightning invoice").fetchSemanticsNodes().size > 1)
    }

    private fun control(mint: String, action: String, quote: String): JSONObject {
        val connection = (URL("$mint/__mint_history/$action").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 5_000
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            connection.outputStream.use { it.write(JSONObject().put("quote", quote).toString().toByteArray()) }
            assertEquals(200, connection.responseCode)
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
}
