package com.cashu.me.ui.receive

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.ui.setCashuContent
import com.cashu.me.ui.settings.QrDetailSheet
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LightningAddressReceiptComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun confirmedPaymentReplacesQrActionsAndDoneDismissesSheet() {
        val receivedAmount = mutableStateOf<String?>(null)
        var dismissals = 0
        compose.setCashuContent(darkTheme = true) {
            QrDetailSheet(
                title = "Lightning Address", content = "receiver@example.com",
                onDismiss = { dismissals++ }, receivedAmount = receivedAmount.value,
            )
        }
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        compose.onNodeWithText("Payment received").assertDoesNotExist()
        capture("address-dark")

        compose.runOnIdle { receivedAmount.value = "21 sats" }
        compose.onNodeWithText("Payment received", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("21 sats", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Copy").assertDoesNotExist()
        compose.onNodeWithText("Share").assertDoesNotExist()
        capture("received-dark")
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, dismissals) }
    }

    @Test
    fun largeTextKeepsAmountAndDoneReachable() {
        compose.setCashuContent(fontScale = 2f) {
            QrDetailSheet(title = "Lightning Address", content = "receiver@example.com",
                onDismiss = {}, receivedAmount = "21,000,000 sats")
        }
        compose.onNodeWithText("21,000,000 sats", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Done").performScrollTo().assertIsDisplayed()
        capture("received-large-text")
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
