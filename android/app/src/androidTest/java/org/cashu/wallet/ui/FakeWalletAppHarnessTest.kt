package org.cashu.wallet.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FakeWalletAppHarnessTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun fakeWalletShellNavigatesTabsAndPushedRoutesWithoutRealServices() {
        compose.setCashuContent {
            FakeWalletApp()
        }

        compose.onNodeWithText("42 sats").assertIsDisplayed()

        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Received ecash").assertIsDisplayed()
        compose.onNodeWithText("Received ecash").performClick()
        compose.onNodeWithText("Transaction detail").assertIsDisplayed()
        compose.onAllNodesWithText("Wallet").assertCountEquals(0)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Received ecash").assertIsDisplayed()

        compose.onNodeWithText("Mints").performClick()
        compose.onNodeWithText("Fake Mint active").assertIsDisplayed()
        compose.onNodeWithText("Fake Mint active").performClick()
        compose.onNodeWithText("Mint detail").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Backup Mint").assertIsDisplayed()
    }

    @Test
    fun fakeWalletShellCoversSendReceiveSettingsAndOverlays() {
        val actionLog = FakeWalletActionLog()
        compose.setCashuContent {
            FakeWalletApp(container = FakeWalletContainer(actionLog = actionLog))
        }

        compose.onNodeWithText("Send").performClick()
        compose.onNodeWithText("Unified send flow").assertIsDisplayed()
        compose.onNodeWithText("Send ecash").performClick()
        compose.onNodeWithText("P2PK lock field").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Receive").performClick()
        compose.onNodeWithText("Paste or scan a token").assertIsDisplayed()
        compose.onNodeWithText("New Request").performClick()
        compose.onNodeWithText("Method picker").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Locked ecash").performClick()
        compose.onNodeWithText("NUT-10 locked receive request").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Backup & Restore").performClick()
        compose.onNodeWithText("Backup reveal auth and restore entry").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Nostr").performClick()
        compose.onNodeWithText("Nostr reveal auth and relay validation").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("Wallet").performClick()
        compose.onNodeWithText("Scan").performClick()
        compose.onNodeWithText("Ready for QR payloads").assertIsDisplayed()
        compose.onNodeWithText("Close scanner").performClick()
        compose.onNodeWithText("Contactless").performClick()
        compose.onNodeWithText("Ready for NFC payloads").assertIsDisplayed()
        compose.onNodeWithText("Close contactless").performClick()

        compose.runOnIdle {
            assertEquals(listOf("scan", "contactless"), actionLog.snapshot())
        }
    }
}
