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
import androidx.compose.ui.test.performScrollTo
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
    fun largeFontHomeCaptureIsNonBlank() {
        setProbe()
        compose.onNodeWithText("42 sats").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun largeFontSendCaptureIsNonBlank() {
        setProbe()
        compose.onNodeWithText("Send").performClick()
        compose.onNodeWithText("Amount entry").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun largeFontSendEcashCaptureIsNonBlank() {
        setProbe()
        compose.onNodeWithText("Send").performClick()
        compose.onNodeWithText("Send ecash").performClick()
        compose.onNodeWithText("P2PK lock field").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun largeFontReceiveEcashCaptureIsNonBlank() {
        setProbe()
        compose.onNodeWithText("Receive").performClick()
        compose.onNodeWithText("Paste token").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun largeFontReceiveLightningCaptureIsNonBlank() {
        setProbe()
        compose.onNodeWithText("Receive").performClick()
        compose.onNodeWithText("New Request").performClick()
        compose.onNodeWithText("Method picker").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun largeFontSettingsCaptureIsNonBlank() {
        setProbe()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("App Lock").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun largeFontSettingsNostrCaptureIsNonBlank() {
        setProbe()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Nostr").performClick()
        compose.onNodeWithText("Nostr reveal auth and relay validation").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun largeFontSettingsLockedEcashCaptureIsNonBlank() {
        setProbe()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Locked Ecash").performClick()
        compose.onNodeWithText("P2PK key flows and reveal auth").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun largeFontSettingsLightningCaptureIsNonBlank() {
        setProbe()
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Lightning").performClick()
        compose.onNodeWithText("Lightning address rows, mint selection, and claim preferences").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun largeFontMintsCaptureIsNonBlank() {
        setProbe()
        compose.onNodeWithText("Mints").performClick()
        compose.onNodeWithText("Paste mint").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun largeFontMintDetailCaptureIsNonBlank() {
        setProbe()
        compose.onNodeWithText("Mints").performClick()
        compose.onNodeWithText("Fake Mint active").performClick()
        compose.onNodeWithText("Full NUT-06 metadata").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun largeFontTransactionDetailCaptureIsNonBlank() {
        setProbe()
        compose.onNodeWithText("History").performClick()
        compose.onNodeWithText("Received ecash").performClick()
        compose.onNodeWithText("QR, copy, share, and explorer actions").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun compactHeightSendActionsRemainReachable() {
        setProbe(width = 320.dp, height = 420.dp, fontScale = 1.5f)
        compose.onNodeWithText("Send").performScrollTo().performClick()
        compose.onNodeWithText("Pay").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Send ecash").performScrollTo().assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun compactHeightReceiveActionsRemainReachable() {
        setProbe(width = 320.dp, height = 420.dp, fontScale = 1.5f)
        compose.onNodeWithText("Receive").performScrollTo().performClick()
        compose.onNodeWithText("Accept token").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("New Request").performScrollTo().assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun compactHeightScannerOverlayKeepsCloseVisible() {
        setProbe(width = 320.dp, height = 420.dp, fontScale = 1.5f)
        compose.onNodeWithText("Scan").performScrollTo().performClick()
        compose.onNodeWithText("Close scanner").performScrollTo().assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun compactHeightContactlessOverlayKeepsCloseReachable() {
        setProbe(width = 320.dp, height = 420.dp, fontScale = 1.5f)
        compose.onNodeWithText("Contactless").performScrollTo().performClick()
        compose.onNodeWithText("Close contactless").performScrollTo().assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun darkThemeWideHomeCaptureIsNonBlank() {
        setProbe(width = 840.dp, height = 720.dp, darkTheme = true)
        compose.onNodeWithText("42 sats").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun darkThemeWideMintsCaptureIsNonBlank() {
        setProbe(width = 840.dp, height = 720.dp, darkTheme = true)
        compose.onNodeWithText("Mints").performClick()
        compose.onNodeWithText("Discovery search").assertIsDisplayed()
        captureProbe()
    }

    @Test
    fun darkThemeWideSettingsCaptureIsNonBlank() {
        setProbe(width = 840.dp, height = 720.dp, darkTheme = true)
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Delete wallet").assertIsDisplayed()
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
