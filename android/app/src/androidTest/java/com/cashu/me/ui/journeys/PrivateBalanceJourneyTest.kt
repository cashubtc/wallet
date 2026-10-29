package com.cashu.me.ui.journeys

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.Core.AmountDisplayPrimary
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.Core.HomeBalanceCycle
import com.cashu.me.Core.SettingsStore
import com.cashu.me.test.UiFailureArtifactsRule
import com.cashu.me.test.WalletJourneyRobot
import com.cashu.me.test.fixtures.*
import com.cashu.me.ui.testing.UiTestTags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Private mode in the home balance tap cycle: bitcoin → fiat → private →
 * bitcoin, masking the hero, Recent and History, but not transaction detail.
 */
@RunWith(AndroidJUnit4::class)
class PrivateBalanceJourneyTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val artifacts = UiFailureArtifactsRule(compose) { fixture?.close() }
    private val robot by lazy { WalletJourneyRobot(compose) }
    private var fixture: LaunchedFixture? = null

    @Test fun cycleHidesHomeAndHistoryButNotDetailAndSurvivesRecreation() {
        val app = AppTestFixture.launch(FixtureMode.FundedWithHistory).also { fixture = it }
        enableUsd(app)

        robot.awaitTag(UiTestTags.WalletScreen)
            .tapDescription("Balance: 500 sats")
            .tapDescription("Balance: \$0.50")
            .awaitDescription(HomeBalanceCycle.HiddenLabel)
        assertTrue(SettingsStore(ApplicationProvider.getApplicationContext()).hideBalances)
        // Recent rows show the asterisks alone: no sign, whatever the direction.
        compose.onAllNodes(hasText(AmountFormatter.HiddenDigits), useUnmergedTree = true)
            .onFirst()
            .assertExists()
        compose.onAllNodes(hasText("+${AmountFormatter.HiddenDigits}"), useUnmergedTree = true)
            .assertCountEquals(0)
        assertNoFixtureAmounts()

        app.scenario.recreate()
        robot.awaitTag(UiTestTags.WalletScreen).awaitDescription(HomeBalanceCycle.HiddenLabel)

        robot.tapText("History").awaitTag(UiTestTags.HistoryScreen)
        assertNoFixtureAmounts()
        compose.onAllNodesWithContentDescription(AmountFormatter.HiddenAmountLabel, substring = true)
            .onFirst()
            .assertExists()
        // Opening a transaction is deliberate, so its detail shows the amount.
        robot.tapTag(UiTestTags.transactionRow("fixture-incoming"))
            .awaitTag(UiTestTags.TransactionReceiptSheet)
        compose.onAllNodes(
            showsOrReads("120") and hasAnyAncestor(hasTestTag(UiTestTags.TransactionReceiptSheet)),
            useUnmergedTree = true,
        ).onFirst().assertExists()

        robot.pressSystemBack().tapText("Wallet")
            .tapDescription(HomeBalanceCycle.HiddenLabel)
            .awaitDescription("Balance: 500 sats")
        val store = SettingsStore(ApplicationProvider.getApplicationContext())
        assertFalse(store.hideBalances)
        // Leaving private lands on bitcoin, not on the fiat it was entered from.
        assertEquals(AmountDisplayPrimary.Sats.rawValue, store.homeBalancePrimary)
    }

    @Test fun withoutFiatTheBalanceTogglesBetweenBitcoinAndPrivate() {
        fixture = AppTestFixture.launch(FixtureMode.FundedWithHistory)
        robot.awaitTag(UiTestTags.WalletScreen)
        compose.onNode(hasClickLabel("Hide balance")).assertExists()
        robot.tapDescription("Balance: 500 sats")
            .awaitDescription(HomeBalanceCycle.HiddenLabel)
        compose.onNode(hasClickLabel("Show balance in sats")).assertExists()
        robot.tapDescription(HomeBalanceCycle.HiddenLabel)
            .awaitDescription("Balance: 500 sats")
    }

    @Test fun oneSwitchHidesEveryUnitPage() {
        val app = AppTestFixture.launch(FixtureMode.FundedWithHistory, supportedUnits = listOf("sat", "usd"))
        fixture = app
        val fake = app.fakeGateway!!
        runBlocking {
            fake.ensureWallet(FakeWalletGateway.TestMintUrl, "usd")
            fake.setUnitBalance(FakeWalletGateway.TestMintUrl, "usd", 1000)
            app.container.walletManager.refreshBalance()
        }
        robot.awaitTag(UiTestTags.WalletScreen)
        // The pager composes both pages; the USD one sits off-screen to the right.
        awaitDescriptionCount("Balance: \$10.00", 1)
        robot.tapDescription("Balance: 500 sats")
        // One switch hides both pages, so swiping while private stays private.
        awaitDescriptionCount(HomeBalanceCycle.HiddenLabel, 2)
        // The USD page cycles unit ↔ private and names its own unit.
        compose.onNode(hasClickLabel("Show balance in USD"))
            .performSemanticsAction(SemanticsActions.OnClick)
        awaitDescriptionCount("Balance: \$10.00", 1)
        robot.awaitDescription("Balance: 500 sats")
    }

    private fun awaitDescriptionCount(description: String, count: Int) {
        compose.waitUntil(DefaultTimeout) {
            compose.onAllNodesWithContentDescription(description, useUnmergedTree = true)
                .fetchSemanticsNodes().size == count
        }
    }

    private fun enableUsd(app: LaunchedFixture) {
        app.scenario.onActivity {
            app.container.settingsManager.setShowFiatBalance(true)
            app.container.settingsManager.setBitcoinPriceCurrency("USD")
            app.container.priceService.syncFromSettings(refresh = true)
        }
        compose.waitUntil(DefaultTimeout) { app.container.priceService.state.value.btcPrice == 100_000.0 }
    }

    /** The fixture's 500 sat balance and its 120/30 sat history never show while private. */
    private fun assertNoFixtureAmounts() {
        for (amount in listOf("500", "120", "₿30", "\$0.")) {
            compose.onAllNodes(showsOrReads(amount), useUnmergedTree = true).assertCountEquals(0)
        }
    }

    private fun showsOrReads(fragment: String) = SemanticsMatcher("shows or reads \"$fragment\"") { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        (texts + descriptions).any { it.contains(fragment) }
    }

    private fun hasClickLabel(label: String) = SemanticsMatcher("click label is \"$label\"") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private companion object { const val DefaultTimeout = 10_000L }
}
