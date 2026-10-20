package com.cashu.me.ui.receive

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.Core.OnchainDepositStatus
import com.cashu.me.ui.testing.UiTestTags
import com.cashu.me.ui.setCashuContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** iOS `testOnchainStatusRowKeepsItsSizeAcrossEveryStage` parity. */
@RunWith(AndroidJUnit4::class)
class OnchainDepositStatusRowComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rowKeepsItsSizeAndOnlyRetriesOnceAttentionIsNeeded() {
        val status = mutableStateOf<OnchainDepositStatus>(OnchainDepositStatus.Waiting)
        var retries = 0
        compose.setCashuContent {
            OnchainDepositStatusRow(status = status.value, useBitcoinSymbol = true, onRetry = { retries++ })
        }
        val row = compose.onNodeWithTag(UiTestTags.ReceiveOnchainStatus)
        val height = row.fetchSemanticsNode().size.height
        for ((next, spoken) in listOf(
            OnchainDepositStatus.Waiting to "Waiting for deposit",
            OnchainDepositStatus.InMempool(2_317) to "2,317 sats, in mempool",
            OnchainDepositStatus.Confirming(300_000, 45) to "300,000 sats, 45 confirmations",
            OnchainDepositStatus.Adding(300_000) to "Adding 300,000 sats to wallet",
            OnchainDepositStatus.Retrying(300_000, needsAttention = false) to "300,000 sats, retrying",
            OnchainDepositStatus.Retrying(300_000, needsAttention = true) to
                "300,000 sats, not added to your wallet yet. It's safe and we'll keep trying.",
            OnchainDepositStatus.Expired to "Expired",
        )) {
            compose.runOnIdle { status.value = next }
            compose.waitForIdle()
            val node = row.fetchSemanticsNode()
            assertEquals(listOf("Status, $spoken"), node.config.getOrNull(SemanticsProperties.ContentDescription))
            assertEquals("The Status row must not change size", height, node.size.height)
            if (next.needsAttention) {
                // Only now is the row the retry button.
                assertEquals(Role.Button, node.config.getOrNull(SemanticsProperties.Role))
                assertEquals("Retry now", node.config.getOrNull(SemanticsActions.OnClick)?.label)
                row.performClick()
                compose.runOnIdle { assertEquals(1, retries) }
            } else {
                assertNull(node.config.getOrNull(SemanticsActions.OnClick))
            }
        }
    }
}
