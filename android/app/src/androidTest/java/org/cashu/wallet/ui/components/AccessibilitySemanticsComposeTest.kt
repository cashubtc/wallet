package org.cashu.wallet.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.cashu.wallet.Core.AmountDisplayPrimary
import org.cashu.wallet.Core.AmountDisplayText
import org.cashu.wallet.Models.TransactionKind
import org.cashu.wallet.Models.TransactionStatus
import org.cashu.wallet.Models.TransactionType
import org.cashu.wallet.Models.WalletTransaction
import org.cashu.wallet.ui.setCashuContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccessibilitySemanticsComposeTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun criticalWalletControlsExposeTalkBackLabelsAndActions() {
        var balanceToggles = 0
        var rowClicks = 0
        var privacyEnabled = true

        compose.setCashuContent {
            Column(Modifier.width(360.dp)) {
                BalanceDisplay(
                    amount = AmountDisplayText(
                        primary = "42 sat",
                        secondary = "\$0.01",
                        effectivePrimary = AmountDisplayPrimary.Sats,
                    ),
                    modifier = Modifier.semantics {
                        role = Role.Button
                        contentDescription = "Balance 42 sat, \$0.01. Double tap to toggle primary display."
                    },
                    onTogglePrimary = {
                        balanceToggles += 1
                    },
                )
                QrCard(
                    content = "cashuA-test-token",
                    modifier = Modifier.testTag("qrCard"),
                    staticOnly = true,
                )
                NumberPad(
                    amount = "123",
                    onAmountChange = {},
                    modifier = Modifier.testTag("numberPad"),
                )
                TransactionRow(
                    model = TransactionRowModel(
                        transaction = WalletTransaction(
                            id = "tx-accessibility",
                            amount = 21,
                            type = TransactionType.Incoming,
                            kind = TransactionKind.Ecash,
                            dateEpochMillis = 0L,
                            status = TransactionStatus.Completed,
                        ),
                        title = "Received ecash",
                        timestamp = "Today",
                        primaryAmount = "21 sat",
                        secondaryAmount = "\$0.01",
                    ),
                    onClick = { rowClicks += 1 },
                    modifier = Modifier.testTag("transactionRow"),
                )
                ToggleRow(
                    title = "Privacy mode",
                    subtitle = "Hide balances until tapped",
                    checked = privacyEnabled,
                    onCheckedChange = { privacyEnabled = it },
                    modifier = Modifier.testTag("privacyToggle"),
                )
            }
        }

        compose.onNodeWithContentDescription("Balance 42 sat, \$0.01. Double tap to toggle primary display.")
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithContentDescription("QR code. Long press for copy and share options.")
            .assertIsDisplayed()
            .assertHasClickAction()
            .performTouchInput { longClick() }
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete. Long press to clear.")
            .assertIsDisplayed()
            .assertHasClickAction()
        compose.onNodeWithContentDescription("Received ecash, Incoming, Completed, +21 sat, \$0.01, Today")
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag("privacyToggle")
            .assertHasClickAction()
            .performClick()

        compose.runOnIdle {
            assertEquals(1, balanceToggles)
            assertEquals(1, rowClicks)
            assertFalse(privacyEnabled)
        }
    }
}
