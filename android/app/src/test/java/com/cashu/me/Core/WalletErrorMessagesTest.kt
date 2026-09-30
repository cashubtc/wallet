package com.cashu.me.Core

import com.cashu.me.Core.MintTransferEligibility.Blocker
import com.cashu.me.Core.Wallet.WalletErrorMessages
import com.cashu.me.Core.Wallet.WalletMessageSeverity
import com.cashu.me.Models.MintTransferException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** iOS parity: `WalletErrorMessageTests.swift`. */
class WalletErrorMessagesTest {

    @Test
    fun multiUnitRemovalPreservesSafetyGuidanceInsteadOfSuggestingNetworkRetry() {
        val error = com.cashu.me.Core.CDK.MultiUnitWalletRemovalException(listOf("sat", "usd"))
        assertEquals(
            "This mint uses multiple currency units and cannot be removed safely yet. Keep it connected and try again after updating the app.",
            WalletErrorMessages.classify(error).text,
        )
    }

    /**
     * Same trap as multi-unit removal: the guidance says "connected", which
     * the network matcher would turn into a connection error.
     */
    @Test
    fun transferInProgressRemovalPreservesSafetyGuidanceInsteadOfSuggestingNetworkRetry() {
        assertEquals(
            "A transfer involving this mint is still settling. Keep it connected until the transfer finishes.",
            WalletErrorMessages.classify(MintTransferInProgressException()).text,
        )
    }

    /** Transfer failures carry no raw text to match, so each maps by type. */
    @Test
    fun `mint transfer errors map to transfer copy`() {
        val caution = WalletMessageSeverity.Caution
        val error = WalletMessageSeverity.Error
        listOf(
            Triple(MintTransferException.NotEligible(Blocker.SameMint), "Choose two different mints.", caution),
            Triple(
                MintTransferException.NotEligible(Blocker.SourceCannotSend),
                "This mint can't send over Lightning. Choose another mint.", caution,
            ),
            Triple(
                MintTransferException.NotEligible(Blocker.DestinationCannotReceive),
                "This mint can't receive over Lightning. Choose another mint.", caution,
            ),
            Triple(MintTransferException.InsufficientBalance(42, 40), "Not enough balance.", error),
            Triple(MintTransferException.NothingToTransfer(), "Nothing left to transfer after fees.", caution),
            Triple(
                MintTransferException.QuoteMismatch(),
                "The mint returned an unexpected quote. Try again or use another mint.", error,
            ),
            Triple(MintTransferException.PlanExpired(), "This quote expired. Review the transfer again.", caution),
            Triple(MintTransferException.PlanStale(), "The transfer changed. Review it again.", caution),
            Triple(
                MintTransferException.PaymentReturned(),
                "The transfer didn't go through. Your funds were not moved.", error,
            ),
        ).forEach { (failure, text, severity) ->
            val message = WalletErrorMessages.classify(failure)
            assertEquals(text, message.text)
            assertEquals(text, severity, message.severity)
        }
    }

    private val mintLimitsCopy =
        "This amount is outside the mint's limits. Try a different amount."

    /**
     * A NUT-04/05 amount rejection must never reach the UI as CDK's own text.
     * The mint returns code 11006 with the real bounds in `detail`, but decoding
     * that response back into an Error rebuilds the variant with three
     * `Amount::default()` — so CDK renders "Amount must be between `0` and `0`
     * is `0`" for every mint at every amount. Regression guard for the copy the
     * user saw shipped verbatim.
     */
    @Test
    fun `cdk amount limit wording maps to mint limits copy`() {
        val message = WalletErrorMessages.classifyMessage("Amount must be between `0` and `0` is `0`")

        assertEquals(mintLimitsCopy, message.text)
        assertEquals(WalletMessageSeverity.Caution, message.severity)
    }

    /**
     * The same rule still has to catch the phrasings it was originally written
     * for, so adding CDK's wording can't quietly narrow it.
     */
    @Test
    fun `legacy amount limit wordings still map to mint limits copy`() {
        listOf(
            "Amount out of range",
            "Amount is outside of allowed range",
            "amount is outside the allowed limits",
        ).forEach { raw ->
            assertEquals(raw, mintLimitsCopy, WalletErrorMessages.classifyMessage(raw).text)
        }
    }

    /**
     * A mint that refuses NUT-05 amountless must point at another mint, not tell
     * the user the invoice is at fault.
     */
    @Test
    fun `mint amountless rejection points at another mint`() {
        listOf(
            "Amount Less Invoice is not allowed",
            "Amountless invoices are not supported for unit `sat` and method `bolt11`",
        ).forEach { raw ->
            val message = WalletErrorMessages.classifyMessage(raw)
            assertEquals(raw, "This mint can't pay invoices without an amount. Choose another mint.", message.text)
            assertEquals(raw, WalletMessageSeverity.Caution, message.severity)
        }

        assertEquals(
            "This invoice doesn't set an amount. Ask the sender for one with the amount set.",
            WalletErrorMessages.classifyMessage("Invoice Amount undefined").text,
        )
    }

    /**
     * `TransactionUnbalanced` is rebuilt as `(0, 0, 0)` by the same decoder, so it
     * reaches us as "Inputs: `0`, Outputs: `0`, Expected Fee: `0`" — three more
     * meaningless numbers that must not be shown.
     */
    @Test
    fun `cdk unbalanced wording maps to fee disagreement copy`() {
        val message =
            WalletErrorMessages.classifyMessage("Inputs: `0`, Outputs: `0`, Expected Fee: `0`")

        assertEquals(
            "The wallet and mint disagreed on the fee. Try again or use another mint.",
            message.text,
        )
        assertFalse(message.text.contains("`0`"))
    }

    /**
     * The zeroed CDK string must not survive anywhere in the resolved copy —
     * the whole point of the mapping is that those numbers are meaningless.
     */
    @Test
    fun `resolved copy never leaks the zeroed bounds`() {
        val text = WalletErrorMessages.classifyMessage("Amount must be between `0` and `0` is `0`").text

        assertFalse(text.contains("`0`"))
        assertFalse(text.lowercase().contains("must be between"))
    }

    /**
     * A mint that pauses a direction answers with CDK's "Minting is disabled" /
     * "Melting is disabled". The rule used to match only the wording without
     * "is", so the raw sentence was what the user read.
     */
    @Test
    fun `cdk paused mint wording maps to paused copy`() {
        val deposits = WalletErrorMessages.classifyMessage("Minting is disabled")
        assertEquals("This mint has paused deposits. Choose another mint.", deposits.text)
        assertEquals(WalletMessageSeverity.Caution, deposits.severity)

        val payments = WalletErrorMessages.classifyMessage("Melting is disabled")
        assertEquals("This mint has paused payments. Choose another mint.", payments.text)
        assertEquals(WalletMessageSeverity.Caution, payments.severity)
    }

    @Test
    fun `legacy paused mint wordings still map to paused copy`() {
        assertEquals(
            "This mint has paused deposits. Choose another mint.",
            WalletErrorMessages.classifyMessage("Minting disabled").text,
        )
        assertEquals(
            "This mint has paused payments. Choose another mint.",
            WalletErrorMessages.classifyMessage("Melting disabled").text,
        )
    }
}
