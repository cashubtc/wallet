package org.cashu.wallet.Core

import org.cashu.wallet.Models.MintQuoteState
import org.cashu.wallet.Models.PaymentMethodKind
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MintQuotePollingPolicyTest {
    @Test
    fun pollsOpenQuotesBeforeExpiry() {
        assertTrue(
            shouldPollMintQuote(
                state = MintQuoteState.Unpaid,
                expiryEpochSeconds = 200,
                nowEpochSeconds = 100,
            ),
        )
        assertTrue(
            shouldPollMintQuote(
                state = MintQuoteState.Pending,
                expiryEpochSeconds = 200,
                nowEpochSeconds = 100,
            ),
        )
    }

    @Test
    fun pollsUnknownQuotesWithoutExpiry() {
        assertTrue(
            shouldPollMintQuote(
                state = MintQuoteState.Unknown,
                expiryEpochSeconds = null,
                nowEpochSeconds = 100,
            ),
        )
    }

    @Test
    fun stopsPollingTerminalStatesAndExpiredQuotes() {
        assertFalse(shouldPollMintQuote(MintQuoteState.Paid, expiryEpochSeconds = 200, nowEpochSeconds = 100))
        assertFalse(shouldPollMintQuote(MintQuoteState.Issued, expiryEpochSeconds = 200, nowEpochSeconds = 100))
        assertFalse(shouldPollMintQuote(MintQuoteState.Failed, expiryEpochSeconds = 200, nowEpochSeconds = 100))
        assertFalse(shouldPollMintQuote(MintQuoteState.Unpaid, expiryEpochSeconds = 100, nowEpochSeconds = 100))
    }

    @Test
    fun pollIntervalsMatchReceiveQuoteMethodCadence() {
        assertEquals(5_000L, initialMintQuotePollIntervalMillis(PaymentMethodKind.Bolt11))
        assertEquals(10_000L, initialMintQuotePollIntervalMillis(PaymentMethodKind.Bolt12))
        assertEquals(30_000L, initialMintQuotePollIntervalMillis(PaymentMethodKind.Onchain))

        assertEquals(6_000L, nextMintQuotePollIntervalMillis(5_000L, PaymentMethodKind.Bolt11))
        assertEquals(15_000L, nextMintQuotePollIntervalMillis(15_000L, PaymentMethodKind.Bolt11))
        assertEquals(11_000L, nextMintQuotePollIntervalMillis(10_000L, PaymentMethodKind.Bolt12))
        assertEquals(30_000L, nextMintQuotePollIntervalMillis(30_000L, PaymentMethodKind.Onchain))
    }
}
