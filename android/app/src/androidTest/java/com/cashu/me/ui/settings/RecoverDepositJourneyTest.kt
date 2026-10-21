package com.cashu.me.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.test.WalletJourneyRobot
import com.cashu.me.test.fixtures.AppTestFixture
import com.cashu.me.test.fixtures.FixtureMode
import com.cashu.me.ui.testing.UiTestTags
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.graphics.writeToTestStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoverDepositJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun choosesAnySavedMintWithoutChangingTheActiveMint() {
        AppTestFixture.launch(FixtureMode.SeededWithMint).use { fixture ->
            val manager = fixture.container.walletManager
            val activeBefore = manager.state.value.activeMint?.url
            // Match AddMintSheet's main-thread scope. Background restores also
            // publish mint balances there; mutating from the instrumentation
            // thread can overwrite their state with a stale mint list.
            runBlocking(Dispatchers.Main) {
                manager.addMint("https://harbor.mint.example")
                manager.addMint("https://north.mint.example")
            }
            val hosts = listOf("mint.test", "harbor.mint.example", "north.mint.example")
            assertEquals(hosts.map { "https://$it" }.toSet(), manager.state.value.mints.map { it.url }.toSet())
            val robot = WalletJourneyRobot(compose)
            robot.awaitTag(UiTestTags.WalletScreen).tapDescription("Settings")
                .scrollToText(UiTestTags.SettingsList, "Backup & Restore").tapText("Backup & Restore")
            compose.onNodeWithText("Recover a deposit").performScrollTo().performClick()
            compose.onNodeWithTag("depositRecoveryMintPicker").performClick()
            compose.onNodeWithText("Choose mint").assertIsDisplayed()
            val mintList = compose.onNodeWithTag("mintPickerList")
            for (host in hosts) {
                // Offscreen lazy rows may not have a semantics node yet.
                mintList.performScrollToNode(hasText(host))
                compose.onNodeWithText(host).assertIsDisplayed()
            }
            recoveryScreenshot("android-mint-picker")
            mintList.performScrollToNode(hasText("harbor.mint.example"))
            compose.onNodeWithText("harbor.mint.example").performClick()
            compose.onNodeWithTag("depositRecoveryMintURL").assertTextContains("https://harbor.mint.example")
            compose.onNodeWithTag("depositRecoveryQuoteID").performTextInput("deposit-2026-001")
            compose.onNodeWithTag("depositRecoveryQuoteID").performImeAction()
            compose.onNodeWithTag("depositRecoveryOptions").performClick()
            compose.onNodeWithText("Search depth").assertIsDisplayed()
            recoveryScreenshot("android-options")
            compose.onNodeWithTag("recoverDepositButton").performClick()
            compose.onNodeWithText("Recover this deposit?").assertIsDisplayed()
            compose.onNodeWithText("Claim this deposit at harbor.mint.example", substring = true).assertIsDisplayed()
            recoveryScreenshot("android-confirmation")
            compose.onNodeWithText("Cancel").performClick()
            assertEquals(0, fixture.fakeGateway!!.depositRecoveryCalls.get())
            assertEquals(hosts.map { "https://$it" }.toSet(), manager.state.value.mints.map { it.url }.toSet())
            assertEquals(hosts.map { "https://$it" }.toSet(), fixture.container.walletStore.loadMints().map { it.url }.toSet())
            assertEquals(activeBefore, manager.state.value.activeMint?.url)
            assertEquals(0L, manager.state.value.balance)
        }
    }

    private fun recoveryScreenshot(name: String) {
        compose.waitForIdle()
        checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()).writeToTestStorage(name)
    }

    @Test fun dedicatedRecoveryWaitsForApprovalAndCancelPreservesTheWallet() {
        AppTestFixture.launch(FixtureMode.SeededWithoutMint).use { fixture ->
            val robot = WalletJourneyRobot(compose)
            val fake = checkNotNull(fixture.fakeGateway)
            val seedBefore = fixture.container.walletManager.backupMnemonic()
            robot.awaitTag(UiTestTags.WalletScreen).tapDescription("Settings")
                .scrollToText(UiTestTags.SettingsList, "Backup & Restore").tapText("Backup & Restore")
            compose.onNodeWithText("Recover a deposit").performScrollTo().performClick()
            compose.onNodeWithTag("depositRecoveryMintURL").performTextInput("https://mint.example")
            compose.onNodeWithTag("depositRecoveryQuoteID").performTextInput("saved-quote-id")
            compose.onNodeWithText("Search depth").assertDoesNotExist()
            compose.onNodeWithTag("depositRecoveryOptions").performClick()
            compose.onNodeWithText("Search depth").assertIsDisplayed()
            compose.onNodeWithTag("depositRecoveryOptions").performClick()
            compose.onNodeWithTag("recoverDepositButton").performScrollTo().performClick()
            compose.onNodeWithText("Recover this deposit?").assertIsDisplayed()
            assertEquals(0, fake.depositRecoveryCalls.get())
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Recover this deposit?").assertDoesNotExist()
            assertEquals(0, fake.depositRecoveryCalls.get())
            assertEquals(seedBefore, fixture.container.walletManager.backupMnemonic())
            compose.onNodeWithTag("recoverDepositButton").performScrollTo().performClick()
            compose.onNodeWithText("Recover").performClick()
            compose.waitUntil { fake.depositRecoveryCalls.get() == 1 }
            compose.onNodeWithText("No matching key.", substring = true).performScrollTo().assertIsDisplayed()
            assertEquals(seedBefore, fixture.container.walletManager.backupMnemonic())
            assertEquals(0L, fixture.container.walletManager.state.value.balance)
        }
    }
}
