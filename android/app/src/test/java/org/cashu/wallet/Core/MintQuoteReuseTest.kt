package org.cashu.wallet.Core

import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.MintQuoteState
import org.cashu.wallet.Models.PaymentMethodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MintQuoteReuseTest {
    @Test
    fun selectsReusableAmountlessBolt12OfferForActiveMintOnly() {
        val reusable = quote(
            id = "offer",
            method = PaymentMethodKind.Bolt12,
            amount = null,
            mintUrl = ActiveMint,
        )
        val wrongMint = reusable.copy(id = "other", mintUrl = "https://other.example")
        val amountSpecific = reusable.copy(id = "amount", amount = 21)
        val alreadyPaid = reusable.copy(id = "paid", amountPaid = 21)

        assertEquals(
            reusable,
            selectReusableAmountlessOffer(
                listOf(wrongMint, amountSpecific, alreadyPaid, reusable),
                activeMintUrl = ActiveMint,
            ),
        )
    }

    @Test
    fun amountlessBolt12ReuseRejectsPaidOrIssuedOffers() {
        val paid = quote(
            id = "paid",
            method = PaymentMethodKind.Bolt12,
            amount = null,
            amountPaid = 21,
        )
        val issued = quote(
            id = "issued",
            method = PaymentMethodKind.Bolt12,
            amount = null,
            amountIssued = 21,
        )

        assertNull(selectReusableAmountlessOffer(listOf(paid, issued), activeMintUrl = ActiveMint))
    }

    @Test
    fun selectsReusableOnchainQuoteUntilIssued() {
        val issued = quote(
            id = "issued",
            method = PaymentMethodKind.Onchain,
            state = MintQuoteState.Issued,
        )
        val pending = quote(
            id = "pending",
            method = PaymentMethodKind.Onchain,
            state = MintQuoteState.Pending,
        )
        val wrongMint = pending.copy(id = "other", mintUrl = "https://other.example")

        assertEquals(
            pending,
            selectReusableOnchainMintQuote(
                listOf(issued, wrongMint, pending),
                activeMintUrl = ActiveMint,
            ),
        )
    }

    @Test
    fun onchainReuseIgnoresOtherPaymentMethods() {
        val bolt11 = quote(id = "ln", method = PaymentMethodKind.Bolt11)
        val bolt12 = quote(id = "offer", method = PaymentMethodKind.Bolt12, amount = null)

        assertNull(selectReusableOnchainMintQuote(listOf(bolt11, bolt12), activeMintUrl = ActiveMint))
    }

    private fun quote(
        id: String,
        method: PaymentMethodKind,
        amount: Long? = 10,
        mintUrl: String = ActiveMint,
        state: MintQuoteState = MintQuoteState.Unpaid,
        amountPaid: Long = 0,
        amountIssued: Long = 0,
    ) = MintQuoteInfo(
        id = id,
        request = "$id-request",
        amount = amount,
        paymentMethod = method,
        state = state,
        expiryEpochSeconds = null,
        mintUrl = mintUrl,
        amountPaid = amountPaid,
        amountIssued = amountIssued,
    )

    private companion object {
        const val ActiveMint = "https://mint.example"
    }
}
