package com.cashu.me.ui.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.test.WalletJourneyRobot
import com.cashu.me.test.fixtures.AppTestFixture
import com.cashu.me.test.fixtures.FixtureMode
import com.cashu.me.ui.testing.UiTestTags
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoverDepositJourneyTest {
    @get:Rule val compose = createEmptyComposeRule()

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
            compose.onNodeWithTag("recoverDepositButton").performScrollTo().performClick()
            compose.onNodeWithText("Recover this deposit?").assertIsDisplayed()
            assertEquals(0, fake.depositRecoveryCalls.get())
            compose.onNodeWithText("Cancel").performClick()
            compose.onNodeWithText("Recover this deposit?").assertDoesNotExist()
            assertEquals(0, fake.depositRecoveryCalls.get())
            assertEquals(seedBefore, fixture.container.walletManager.backupMnemonic())
            compose.onNodeWithTag("recoverDepositButton").performScrollTo().performClick()
            compose.onNode(hasText("Recover deposit") and hasAnyAncestor(isDialog())).performClick()
            compose.waitUntil { fake.depositRecoveryCalls.get() == 1 }
            compose.onNodeWithText("No matching key found.", substring = true).performScrollTo().assertIsDisplayed()
            assertEquals(seedBefore, fixture.container.walletManager.backupMnemonic())
            assertEquals(0L, fixture.container.walletManager.state.value.balance)
        }
    }
}
