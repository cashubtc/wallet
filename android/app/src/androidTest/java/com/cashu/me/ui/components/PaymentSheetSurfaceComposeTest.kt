package com.cashu.me.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.graphics.writeToTestStorage
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.ui.setCashuContent
import com.cashu.me.ui.shell.WalletFlow
import com.cashu.me.ui.shell.WalletFlowSheetHost
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PaymentSheetSurfaceComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun darkFlowKeepsTheSheetWhileChangingSurface() = verifySurface(dark = true)
    @Test fun lightFlowKeepsTheSheetWhileChangingSurface() = verifySurface(dark = false)

    private fun verifySurface(dark: Boolean) {
        val compact = mutableStateOf(true)
        var dismissals = 0
        compose.setCashuContent(darkTheme = dark) {
            WalletFlowSheetHost(
                flow = WalletFlow.Send,
                compactContent = compact.value,
                dismissLocked = false,
                onBackdropVisibilityChanged = {},
                onDismissed = { dismissals++ },
                snackbarHostState = remember { SnackbarHostState() },
            ) { _, close ->
                if (compact.value) {
                    Column(Modifier.fillMaxWidth().height(300.dp).testTag("payment-surface-probe")) {
                        SheetHeader(title = "Send")
                        Text("Scan")
                        Text("Ecash")
                        PrimaryButton("Continue", onClick = { compact.value = false })
                    }
                } else {
                    PaymentStatusScreen(
                        modifier = Modifier.testTag("payment-surface-probe"),
                        phase = PaymentStatusPhase.Success,
                        title = "Payment sent",
                        successAmount = "27,237 sat",
                        rows = {
                            InspectorRow("Mint", "Example mint")
                            InspectorRow("Fees", "2 sat")
                        },
                        onDone = close,
                    )
                }
            }
        }
        capture("compact-${if (dark) "dark" else "light"}")
        compose.onNodeWithText("Continue").performClick()
        compose.onNodeWithContentDescription("Amount: 27,237 sat", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Done").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, dismissals) }
        capture("success-${if (dark) "dark" else "light"}")
        // Capture the sheet's Compose window, not a global display coordinate.
        // UiAutomation screenshots on the managed API 35 display returned
        // transparent pixels even though the dialog's content was visible.
        val pixels = compose.onNodeWithTag("payment-surface-probe").captureToImage().toPixelMap()
        assertEquals(if (dark) Color.Black else Color.White,
            pixels[pixels.width / 2, pixels.height * 3 / 4])
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        compose.onNodeWithTag("payment-surface-probe").captureToImage()
            .asAndroidBitmap().writeToTestStorage("cashu-surface-$name")
    }
}
