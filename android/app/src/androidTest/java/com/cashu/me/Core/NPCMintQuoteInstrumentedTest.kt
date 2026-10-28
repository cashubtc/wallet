package com.cashu.me.Core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.Core.CDK.npcMintQuote
import org.cashudevkit.CurrencyUnit
import org.cashudevkit.NpubCashQuote
import org.cashudevkit.QuoteState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NPCMintQuoteInstrumentedTest {
    @Test
    fun convertsOriginalQuoteAndPreservesStateAndAccountingPolicy() {
        for ((input, expected) in listOf(
            "UNPAID" to QuoteState.UNPAID, "paid" to QuoteState.PAID,
            "PENDING" to QuoteState.PENDING, "ISSUED" to QuoteState.ISSUED,
        )) {
            val native = NpubCashQuote("id", 42uL, "usd", 100uL, 120uL, 200uL,
                "https://mint.example/", "invoice", input, true)
            val result = npcMintQuote(NPCService.fromCdkQuote(native), "https://mint.example")
            assertEquals("id", result.id)
            assertEquals(42uL, result.amount?.value)
            assertEquals(CurrencyUnit.Usd, result.unit)
            assertEquals("https://mint.example", result.mintUrl.url)
            assertEquals("invoice", result.request)
            assertEquals(200uL, result.expiry)
            assertEquals(120uL, result.updatedAt)
            assertEquals(expected, result.state)
            assertEquals(if (expected == QuoteState.PAID || expected == QuoteState.ISSUED) 42uL else 0uL, result.amountPaid.value)
            assertEquals(if (expected == QuoteState.ISSUED) 42uL else 0uL, result.amountIssued.value)
            assertNull(result.secretKey)
            assertNull(result.usedByOperation)
            assertEquals(0u, result.version)
        }
    }

    @Test
    fun paidQuoteWithoutPaidTimestampStillHasPaidAccounting() {
        val native = NpubCashQuote("id", 42uL, "sat", 100uL, null, null,
            "https://mint.example", "invoice", "PAID", true)
        val result = npcMintQuote(NPCService.fromCdkQuote(native), "https://mint.example")
        assertEquals(QuoteState.PAID, result.state)
        assertEquals(42uL, result.amountPaid.value)
        assertNull(result.secretKey)
    }

    @Test
    fun rejectsMissingOrMismatchedOriginal() {
        val native = NpubCashQuote("id", 1uL, "sat", 1uL, null, null, null, null, "PAID", false)
        val quote = NPCService.fromCdkQuote(native)
        assertTrue(runCatching { npcMintQuote(quote.copy(cdkQuote = null), "https://mint.example") }.isFailure)
        assertTrue(runCatching { npcMintQuote(quote.copy(id = "other"), "https://mint.example") }.isFailure)
    }
}
