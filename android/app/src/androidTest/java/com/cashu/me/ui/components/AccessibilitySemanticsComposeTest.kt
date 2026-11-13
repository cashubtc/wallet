package com.cashu.me.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.Core.AmountDisplayPrimary
import com.cashu.me.Core.AmountDisplayText
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.Core.HomeBalanceCycle
import com.cashu.me.Models.CashuRequest
import com.cashu.me.Models.CashuRequestPayment
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import com.cashu.me.ui.setCashuContent
import java.util.Locale
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
        var rowClicks = 0
        var privacyEnabled = true

        compose.setCashuContent {
            Column(
                Modifier
                    .width(360.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                BalanceDisplay(
                    amount = AmountDisplayText(
                        primary = "42 sat",
                        secondary = "\$0.01",
                        effectivePrimary = AmountDisplayPrimary.Sats,
                    ),
                    modifier = Modifier.testTag("balanceDisplay"),
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

        compose.onNodeWithTag("balanceDisplay")
            .assertIsDisplayed()
            .assertHasNoClickAction()
        compose.onNodeWithContentDescription("Balance: 42 sat")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("QR code. Long press for copy and share options.")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
            .performTouchInput { longClick() }
        compose.onNodeWithText("Copy").assertIsDisplayed()
        compose.onNodeWithText("Share").assertIsDisplayed()
        compose.onNodeWithText("Copy").performClick()
        compose.onNodeWithContentDescription("Delete. Long press to clear.")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHasClickAction()
        compose.onNodeWithContentDescription("Received ecash, Incoming, Completed, +21 sat, \$0.01, Today")
            .performScrollTo()
            .assertHasClickAction()
            .performClick()
        compose.onNodeWithTag("privacyToggle")
            .performScrollTo()
            .assertHasClickAction()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ToggleableState,
                    ToggleableState.On,
                ),
            )
            .performClick()

        compose.runOnIdle {
            assertEquals(1, rowClicks)
            assertFalse(privacyEnabled)
        }
    }

    @Test
    fun balanceAccessibilityNamesThePreferredPrimaryAmount() {
        compose.setCashuContent {
            BalanceDisplay(
                amount = AmountDisplayText(
                    primary = "\$20.00",
                    secondary = "100,000 sat",
                    effectivePrimary = AmountDisplayPrimary.Fiat,
                ),
            )
        }

        compose.onNodeWithContentDescription("Balance: \$20.00")
            .assertIsDisplayed()
        compose.onNodeWithText("100,000 sat")
            .assertIsDisplayed()
    }

    @Test
    fun balanceIsTappableWithoutFiatAndNamesWhereTheTapGoes() {
        var taps = 0
        compose.setCashuContent {
            BalanceDisplay(
                amount = AmountDisplayText(
                    primary = "42 sat",
                    secondary = null,
                    effectivePrimary = AmountDisplayPrimary.Sats,
                ),
                onPrimaryClick = { taps += 1 },
                primaryClickLabel = "Hide balance",
            )
        }

        compose.onNodeWithContentDescription("Balance: 42 sat")
            .assertIsDisplayed()
            .assertHasClickAction()
            .assert(hasClickLabel("Hide balance"))
            .performClick()
        compose.runOnIdle { assertEquals(1, taps) }
    }

    @Test
    fun hiddenBalanceReadsHiddenAndShowsNoDigits() {
        compose.setCashuContent {
            BalanceDisplay(
                amount = AmountFormatter.HiddenBalance,
                receivedDelta = AmountFormatter.HiddenDelta,
                onPrimaryClick = {},
                primaryClickLabel = "Show balance in sats",
                hidden = true,
                modifier = Modifier.testTag("hiddenBalance"),
            )
        }

        compose.onNodeWithContentDescription(HomeBalanceCycle.HiddenLabel)
            .assertIsDisplayed()
            .assert(hasClickLabel("Show balance in sats"))
        // The receive beat still lands, with its digits masked.
        compose.onNodeWithText(AmountFormatter.HiddenDelta, useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(within("hiddenBalance") and hasDigits(), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    @Test
    fun hiddenRowsReadAmountHiddenAndKeepTheirHeight() {
        val tx = WalletTransaction(
            id = "tx-hidden",
            amount = 21,
            type = TransactionType.Incoming,
            kind = TransactionKind.Ecash,
            dateEpochMillis = 0L,
            status = TransactionStatus.Completed,
        )
        val visible = TransactionRowModel(
            transaction = tx,
            title = "Received ecash",
            timestamp = "Today",
            primaryAmount = "21 sat",
            secondaryAmount = "\$0.01",
        )
        val request = CashuRequest(
            id = "req-hidden",
            encoded = "creqA-test",
            amount = 21,
            receivedPayments = listOf(CashuRequestPayment("tx-hidden", 21, 0L)),
        )
        compose.setCashuContent {
            Column(Modifier.width(360.dp)) {
                TransactionRow(model = visible, onClick = {}, modifier = Modifier.testTag("visibleRow"))
                TransactionRow(
                    model = visible.copy(amountHidden = true),
                    onClick = {},
                    modifier = Modifier.testTag("hiddenRow"),
                )
                CashuRequestRow(
                    request = request,
                    timestamp = "Today",
                    primaryAmountText = "21 sat",
                    secondaryAmountText = "\$0.01",
                    onClick = {},
                    amountHidden = true,
                    modifier = Modifier.testTag("hiddenRequest"),
                )
            }
        }

        compose.onNodeWithContentDescription("Received ecash, Incoming, Completed, Amount hidden, Today")
            .assertIsDisplayed()
        // The asterisks stand alone: no sign, whatever the direction.
        compose.onAllNodes(within("hiddenRow") and hasText("****"), useUnmergedTree = true)
            .assertCountEquals(1)
        compose.onAllNodes(within("hiddenRow") and hasDigits(), useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onAllNodes(within("hiddenRequest") and hasDigits(), useUnmergedTree = true)
            .assertCountEquals(0)
        compose.onNodeWithTag("hiddenRequest")
            .assert(hasContentDescriptionPart(AmountFormatter.HiddenAmountLabel))
        assertEquals(
            compose.onNodeWithTag("visibleRow").getBoundsInRoot().height,
            compose.onNodeWithTag("hiddenRow").getBoundsInRoot().height,
        )
    }

    private fun hasClickLabel(label: String) = SemanticsMatcher("click label is \"$label\"") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label == label
    }

    private fun hasDigits() = SemanticsMatcher("shows or reads a digit") { node ->
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val descriptions = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        (texts + descriptions).any { text -> text.any(Char::isDigit) }
    }

    private fun hasContentDescriptionPart(part: String) = SemanticsMatcher("description includes \"$part\"") { node ->
        node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().any { it.contains(part) }
    }

    private fun within(tag: String) = hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))
}

