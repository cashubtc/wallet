package org.cashu.wallet.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FakeWalletParityComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun homeUserStoryCoversBalanceUnitsRecentEmptyAndPrimaryActions() {
        compose.setCashuContent {
            FakeWalletApp()
        }

        compose.onNodeWithText("42 sats").assertIsDisplayed()
        compose.onNodeWithText("+21 sats").assertIsDisplayed()
        compose.onNodeWithText("Unit: sat").assertIsDisplayed()
        compose.onNodeWithText("Hide balance").performClick()
        compose.onNodeWithText("Balance hidden").assertIsDisplayed()
        compose.onNodeWithText("Show balance").performClick()
        compose.onNodeWithText("Next unit").performClick()
        compose.onNodeWithText("Unit: usd").assertIsDisplayed()
        compose.onNodeWithText("Recent: Received ecash").assertIsDisplayed()
        compose.onNodeWithText("Send").performClick()
        compose.onNodeWithText("Destination input").assertIsDisplayed()
    }

    @Test
    fun homeEmptyStateIsAvailableWithoutHistory() {
        compose.setCashuContent {
            FakeWalletApp(container = FakeWalletContainer(historyItems = emptyList()))
        }

        compose.onNodeWithText("No History Yet").assertIsDisplayed()
    }

    @Test
    fun onboardingCreateStoryIsCovered() {
        compose.setCashuContent {
            FakeOnboardingFlow()
        }

        compose.onNodeWithText("Create wallet").performClick()
        compose.onNodeWithText("Seed phrase").assertIsDisplayed()
        compose.onNodeWithText("I saved my seed").performClick()
        compose.onNodeWithText("First mint").assertIsDisplayed()
        compose.onNodeWithText("Skip first mint").performClick()
        compose.onNodeWithText("Wallet ready").assertIsDisplayed()
    }

    @Test
    fun onboardingRestoreStoryIsCovered() {
        compose.setCashuContent {
            FakeOnboardingFlow()
        }

        compose.onNodeWithText("Restore wallet").performClick()
        compose.onNodeWithText("Restore method").assertIsDisplayed()
        compose.onNodeWithText("Seed restore").performClick()
        compose.onNodeWithText("Restore seed input").assertIsDisplayed()
        compose.onNodeWithText("Continue restore").performClick()
        compose.onNodeWithText("Staged mint restore progress").assertIsDisplayed()
        compose.onNodeWithText("Show restore results").performClick()
        compose.onNodeWithText("Staged mint restore results").assertIsDisplayed()
    }

    @Test
    fun sendStoryCoversCoreStates() {
        compose.setCashuContent {
            FakeWalletApp()
        }

        compose.onNodeWithText("Send").performClick()
        compose.onNodeWithText("Amount entry").assertIsDisplayed()
        compose.onNodeWithText("Load quote").performClick()
        compose.onNodeWithText("Quote loading").assertIsDisplayed()
        compose.onNodeWithText("Switch mint").performClick()
        compose.onNodeWithText("Mint switched").assertIsDisplayed()
        compose.onNodeWithText("Pay").performClick()
        compose.onNodeWithText("Success status").assertIsDisplayed()
        compose.onNodeWithText("Fail payment").performClick()
        compose.onNodeWithText("Failure status").assertIsDisplayed()
        compose.onNodeWithText("Send ecash").performClick()
        compose.onNodeWithText("P2PK lock field").assertIsDisplayed()
        compose.onNodeWithText("Generate token").performClick()
        compose.onNodeWithText("Generated token").assertIsDisplayed()
    }

    @Test
    fun receiveEcashStoryCoversCoreStates() {
        compose.setCashuContent {
            FakeWalletApp()
        }

        compose.onNodeWithText("Receive").performClick()
        compose.onNodeWithText("Paste token").assertIsDisplayed()
        compose.onNodeWithText("Locked token: Your key").assertIsDisplayed()
        compose.onNodeWithText("Unknown mint warning").assertIsDisplayed()
        compose.onNodeWithText("Review").performClick()
        compose.onNodeWithText("Review token").assertIsDisplayed()
        compose.onNodeWithText("Receive later").performClick()
        compose.onNodeWithText("Receive later saved").assertIsDisplayed()
        compose.onNodeWithText("Accept token").performClick()
        compose.onNodeWithText("Receive success").assertIsDisplayed()
        compose.onNodeWithText("Fail receive").performClick()
        compose.onNodeWithText("Receive failure").assertIsDisplayed()
        compose.onNodeWithText("New Request").performClick()
        compose.onNodeWithText("Receive Lightning").assertIsDisplayed()
    }

    @Test
    fun receiveLightningStoryIsCovered() {
        compose.setCashuContent {
            FakeWalletApp()
        }

        compose.onNodeWithText("Receive").performClick()
        compose.onNodeWithText("New Request").performClick()
        compose.onNodeWithText("Method picker").assertIsDisplayed()
        compose.onNodeWithText("Expiry countdown").assertIsDisplayed()
        compose.onNodeWithText("Reusable invoice").performClick()
        compose.onNodeWithText("BOLT12 reusable invoice").assertIsDisplayed()
        compose.onNodeWithText("On-chain address").performClick()
        compose.onNodeWithText("On-chain address display").assertIsDisplayed()
        compose.onNodeWithText("On-chain observer link").assertIsDisplayed()
    }

    @Test
    fun historyStoryIsCovered() {
        compose.setCashuContent {
            FakeWalletApp()
        }

        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Search history").performClick()
        compose.onNodeWithText("No Results").assertIsDisplayed()
        compose.onNodeWithText("Delete request").performClick()
        compose.onNodeWithText("Received ecash").performClick()
        compose.onNodeWithText("QR, copy, share, and explorer actions").assertIsDisplayed()
    }

    @Test
    fun mintsStoryIsCovered() {
        compose.setCashuContent {
            FakeWalletApp()
        }

        compose.onNodeWithText("Mints").performClick()
        compose.onNodeWithText("Paste mint").assertIsDisplayed()
        compose.onNodeWithText("Discovery search").assertIsDisplayed()
        compose.onNodeWithText("Add discovered mint").performClick()
        compose.onNodeWithText("Discovered mint added").assertIsDisplayed()
        compose.onNodeWithText("Set active Fake Mint").performClick()
        compose.onNodeWithText("Remove Fake Mint").performClick()
    }

    @Test
    fun settingsBackupStoryIsCovered() {
        compose.setCashuContent { FakeWalletApp() }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("App Lock").assertIsDisplayed()
        compose.onNodeWithText("Backup & Restore").performClick()
        compose.onNodeWithText("Backup reveal auth and restore entry").assertIsDisplayed()
    }

    @Test
    fun settingsNostrStoryIsCovered() {
        compose.setCashuContent { FakeWalletApp() }
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Nostr").performClick()
        compose.onNodeWithText("Nostr reveal auth and relay validation").assertIsDisplayed()
    }

    @Test
    fun scannerStoryIsCovered() {
        compose.setCashuContent { FakeWalletApp() }
        compose.onNodeWithText("Scan").performClick()
        compose.onNodeWithText("Permission denied").assertIsDisplayed()
        compose.onNodeWithText("Permission granted").assertIsDisplayed()
        compose.onNodeWithText("Animated UR progress").assertIsDisplayed()
        compose.onNodeWithText("Quick-fill routing").assertIsDisplayed()
        compose.onNodeWithText("Unsupported payload").assertIsDisplayed()
    }
}
