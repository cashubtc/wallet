package org.cashu.wallet.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.cashu.wallet.ui.setCashuContent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsRowsComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun navRowHandlesCompactWidthAndClick() {
        var clicks = 0

        compose.setCashuContent(fontScale = 2f) {
            Column(Modifier.width(240.dp)) {
                NavRow(
                    title = "Backup and recovery words",
                    subtitle = "Show seed after authentication",
                    trailingValue = "Enabled",
                    leadingIcon = Icons.Outlined.Lock,
                    modifier = Modifier.testTag("backupRow"),
                    onClick = { clicks += 1 },
                )
            }
        }

        compose.onNodeWithText("Backup and recovery words").assertIsDisplayed()
        compose.onNodeWithText("Show seed after authentication").assertIsDisplayed()
        compose.onNodeWithTag("backupRow").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun toggleRowExposesSwitchStateAndTogglesFromWholeRow() {
        var checked = false

        compose.setCashuContent(fontScale = 2f) {
            ToggleRow(
                title = "Privacy mode",
                subtitle = "Hide balances until tapped",
                checked = checked,
                onCheckedChange = { checked = it },
                modifier = Modifier
                    .width(260.dp)
                    .testTag("privacyToggle"),
            )
        }

        compose.onNodeWithText("Privacy mode").assertIsDisplayed()
        compose.onNodeWithTag("privacyToggle").assertIsOff()
        compose.onNodeWithTag("privacyToggle").performClick()
        compose.onNodeWithTag("privacyToggle").assertIsOn()
    }
}
