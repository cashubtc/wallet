package com.cashu.me.Core

import com.cashu.me.Models.MintQuoteRetryState
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OnchainDepositStatusTest {
    private val formatter = AmountFormatter(Locale.US)
    private val sats: (Long) -> AmountParts = { formatter.satsParts(it, useBitcoinSymbol = false) }

    private fun resolve(
        paid: Long = 0,
        issued: Long = 0,
        observation: OnchainPaymentObservation? = null,
        retry: MintQuoteRetryState = MintQuoteRetryState.None,
        expired: Boolean = false,
    ) = OnchainDepositStatus.resolve(paid, issued, observation, retry, expired)

    @Test
    fun walksFromWaitingToReceived() {
        assertEquals(OnchainDepositStatus.Waiting, resolve())
        val mempool = OnchainPaymentObservation("tx", 2_317, confirmed = false, confirmations = null)
        assertEquals(OnchainDepositStatus.InMempool(2_317), resolve(observation = mempool))
        val confirmed = OnchainPaymentObservation("tx", 2_317, confirmed = true, confirmations = 3)
        assertEquals(OnchainDepositStatus.Confirming(2_317, 3), resolve(observation = confirmed))
        val countless = OnchainPaymentObservation("tx", 2_317, confirmed = true, confirmations = null)
        assertEquals(OnchainDepositStatus.Confirming(2_317, 1), resolve(observation = countless))
        // The mint's credit outranks the explorer.
        assertEquals(OnchainDepositStatus.Adding(2_317), resolve(paid = 2_317, observation = confirmed))
        assertEquals(OnchainDepositStatus.Received(2_317), resolve(paid = 2_317, issued = 2_317, observation = confirmed))
    }

    @Test
    fun retryStatesAndASecondDepositShowTheOutstandingDelta() {
        assertEquals(
            OnchainDepositStatus.Retrying(500, needsAttention = false),
            resolve(paid = 500, retry = MintQuoteRetryState.RetryScheduled),
        )
        assertTrue(resolve(paid = 500, retry = MintQuoteRetryState.NeedsAttention).needsAttention)
        assertFalse(resolve(paid = 500).needsAttention)
        assertEquals(OnchainDepositStatus.Adding(1_000), resolve(paid = 3_000, issued = 2_000))
    }

    @Test
    fun aLateDepositBeatsExpiry() {
        assertEquals(OnchainDepositStatus.Expired, resolve(expired = true))
        val late = OnchainPaymentObservation("tx", 21, confirmed = false, confirmations = null)
        assertEquals(OnchainDepositStatus.InMempool(21), resolve(observation = late, expired = true))
    }

    @Test
    fun sheetValuesCarryTheAmountAndHistoryTextNeverDoes() {
        assertEquals("Waiting for deposit", OnchainDepositStatus.Waiting.sheetValue(sats).text)
        assertEquals("Expired", OnchainDepositStatus.Expired.sheetValue(sats).text)
        assertEquals("2,317 sat · in mempool", OnchainDepositStatus.InMempool(2_317).sheetValue(sats).text)
        assertEquals("2,317 sat · 1 confirmation", OnchainDepositStatus.Confirming(2_317, 1).sheetValue(sats).text)
        assertEquals("2,317 sat · 2 confirmations", OnchainDepositStatus.Confirming(2_317, 2).sheetValue(sats).text)
        assertEquals("Adding 2,317 sat to wallet…", OnchainDepositStatus.Adding(2_317).sheetValue(sats).text)
        assertEquals("2,317 sat · retrying", OnchainDepositStatus.Retrying(2_317, false).sheetValue(sats).text)
        val attention = OnchainDepositStatus.Retrying(2_317, true).sheetValue(sats)
        assertEquals("2,317 sat · not added yet", attention.text)
        // The reassurance the screen leaves to the retry glyph is spoken.
        assertEquals("2,317 sat, not added to your wallet yet. It's safe and we'll keep trying.", attention.spoken)

        val symbol = OnchainDepositStatus.InMempool(2_317).sheetValue { formatter.satsParts(it, useBitcoinSymbol = true) }
        assertEquals("₿2,317 · in mempool", symbol.text)
        // TalkBack never reads a bare ₿.
        assertEquals("2,317 sats, in mempool", symbol.spoken)

        assertEquals("Waiting for deposit", OnchainDepositStatus.Waiting.historyText)
        assertEquals("In mempool", OnchainDepositStatus.InMempool(2_317).historyText)
        assertEquals("1 confirmation", OnchainDepositStatus.Confirming(2_317, 1).historyText)
        assertEquals("Adding to wallet…", OnchainDepositStatus.Adding(2_317).historyText)
        assertEquals("Retrying", OnchainDepositStatus.Retrying(2_317, false).historyText)
        assertEquals("Not added yet", OnchainDepositStatus.Retrying(2_317, true).historyText)
        assertNull(OnchainDepositStatus.Expired.historyText)
        assertNull(OnchainDepositStatus.Received(2_317).historyText)
    }
}
