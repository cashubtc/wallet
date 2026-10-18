package com.cashu.me.ui.components

import androidx.compose.foundation.layout.Column
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

    @Test fun failedPaymentHasVisibleLabelAndAccessibleStatus() {
        compose.setCashuContent {
            Column {
                for (status in listOf(TransactionStatus.Failed, TransactionStatus.Completed)) {
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
        compose.onNodeWithText("Failed", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Completed", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithContentDescription("Lightning paid, Outgoing, Failed, 2,100 sat, Today")
            .assertIsDisplayed()
        compose.onNodeWithContentDescription("Lightning paid, Outgoing, Completed, 2,100 sat, Today")
            .assertIsDisplayed()
    }
}
