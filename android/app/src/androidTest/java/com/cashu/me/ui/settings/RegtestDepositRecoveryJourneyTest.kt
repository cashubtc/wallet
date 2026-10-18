package com.cashu.me.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.graphics.writeToTestStorage
import com.cashu.me.Core.DepositRecoveryRequest
import com.cashu.me.Core.DepositRecoveryStatus
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.test.WalletJourneyRobot
import com.cashu.me.test.fixtures.AppTestFixture
import com.cashu.me.test.fixtures.FixtureMode
import com.cashu.me.ui.testing.UiTestTags
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class RegtestDepositRecoveryJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun paidUnmintedOnchainQuoteRecoversThroughUIOnlyOnce() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cashu.regtestRecovery") == "true")
        val paid = fixtureCall("/new")
        val quoteId = paid.getString("quote")
        val mint = paid.getString("mint_url")
        assertEquals(21_000, paid.getInt("amount_paid"))
        assertEquals(0, paid.getInt("amount_issued"))
        assertTrue(paid.getInt("confirmations") >= 3)
        AppTestFixture.launch(FixtureMode.LiveSeededWithoutMint, mnemonic = paid.getString("mnemonic")).use { fixture ->
            val manager = fixture.container.walletManager
            assertEquals(0L, manager.state.value.balance)
            assertTrue(manager.state.value.mints.isEmpty())
            WalletJourneyRobot(compose).awaitTag(UiTestTags.WalletScreen).tapDescription("Settings")
                .scrollToText(UiTestTags.SettingsList, "Backup & Restore").tapText("Backup & Restore")
            compose.onNodeWithText("Recover a deposit").performScrollTo().performClick()
            compose.onNodeWithTag("depositRecoveryMintURL").performTextInput(mint)
            compose.onNodeWithTag("depositRecoveryQuoteID").performTextInput(quoteId)
            compose.onNodeWithTag("depositRecoveryQuoteID").performImeAction()
            compose.onNodeWithTag("depositRecoveryOptions").performClick()
            compose.onNodeWithText("Payment method").performClick()
            compose.onNodeWithText("On-chain address").performClick()
            compose.onNodeWithTag("depositRecoveryOptions").performClick()
            compose.onNodeWithTag("recoverDepositButton").performScrollTo().performClick()
            compose.onNodeWithText("Recover this deposit?").assertIsDisplayed()
            compose.onNodeWithText("Claim this deposit at 127.0.0.1", substring = true).assertIsDisplayed()
            // Showing approval must not claim the paid quote.
            assertEquals(0, fixtureCall("/quote/$quoteId").getInt("amount_issued"))
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).writeToTestStorage("android-regtest-first-confirmation")
            compose.onNodeWithText("Recover").performClick()
            compose.waitUntil(timeoutMillis = 60_000) { manager.state.value.balance == 21_000L }
            compose.onNodeWithText("Recovered ", substring = true).performScrollTo().assertIsDisplayed()
            assertEquals(21_000, fixtureCall("/quote/$quoteId").getInt("amount_issued"))
            checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).writeToTestStorage("android-regtest-recovered")
            runBlocking {
                val repeat = manager.recoverDeposit(DepositRecoveryRequest(mint, quoteId, PaymentMethodKind.Onchain))
                assertEquals(DepositRecoveryStatus.AlreadyClaimed, repeat.status)
            }
            assertEquals(21_000L, manager.state.value.balance)
            assertEquals(1, manager.state.value.transactions.count { it.quoteId == quoteId })
            assertEquals(21_000, fixtureCall("/quote/$quoteId").getInt("amount_issued"))
        }
    }

    private fun fixtureCall(path: String): JSONObject {
        val connection = URL("http://127.0.0.1:3340$path").openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 115_000
        return try {
            check(connection.responseCode == 200) { "Local regtest fixture failed" }
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
}
