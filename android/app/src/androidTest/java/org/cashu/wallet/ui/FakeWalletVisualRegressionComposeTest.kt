package org.cashu.wallet.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FakeWalletVisualRegressionComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun largeFontCoreWalletScreensCaptureNonBlankImages() {
        captureHome()
        captureSend()
        captureSendEcash()
        captureReceiveEcash()
        captureReceiveLightning()
        captureSettings()
        captureSettingsRoute("Nostr", "Nostr reveal auth and relay validation")
        captureSettingsRoute("Locked Ecash", "P2PK key flows and reveal auth")
        captureSettingsRoute("Lightning", "Lightning address rows, mint selection, and claim preferences")
        captureMints()
        captureMintDetail()
        captureTransactionDetail()
    }

    @Test
    fun compactHeightAmountAndOverlayScreensKeepPrimaryActionsVisible() {
        setProbe(width = 320.dp, height = 420.dp, fontScale = 1.5f)
        compose.onNodeWithText("Send").performClick()
        compose.onNodeWithText("Pay").assertIsDisplayed()
        compose.onNodeWithText("Send ecash").assertIsDisplayed()
        captureProbe()

        setProbe(width = 320.dp, height = 420.dp, fontScale = 1.5f)
        compose.onNodeWithText("Receive").performClick()
        compose.onNodeWithText("Accept token").assertIsDisplayed()
        compose.onNodeWithText("New Request").assertIsDisplayed()
        captureProbe()

        setProbe(width = 320.dp, height = 420.dp, fontScale = 1.5f)
        compose.onNodeWithText("Scan").performClick()
        compose.onNodeWithText("Close scanner").assertIsDisplayed()
        captureProbe()

        setProbe(width = 320.dp, height = 420.dp, fontScale = 1.5f)
        compose.onNodeWithText("Contactless").performClick()
        compose.onNodeWithText("Close contactless").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun darkThemeAndWideWidthShellScreensCaptureNonBlankImages() {
        setProbe(width = 840.dp, height = 720.dp, darkTheme = true)
        compose.onNodeWithText("42 sats").assertIsDisplayed()
        captureProbe()

        setProbe(width = 840.dp, height = 720.dp, darkTheme = true)
        compose.onNodeWithText("Mints").performClick()
        compose.onNodeWithText("Discovery search").assertIsDisplayed()
        captureProbe()

        setProbe(width = 840.dp, height = 720.dp, darkTheme = true)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Delete wallet").assertIsDisplayed()
        captureProbe()
    }

    private fun captureHome() {
        setProbe()
        compose.onNodeWithText("42 sats").assertIsDisplayed()
        captureProbe()
    }

    private fun captureSend() {
        setProbe()
        compose.onNodeWithText("Send").performClick()
        compose.onNodeWithText("Amount entry").assertIsDisplayed()
        captureProbe()
    }

    private fun captureSendEcash() {
        setProbe()
        compose.onNodeWithText("Send").performClick()
        compose.onNodeWithText("Send ecash").performClick()
        compose.onNodeWithText("P2PK lock field").assertIsDisplayed()
        captureProbe()
    }

    private fun captureReceiveEcash() {
        setProbe()
        compose.onNodeWithText("Receive").performClick()
        compose.onNodeWithText("Paste token").assertIsDisplayed()
        captureProbe()
    }

    private fun captureReceiveLightning() {
        setProbe()
        compose.onNodeWithText("Receive").performClick()
        compose.onNodeWithText("New Request").performClick()
        compose.onNodeWithText("Method picker").assertIsDisplayed()
        captureProbe()
    }

    private fun captureSettings() {
        setProbe()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("App Lock").assertIsDisplayed()
        captureProbe()
    }

    private fun captureSettingsRoute(row: String, detailText: String) {
        setProbe()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText(row).performClick()
        compose.onNodeWithText(detailText).assertIsDisplayed()
        captureProbe()
    }

    private fun captureMints() {
        setProbe()
        compose.onNodeWithText("Mints").performClick()
        compose.onNodeWithText("Paste mint").assertIsDisplayed()
        captureProbe()
    }

    private fun captureMintDetail() {
        setProbe()
        compose.onNodeWithText("Mints").performClick()
        compose.onNodeWithText("Fake Mint active").performClick()
        compose.onNodeWithText("Full NUT-06 metadata").assertIsDisplayed()
        captureProbe()
    }

    private fun captureTransactionDetail() {
        setProbe()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Received ecash").performClick()
        compose.onNodeWithText("QR, copy, share, and explorer actions").assertIsDisplayed()
        captureProbe()
    }

    private fun setProbe(
        width: Dp = 360.dp,
        height: Dp = 720.dp,
        fontScale: Float = 2f,
        darkTheme: Boolean = false,
    ) {
        compose.setCashuContent(darkTheme = darkTheme, fontScale = fontScale) {
            Box(
                modifier = Modifier
                    .width(width)
                    .height(height)
                    .testTag(ProbeTag),
            ) {
                FakeWalletApp()
            }
        }
    }

    private fun captureProbe() {
        compose.onNodeWithTag(ProbeTag).captureToImage().assertNonBlank()
    }

    private fun ImageBitmap.assertNonBlank() {
        assertTrue("Captured image must have positive dimensions.", width > 0 && height > 0)
        val pixels = toPixelMap()
        val xStep = (width / 8).coerceAtLeast(1)
        val yStep = (height / 8).coerceAtLeast(1)
        var hasVisiblePixel = false
        for (x in 0 until width step xStep) {
            for (y in 0 until height step yStep) {
                if (pixels[x, y].alpha > 0f) {
                    hasVisiblePixel = true
                }
            }
        }
        assertTrue("Captured image must contain visible pixels.", hasVisiblePixel)
    }

    private companion object {
        const val ProbeTag = "visual-probe"
    }
}
