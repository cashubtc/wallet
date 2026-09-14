package com.cashu.me.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.graphics.writeToTestStorage
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.ui.setCashuContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaymentStatusAccessibilityComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun changingStatusTitleIsAPoliteLiveRegion() {
        compose.setCashuContent {
            PaymentStatusScreen(
                phase = PaymentStatusPhase.Success,
                title = "Payment sent",
                onDone = {},
            )
        }

        val semantics = compose.onNodeWithText("Payment sent")
            .fetchSemanticsNode()
            .config

        assertEquals(LiveRegionMode.Polite, semantics[SemanticsProperties.LiveRegion])
    }
    @Test
    fun largeTextStacksReadOnlyAndEditableValuesWithoutTruncation() {
        val mint = "A receiving mint with a deliberately long descriptive name"
        val fee = "21,000,000 sat"
        compose.setCashuContent(fontScale = 2f) {
            Surface(Modifier.testTag("accessible-payment-details")) {
                Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                    InspectorRow("Mint", mint)
                    InspectorRow("Fees", fee, editable = true, onClick = {})
                }
            }
        }
        compose.onNodeWithTag("accessible-payment-details").captureToImage()
            .asAndroidBitmap().writeToTestStorage("payment-details-large-text")
        for ((label, value) in listOf("Mint" to mint, "Fees" to fee)) {
            val labelNode = compose.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
            val valueNode = compose.onNodeWithText(value, useUnmergedTree = true)
            assertTrue(valueNode.fetchSemanticsNode().boundsInRoot.top >= labelNode.boundsInRoot.bottom)
            val layouts = mutableListOf<TextLayoutResult>()
            valueNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue(layouts.isNotEmpty())
            val layout = layouts.single()
            assertEquals(value.length, layout.getLineEnd(layout.lineCount - 1, visibleEnd = true))
            assertFalse("Full $label value must remain readable", layout.hasVisualOverflow)
        }
    }

    @Test
    fun longMintNameStacksAtDefaultTextSize() {
        val mint = "A receiving mint with a deliberately long descriptive name"
        compose.setCashuContent {
            Surface {
                Column(Modifier.width(320.dp)) { InspectorRow("Mint", mint) }
            }
        }
        val label = compose.onNodeWithText("Mint", useUnmergedTree = true).fetchSemanticsNode()
        val value = compose.onNodeWithText(mint, useUnmergedTree = true)
        assertTrue(value.fetchSemanticsNode().boundsInRoot.top >= label.boundsInRoot.bottom)
        val layouts = mutableListOf<TextLayoutResult>()
        value.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertFalse(layouts.single().hasVisualOverflow)
    }

    @Test
    fun successAmountFadesInDuringTheProcessingMorph() {
        val phase = mutableStateOf(PaymentStatusPhase.Processing)
        compose.mainClock.autoAdvance = false
        compose.setCashuContent(darkTheme = true) {
            Surface {
                PaymentStatusScreen(phase = phase.value,
                    title = if (phase.value == PaymentStatusPhase.Processing) "Processing" else "Payment sent",
                    successAmount = "27,237 sat", onDone = {})
            }
        }
        compose.mainClock.advanceTimeBy(1_000)
        compose.runOnIdle { phase.value = PaymentStatusPhase.Success }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(32)
        fun amountBrightness(): Float {
            val pixels = compose.onNodeWithContentDescription("Amount: 27,237 sat", useUnmergedTree = true)
                .captureToImage().toPixelMap()
            var sum = 0f
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) sum += pixels[x, y].red
            return sum / (pixels.width * pixels.height)
        }
        val entering = amountBrightness()
        compose.mainClock.advanceTimeBy(700)
        val settled = amountBrightness()
        assertTrue("Amount must fade with the status, not appear at full opacity", settled > entering + 0.02f)
    }

}
