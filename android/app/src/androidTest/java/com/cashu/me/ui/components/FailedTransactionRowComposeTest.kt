package com.cashu.me.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.text.TextLayoutResult
import org.junit.Assert.assertEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.Core.TransactionDisplay
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import com.cashu.me.ui.setCashuContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FailedTransactionRowComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun failedPaymentUsesXAndPendingAmountColorWithoutAStatusLine() = verifyFailedRow(dark = false)

    @Test fun failedPaymentUsesXAndPendingAmountColorInDarkMode() = verifyFailedRow(dark = true)

    private fun verifyFailedRow(dark: Boolean) {
        var failureColor = Color.Unspecified
        var completedColor = Color.Unspecified
        var pendingColor = Color.Unspecified
        compose.setCashuContent(darkTheme = dark) {
            failureColor = MaterialTheme.colorScheme.onSurfaceVariant
            pendingColor = MaterialTheme.colorScheme.onSurfaceVariant
            completedColor = MaterialTheme.colorScheme.onSurface
            Column {
                for (status in listOf(TransactionStatus.Failed, TransactionStatus.Completed, TransactionStatus.Pending)) {
                    val tx = WalletTransaction(
                        id = status.name, amount = 2100, type = TransactionType.Outgoing,
                        kind = TransactionKind.Lightning, dateEpochMillis = 0, status = status,
                    )
                    TransactionRow(
                        model = TransactionRowModel(tx, TransactionDisplay.title(tx), "Today", "2,100 sat", null),
                        onClick = {},
                    )
                }
            }
        }
        compose.onAllNodesWithTag("transaction-failure-icon", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("transaction-failure-icon", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Failed", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithText("Completed", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Lightning paid, Outgoing, Failed, 2,100 sat, Today")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Lightning paid, Outgoing, Completed, 2,100 sat, Today")
            .assertIsDisplayed()
        val failed = compose.onNodeWithContentDescription("Lightning paid, Outgoing, Failed, 2,100 sat, Today")
        val completed = compose.onNodeWithContentDescription("Lightning paid, Outgoing, Completed, 2,100 sat, Today")
        assertEquals(completed.fetchSemanticsNode().boundsInRoot.height,
            failed.fetchSemanticsNode().boundsInRoot.height, 1f)
        val amounts = compose.onAllNodesWithText("2,100 sat", useUnmergedTree = true)
        for ((index, expected) in listOf(failureColor, completedColor, pendingColor).withIndex()) {
            val layouts = mutableListOf<TextLayoutResult>()
            amounts[index].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(expected, layouts.single().layoutInput.style.color)
        }
    }
}
