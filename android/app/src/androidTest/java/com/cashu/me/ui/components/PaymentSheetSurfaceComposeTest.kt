package com.cashu.me.ui.components

import android.graphics.Color
import android.os.ParcelFileDescriptor
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
import androidx.test.platform.app.InstrumentationRegistry
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
                    Column(Modifier.fillMaxWidth().height(300.dp)) {
                        SheetHeader(title = "Send")
                        Text("Scan")
                        Text("Ecash")
                        PrimaryButton("Continue", onClick = { compact.value = false })
                    }
                } else {
                    PaymentStatusScreen(
                        phase = PaymentStatusPhase.Success,
                        title = "Payment sent",
                        successAmount = "27,237 sat",
                        rows = { InspectorRow("Mint", "Example mint") },
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
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        try {
            // Empty lower part of the full-height flow: no text or controls here.
            assertEquals(if (dark) Color.BLACK else Color.WHITE,
                screenshot.getPixel(screenshot.width / 2, screenshot.height * 3 / 4))
        } finally {
            screenshot.recycle()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val command = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
            "screencap -p /sdcard/Download/cashu-surface-$name.png",
        )
        ParcelFileDescriptor.AutoCloseInputStream(command).use { it.readBytes() }
    }
}
