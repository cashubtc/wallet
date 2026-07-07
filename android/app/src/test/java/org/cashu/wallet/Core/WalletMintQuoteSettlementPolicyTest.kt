package org.cashu.wallet.Core

import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.MintQuoteState
import org.cashu.wallet.Models.PaymentMethodKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletMintQuoteSettlementPolicyTest {
    @Test
    fun paidAndIssuedQuotesAttemptSettlement() {
        assertTrue(
            shouldAttemptMintQuoteSettlement(
                quote(state = MintQuoteState.Paid),
                allowPendingOnchainMintAttempt = false,
            ),
        )
        assertTrue(
            shouldAttemptMintQuoteSettlement(
                quote(state = MintQuoteState.Issued),
                allowPendingOnchainMintAttempt = false,
            ),
        )
    }

    @Test
    fun pendingOnchainOnlyAttemptsSettlementWhenAllowed() {
        val pendingOnchain = quote(
            method = PaymentMethodKind.Onchain,
            state = MintQuoteState.Pending,
        )

        assertFalse(
            shouldAttemptMintQuoteSettlement(
                pendingOnchain,
                allowPendingOnchainMintAttempt = false,
            ),
        )
        assertTrue(
            shouldAttemptMintQuoteSettlement(
                pendingOnchain,
                allowPendingOnchainMintAttempt = true,
            ),
        )
    }

    @Test
    fun unpaidLightningQuotesDoNotAttemptSettlement() {
        assertFalse(
            shouldAttemptMintQuoteSettlement(
                quote(state = MintQuoteState.Unpaid),
                allowPendingOnchainMintAttempt = true,
            ),
        )
    }

    @Test
    fun bolt12QuoteAlreadySettledByGatewayWhenIssuedAmountCoversPaidAmount() {
        assertTrue(
            isMintQuoteAlreadySettledByGateway(
                quote(
                    method = PaymentMethodKind.Bolt12,
                    amountPaid = 21,
                    amountIssued = 21,
                ),
            ),
        )
        assertTrue(
            isMintQuoteAlreadySettledByGateway(
                quote(
                    method = PaymentMethodKind.Bolt12,
                    amountPaid = 21,
                    amountIssued = 30,
                ),
            ),
        )
        assertFalse(
            isMintQuoteAlreadySettledByGateway(
                quote(
                    method = PaymentMethodKind.Bolt12,
                    amountPaid = 21,
                    amountIssued = 20,
                ),
            ),
        )
        assertFalse(
            isMintQuoteAlreadySettledByGateway(
                quote(
                    method = PaymentMethodKind.Bolt11,
                    amountPaid = 21,
                    amountIssued = 21,
                ),
            ),
        )
    }

    private fun quote(
        method: PaymentMethodKind = PaymentMethodKind.Bolt11,
        state: MintQuoteState = MintQuoteState.Unpaid,
        amountPaid: Long = 0,
        amountIssued: Long = 0,
    ) = MintQuoteInfo(
        id = "quote-id",
        request = "lnbc1quote",
        amount = 21,
        paymentMethod = method,
        state = state,
        expiryEpochSeconds = null,
        mintUrl = "https://mint.example",
        amountPaid = amountPaid,
        amountIssued = amountIssued,
    )
}
