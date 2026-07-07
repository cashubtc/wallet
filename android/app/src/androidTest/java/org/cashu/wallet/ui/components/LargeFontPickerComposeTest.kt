package org.cashu.wallet.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.cashu.wallet.Core.NostrSignerType
import org.cashu.wallet.ui.receive.ReceiveMethodOption
import org.cashu.wallet.ui.receive.ReceiveMethodPickerContent
import org.cashu.wallet.ui.setCashuContent
import org.cashu.wallet.ui.settings.NostrSignerPicker
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LargeFontPickerComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nostrSignerPickerRendersAtLargeFontAndCompactWidth() {
        compose.setCashuContent(fontScale = 2f) {
            Column(Modifier.width(260.dp).testTag("nostrSignerPicker")) {
                NostrSignerPicker(
                    signerType = NostrSignerType.Seed,
                    onSelect = {},
                )
            }
        }

        compose.onNodeWithText(NostrSignerType.Seed.displayName).assertIsDisplayed()
        compose.onNodeWithText(NostrSignerType.PrivateKey.displayName).assertIsDisplayed()
        compose.onNodeWithText("Keys are derived from your wallet seed.").assertIsDisplayed()
        compose.onNodeWithTag("nostrSignerPicker").captureToImage().assertNonEmpty()
    }

    @Test
    fun receiveMethodPickerRendersAtLargeFontAndCompactWidth() {
        compose.setCashuContent(fontScale = 2f) {
            Column(Modifier.width(280.dp).testTag("receiveMethodPicker")) {
                ReceiveMethodPickerContent(
                    options = listOf(
                        ReceiveMethodOption.Lightning,
                        ReceiveMethodOption.ReusableAny,
                        ReceiveMethodOption.Onchain,
                    ),
                    selectedOption = ReceiveMethodOption.ReusableAny,
                    onSelect = {},
                )
            }
        }

        compose.onNodeWithText("Receive with").assertIsDisplayed()
        compose.onNodeWithText("Lightning invoice").assertIsDisplayed()
        compose.onNodeWithText("Reusable invoice").assertIsDisplayed()
        compose.onNodeWithText("On-chain address").assertIsDisplayed()
        compose.onNodeWithText("Any amount, paid many times").assertIsDisplayed()
        compose.onNodeWithTag("receiveMethodPicker").captureToImage().assertNonEmpty()
    }

    private fun ImageBitmap.assertNonEmpty() {
        assertTrue(width > 0)
        assertTrue(height > 0)
    }
}
