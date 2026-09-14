package com.cashu.me.ui.receive.nfc

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.test.core.graphics.writeToTestStorage
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Before
import org.junit.After
import com.cashu.me.Core.NfcReceive.NfcReceivePhase
import com.cashu.me.Core.NfcReceive.NfcReceiveState
import com.cashu.me.ui.setCashuContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NfcReceiveSuccessTransitionComposeTest {
    @get:Rule
    val compose = createComposeRule()

    private var previousAnimationScale = "1"

    @Before
    fun enableNormalMotion() {
        // Gradle disables system animations for instrumentation by default.
        // Exercise the production spinner and morph, then restore that setting.
        previousAnimationScale = shell("settings get global animator_duration_scale").trim()
        shell("settings put global animator_duration_scale 1")
    }

    @After
    fun restoreMotionSetting() {
        shell("settings put global animator_duration_scale $previousAnimationScale")
    }

    private fun shell(command: String): String =
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }

    @Test
    fun transferAndReceiptKeepTheirPositionsInLightMode() = verifyTransferLayout(darkTheme = false)

    @Test
    fun transferAndReceiptKeepTheirPositionsInDarkMode() = verifyTransferLayout(darkTheme = true)

    private fun verifyTransferLayout(darkTheme: Boolean) {
        var state by mutableStateOf(NfcReceiveState(NfcReceivePhase.Connected))
        var completions = 0
        compose.setCashuContent(darkTheme = darkTheme) {
            Surface(modifier = Modifier.fillMaxSize().testTag("nfc-transfer-screen")) {
                NfcReceiveOverlayContent(
                    state = state,
                    successAmountLabel = "19 sat",
                    successMintName = "Minibits mint",
                    onSuccessDone = { completions++ },
                    onRetry = {},
                )
            }
        }
        compose.waitForIdle()
        val iconBounds = compose.onNodeWithTag("payment-status-icon").fetchSemanticsNode().boundsInRoot
        val titleTop = compose.onNodeWithText("Keep phones together").fetchSemanticsNode().boundsInRoot.top
        val detailTop = compose.onNodeWithText("Do not move either phone until the transfer finishes.")
            .fetchSemanticsNode().boundsInRoot.top

        val phases = listOf(
            NfcReceivePhase.Receiving to "Keep phones together",
            NfcReceivePhase.Validating to "Checking payment",
            NfcReceivePhase.Redeeming to "Securing ecash",
            NfcReceivePhase.Converting to "Moving to your default mint",
            NfcReceivePhase.Success to "Payment Received!",
        )
        for ((phase, title) in phases) {
            compose.runOnIdle {
                state = NfcReceiveState(phase = phase, amount = 19, settlementMint = "https://mint.example")
            }
            compose.waitForIdle()
            compose.onNodeWithText(title).assertIsDisplayed()
            assertEquals("Icon moved during $phase", iconBounds,
                compose.onNodeWithTag("payment-status-icon").fetchSemanticsNode().boundsInRoot)
            assertEquals("Title moved during $phase", titleTop,
                compose.onNodeWithText(title).fetchSemanticsNode().boundsInRoot.top, 1f)
            if (phase in setOf(NfcReceivePhase.Receiving, NfcReceivePhase.Redeeming, NfcReceivePhase.Success)) {
                compose.onNodeWithTag("nfc-transfer-screen").captureToImage().asAndroidBitmap()
                    .writeToTestStorage("nfc-${if (darkTheme) "dark" else "light"}-$phase")
            }
            if (phase != NfcReceivePhase.Success) {
                compose.onNodeWithText("Done").assertDoesNotExist()
                val detail = if (phase == NfcReceivePhase.Receiving) {
                    "Do not move either phone until the transfer finishes."
                } else {
                    "Transfer complete — you can move the phones apart."
                }
                assertEquals("Instructions moved during $phase", detailTop,
                    compose.onNodeWithText(detail).fetchSemanticsNode().boundsInRoot.top, 1f)
            }
        }
        compose.onNodeWithContentDescription("Success").assertIsDisplayed()
        compose.onNodeWithContentDescription("Amount: 19 sat", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Minibits mint").assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, completions) }
    }
}
