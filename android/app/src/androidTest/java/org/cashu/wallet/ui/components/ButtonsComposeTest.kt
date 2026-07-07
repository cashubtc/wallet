package org.cashu.wallet.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.cashu.wallet.ui.setCashuContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ButtonsComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun primaryButtonStaysUsableAtLargeFontAndCompactWidth() {
        var clicks = 0
        val label = "Create reusable request"

        compose.setCashuContent(fontScale = 2f) {
            Column(Modifier.width(220.dp)) {
                PrimaryButton(
                    text = label,
                    modifier = Modifier.testTag("primaryButton"),
                    onClick = { clicks += 1 },
                )
            }
        }

        compose.onNodeWithTag("primaryButton").assertIsDisplayed()
        compose.onNodeWithTag("primaryButton").assertTextEquals(label)
        compose.onNodeWithTag("primaryButton").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun loadingPrimaryButtonDisablesClicks() {
        var clicks = 0

        compose.setCashuContent {
            PrimaryButton(
                text = "Send",
                loading = true,
                modifier = Modifier.testTag("loadingButton"),
                onClick = { clicks += 1 },
            )
        }

        compose.onNodeWithTag("loadingButton").assertIsDisplayed()
        compose.onNodeWithTag("loadingButton").assertIsNotEnabled()
        assertEquals(0, clicks)
    }
}
