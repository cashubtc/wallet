package com.cashu.me.ui.receive

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.ui.setCashuContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LightningAddressReceiptComposeTest {
    @get:Rule val compose = createComposeRule()
    private val address = "npub1" + "q".repeat(58) + "@example.com"

    @Test
    fun paymentKeepsModalBoundsAndDoneDismisses() {
        val receivedAmount = mutableStateOf<String?>(null)
        val presented = mutableStateOf(true)
        var dismissals = 0
        compose.setCashuContent(darkTheme = true) {
            if (presented.value) {
                LightningAddressModal(onDismiss = { dismissals++; presented.value = false }) {
                    LightningAddressReceiveContent(
                        address = address,
                        onDismiss = { dismissals++; presented.value = false },
                        receivedAmount = receivedAmount.value,
                    )
                }
            }
        }
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        compose.onNodeWithText(address).assertIsDisplayed()
        val initialBounds = compose.onNodeWithTag("lightning-address-modal-content")
            .fetchSemanticsNode().boundsInWindow
        capture("address-dark")
        compose.runOnIdle { receivedAmount.value = "1 sat" }
        compose.onNodeWithText("Payment Received!", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Amount: 1 sat", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.onNodeWithText("Share").assertDoesNotExist()
        compose.onNodeWithText(address).assertDoesNotExist()
        assertEquals(initialBounds, compose.onNodeWithTag("lightning-address-modal-content")
            .fetchSemanticsNode().boundsInWindow)
        compose.runOnIdle { assertEquals(0, dismissals) }
        capture("received-dark")
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.onNodeWithTag("lightning-address-modal-content").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun largeTextKeepsAmountAndDoneReachable() {
        compose.setCashuContent {
            LightningAddressModal(onDismiss = {}) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    LightningAddressReceiveContent(address = address,
                        onDismiss = {}, receivedAmount = "21,000,000 sats")
                }
            }
        }
        compose.onNodeWithContentDescription("Amount: 21,000,000 sats", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.onNodeWithText(address).assertDoesNotExist()
        capture("received-large-text")
    }

    @Test
    fun closeDismissesWhileWaiting() {
        val presented = mutableStateOf(true)
        compose.setCashuContent {
            if (presented.value) {
                LightningAddressModal(onDismiss = { presented.value = false }) {
                    LightningAddressReceiveContent(address = address,
                        onDismiss = { presented.value = false })
                }
            }
        }
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithTag("lightning-address-modal-content").assertDoesNotExist()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Keep review captures after Gradle uninstalls the isolated test app.
        val command = instrumentation.uiAutomation.executeShellCommand(
            "screencap -p /sdcard/Download/cashu-npc-$name.png",
        )
        ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
    }
}
