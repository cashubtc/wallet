package com.cashu.me.ui.receive.nfc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.Core.NfcReceive.NfcReceivePhase
import com.cashu.me.Core.NfcReceive.NfcReceiveState
import com.cashu.me.ui.setCashuContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NfcReceiveIndicatorComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun missingAmountSurfaceHugsContentAndIsCentered() {
        val message = "Set an amount to receive by tap."

        compose.setCashuContent {
            Box(Modifier.width(360.dp)) {
                NfcReceiveIndicatorContent(
                    state = NfcReceiveState(
                        phase = NfcReceivePhase.NeedsAmount,
                        message = message,
                    ),
                    modifier = Modifier.testTag("nfcIndicator"),
                )
            }
        }

        compose.onNodeWithText(message).assertIsDisplayed()
        assertSurfaceHugsContentAndIsCentered()
        assertNoLegacyNavigationCopy()
    }

    @Test
    fun readySurfaceHugsContentAndIsCentered() {
        val message = "Ask the sender to hold their phone near yours."

        compose.setCashuContent {
            Box(Modifier.width(360.dp)) {
                NfcReceiveIndicatorContent(
                    state = NfcReceiveState(phase = NfcReceivePhase.Waiting),
                    modifier = Modifier.testTag("nfcIndicator"),
                )
            }
        }

        compose.onNodeWithText(message).assertIsDisplayed()
        assertSurfaceHugsContentAndIsCentered()
        assertNoLegacyNavigationCopy()
    }

    @Test
    fun readySurfaceStaysWithinBoundsAtLargeFont() {
        val message = "Ask the sender to hold their phone near yours."

        compose.setCashuContent(fontScale = 2f) {
            Box(Modifier.width(280.dp)) {
                NfcReceiveIndicatorContent(
                    state = NfcReceiveState(phase = NfcReceivePhase.Waiting),
                    modifier = Modifier.testTag("nfcIndicator"),
                )
            }
        }

        compose.onNodeWithText(message).assertIsDisplayed()
        val indicatorBounds = compose.onNodeWithTag("nfcIndicator")
            .fetchSemanticsNode().boundsInRoot
        val surfaceBounds = compose.onNodeWithTag("nfcReceiveIndicatorSurface")
            .fetchSemanticsNode().boundsInRoot

        assertTrue(surfaceBounds.left >= indicatorBounds.left)
        assertTrue(surfaceBounds.right <= indicatorBounds.right)
        compose.onNodeWithTag("nfcIndicator").captureToImage().assertNonEmpty()
    }

    private fun assertSurfaceHugsContentAndIsCentered() {
        val indicatorBounds = compose.onNodeWithTag("nfcIndicator")
            .fetchSemanticsNode().boundsInRoot
        val surfaceBounds = compose.onNodeWithTag("nfcReceiveIndicatorSurface")
            .fetchSemanticsNode().boundsInRoot

        assertTrue(surfaceBounds.width < indicatorBounds.width)
        assertEquals(indicatorBounds.center.x, surfaceBounds.center.x, 1f)
    }

    private fun assertNoLegacyNavigationCopy() {
        compose.onAllNodesWithText("Tap", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("→", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("->", substring = true).assertCountEquals(0)
    }

    private fun ImageBitmap.assertNonEmpty() {
        assertTrue(width > 0)
        assertTrue(height > 0)
    }
}
