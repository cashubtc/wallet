package com.cashu.me.ui.send

import com.cashu.me.Core.Wallet.WalletMessage
import com.cashu.me.Models.*
import org.junit.Assert.*
import org.junit.Test

class OpenPaymentReconciliationTest {
    private val completed = WalletTransaction(
        id = "send", amount = 21, fee = 1, type = TransactionType.Outgoing,
        kind = TransactionKind.Lightning, dateEpochMillis = 1, status = TransactionStatus.Completed,
        quoteId = "quote", mintUrl = "https://mint.example", token = "token",
    )
    private val details = SendPaymentDetails(listOf(SendPaymentDetailRow(
        SendPaymentDetailKey.NetworkFee, "Fees", SendPaymentDetailValue.Sats(4, isUpperBound = true))))

    @Test fun onlyMatchingTerminalOutgoingSatPaymentResolvesTheSheet() {
        assertEquals(completed, listOf(completed).terminalOutgoingMelt("quote", "https://mint.example/"))
        val unrelated = listOf(
            completed.copy(quoteId = "other"), completed.copy(mintUrl = "https://other.example"),
            completed.copy(type = TransactionType.Incoming), completed.copy(unit = "usd"),
            completed.copy(kind = TransactionKind.Ecash), completed.copy(status = TransactionStatus.Pending),
        )
        assertNull(unrelated.terminalOutgoingMelt("quote", "https://mint.example"))
    }

    @Test fun pendingAndUnresolvedScreensUseTerminalFeesAndDropWaitingState() {
        val pending = SendStatus.Sent(details, MeltPaymentResult(null, 21, 4, "https://mint.example",
            settlement = MeltSettlement.Pending))
        val waiting = SendStatus.Failed(details, WalletMessage("Waiting", awaitsMintConfirmation = true))
        for (status in listOf(pending, waiting)) {
            val paid = status.reconciledWith(completed) as SendStatus.Sent
            assertEquals(MeltSettlement.Settled, paid.result!!.settlement)
            assertEquals(1L, paid.result.feePaid)
            assertEquals(SendPaymentDetailValue.Sats(1), paid.details.rows.single().value)
            val failed = status.reconciledWith(completed.copy(status = TransactionStatus.Failed)) as SendStatus.Failed
            assertFalse(failed.message.awaitsMintConfirmation)
            assertTrue(failed.message.isTerminal)
            assertEquals("Payment failed", failed.message.title)
            assertFalse(failed.message.text.contains("returned"))
            assertSame(status, status.reconciledWith(completed.copy(status = TransactionStatus.Pending)))
        }
        val final = SendStatus.Sent(details, MeltPaymentResult(null, 21, 2, "https://mint.example"))
        assertSame(final, final.reconciledWith(completed))
    }

    @Test fun claimedTokenNeedsMatchingIdentityDirectionMintAndUnit() {
        val ecash = completed.copy(kind = TransactionKind.Ecash)
        assertTrue(listOf(ecash).isClaimedSend("send", "token", "https://mint.example", "sat"))
        assertTrue(listOf(ecash).isClaimedSend(null, "token", "https://mint.example", "sat"))
        assertFalse(listOf(ecash).isClaimedSend("other", "token", "https://mint.example", "sat"))
        assertFalse(listOf(ecash).isClaimedSend(null, "other", "https://mint.example", "sat"))
        assertFalse(listOf(ecash.copy(type = TransactionType.Incoming)).isClaimedSend("send", "token", "https://mint.example", "sat"))
        assertFalse(listOf(ecash.copy(status = TransactionStatus.Failed)).isClaimedSend("send", "token", "https://mint.example", "sat"))
        assertFalse(listOf(ecash).isClaimedSend("send", "token", "https://other.example", "sat"))
        assertFalse(listOf(ecash).isClaimedSend("send", "token", "https://mint.example", "usd"))
    }
}
