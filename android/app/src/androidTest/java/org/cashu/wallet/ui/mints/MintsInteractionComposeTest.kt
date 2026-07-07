package org.cashu.wallet.ui.mints

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.ui.setCashuContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MintsInteractionComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun mintRowClickOpensAndSwipeLeftRequestsRemovalWithoutOpening() {
        var opened = 0
        var removed = 0
        var activated = 0

        compose.setCashuContent {
            SwipeableMintRow(
                mint = mint("Swipe Mint", "https://swipe.example"),
                isActive = false,
                onOpen = { opened += 1 },
                onSetActive = { activated += 1 },
                onRequestRemove = { removed += 1 },
                modifier = Modifier.testTag("mintRow"),
            )
        }

        compose.onNodeWithText("Swipe Mint").assertIsDisplayed()
        compose.onNodeWithTag("mintRow").performClick()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(0, removed)
            assertEquals(0, activated)
        }

        compose.onNodeWithTag("mintRow").performTouchInput { swipeLeft() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, opened)
            assertEquals(1, removed)
            assertEquals(0, activated)
        }
    }

    @Test
    fun mintRowSwipeRightSetsInactiveMintActiveWithoutOpening() {
        var opened = 0
        var removed = 0
        var activated = 0

        compose.setCashuContent {
            SwipeableMintRow(
                mint = mint("Inactive Mint", "https://inactive.example"),
                isActive = false,
                onOpen = { opened += 1 },
                onSetActive = { activated += 1 },
                onRequestRemove = { removed += 1 },
                modifier = Modifier.testTag("mintRow"),
            )
        }

        compose.onNodeWithTag("mintRow").performTouchInput { swipeRight() }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, opened)
            assertEquals(0, removed)
            assertEquals(1, activated)
        }
    }

    @Test
    fun discoveryAddDisablesAfterFirstTapToPreventDoubleSubmit() {
        var adds = 0
        var busy by mutableStateOf(false)

        compose.setCashuContent {
            DiscoveryRow(
                mint = mint("Nostr Mint", "https://nostr.example"),
                isConfigured = false,
                isBusy = busy,
                onAdd = {
                    adds += 1
                    busy = true
                },
                modifier = Modifier.testTag("discoveryRow"),
            )
        }

        compose.onNodeWithText("Add").assertIsEnabled()
        compose.onNodeWithText("Add").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Add").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, adds) }
    }

    @Test
    fun configuredDiscoveryRowShowsAddedStateInsteadOfAddAction() {
        compose.setCashuContent {
            DiscoveryRow(
                mint = mint("Configured Mint", "https://configured.example"),
                isConfigured = true,
                isBusy = false,
                onAdd = {},
                modifier = Modifier.testTag("discoveryRow"),
            )
        }

        compose.onNodeWithText("Configured Mint").assertIsDisplayed()
        compose.onNodeWithText("Added").assertIsDisplayed()
    }

    private fun mint(name: String, url: String): MintInfo =
        MintInfo(
            url = url,
            name = name,
            description = "Mint used by interaction tests.",
            balance = 21,
        )
}
