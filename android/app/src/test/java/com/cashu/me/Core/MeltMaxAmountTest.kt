package com.cashu.me.Core

import com.cashu.me.Models.MeltQuoteInfo
import com.cashu.me.Models.MeltQuoteState
import com.cashu.me.Models.PaymentMethodKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fee-aware Max for Lightning and on-chain sends. iOS parity: `SendMaxMeltAmountTests`. */
class MeltMaxAmountTest {
    private val mintUrl = "https://mint.example"

    private fun fakeQuote(
        id: String,
        amount: Long,
        feeReserve: Long,
        mintUrl: String = this.mintUrl,
        state: MeltQuoteState = MeltQuoteState.Unpaid,
        expiryEpochSeconds: Long? = 2_000L,
    ) = MeltQuoteInfo(
        id = id,
        mintUrl = mintUrl,
        amount = amount,
        feeReserve = feeReserve,
        paymentMethod = PaymentMethodKind.Bolt11,
        state = state,
        expiryEpochSeconds = expiryEpochSeconds,
    )

    private fun largest(balance: Long, reserve: (Long) -> Long): Pair<PayableMeltQuote?, Int> = runBlocking {
        var quotes = 0
        val payable = largestPayableMeltAmount(balance) { amount ->
            quotes += 1
            fakeQuote(id = "quote-$quotes-$amount", amount = amount, feeReserve = reserve(amount))
        }
        payable to quotes
    }

    @Test
    fun flatReserveSettlesOnTheSecondQuoteAndReturnsWinningQuote() {
        val (payable, quotes) = largest(1_314) { 13 }
        assertNotNull(payable)
        val winner = checkNotNull(payable)
        assertEquals(1_301L, winner.amount)
        assertEquals("quote-2-1301", winner.quote.id)
        assertEquals(1_301L, winner.quote.amount)
        assertEquals(13L, winner.quote.feeReserve)
        assertEquals(mintUrl, winner.quote.mintUrl)
        assertEquals(2, quotes)
    }

    @Test
    fun percentageReserveWithFloorFitsTheBalanceAndReturnsWinningQuote() {
        // 1% rounded up, at least 2 sat — a common Lightning backend shape.
        val reserve = { amount: Long -> maxOf(2L, (amount + 99) / 100) }
        val (payable, _) = largest(1_314, reserve)
        val winner = checkNotNull(payable)
        assertEquals(1_300L, winner.amount)
        assertEquals(1_300L, winner.quote.amount)
        assertEquals(reserve(1_300L), winner.quote.feeReserve)
        assertTrue(1_300 + reserve(1_300) <= 1_314)
        assertTrue("1,300 is the largest amount that fits", 1_301 + reserve(1_301) > 1_314)
    }

    @Test
    fun noReserveKeepsTheWholeBalanceWithOneQuote() {
        val (payable, quotes) = largest(500) { 0 }
        val winner = checkNotNull(payable)
        assertEquals(500L, winner.amount)
        assertEquals("quote-1-500", winner.quote.id)
        assertEquals(1, quotes)
    }

    @Test
    fun balanceBelowTheReserveFloorPaysNothing() {
        assertNull(largest(1) { 2 }.first)
    }

    @Test(expected = IllegalStateException::class)
    fun inconclusiveQuotesDoNotReportAnEmptyBalance() {
        runBlocking {
            largestPayableMeltAmount(100, rounds = 1) { amount ->
                fakeQuote(id = "q-$amount", amount = amount, feeReserve = 2)
            }
        }
    }

    @Test(expected = IllegalStateException::class)
    fun quoteFailuresReachTheCaller() {
        runBlocking { largestPayableMeltAmount(100) { error("mint down") } }
    }

    @Test
    fun cachedMaxMeltQuoteMatchesOnlyFreshMatchingSelection() {
        val quote = fakeQuote(id = "q-498", amount = 498L, feeReserve = 2L, expiryEpochSeconds = 2_000L)
        val cached = CachedMaxMeltQuote(
            request = "alice@example.com",
            amount = 498L,
            mintUrl = "https://mint.example/",
            quote = quote,
        )

        assertTrue(
            cached.matches(
                request = "alice@example.com",
                amount = 498L,
                mintUrl = "https://mint.example",
                nowEpochSeconds = 1_999L,
            ),
        )
        assertFalse(
            "Expired quote must not be reused",
            cached.matches(
                request = "alice@example.com",
                amount = 498L,
                mintUrl = "https://mint.example",
                nowEpochSeconds = 2_001L,
            ),
        )
        assertFalse(
            "Edited or fiat-rounded amount must not reuse a quote for a different amount",
            cached.matches(
                request = "alice@example.com",
                amount = 490L,
                mintUrl = "https://mint.example",
                nowEpochSeconds = 1_999L,
            ),
        )
        assertFalse(
            "Switched mint must not reuse the previous mint's quote",
            cached.matches(
                request = "alice@example.com",
                amount = 498L,
                mintUrl = "https://other-mint.example",
                nowEpochSeconds = 1_999L,
            ),
        )
        assertFalse(
            "Different recipient must not reuse the previous quote",
            cached.matches(
                request = "bob@example.com",
                amount = 498L,
                mintUrl = "https://mint.example",
                nowEpochSeconds = 1_999L,
            ),
        )
        val paidCached = CachedMaxMeltQuote(
            request = "alice@example.com",
            amount = 498L,
            mintUrl = "https://mint.example",
            quote = fakeQuote(
                id = "q-paid",
                amount = 498L,
                feeReserve = 2L,
                state = MeltQuoteState.Paid,
                expiryEpochSeconds = 2_000L,
            ),
        )
        assertFalse(
            "Only unpaid quotes may be reused",
            paidCached.matches(
                request = "alice@example.com",
                amount = 498L,
                mintUrl = "https://mint.example",
                nowEpochSeconds = 1_999L,
            ),
        )
    }
}
