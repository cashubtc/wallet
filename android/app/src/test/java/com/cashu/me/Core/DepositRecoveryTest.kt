package com.cashu.me.Core

import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.MintQuoteState
import com.cashu.me.Models.PaymentMethodKind
import org.junit.Assert.*
import org.junit.Test

class DepositRecoveryTest {
    private fun request(url: String = "https://mint.example", id: String = "quote", limit: UInt = 1_000u) =
        DepositRecoveryRequest(url, id, PaymentMethodKind.Bolt11, searchLimit = limit)
    private fun quote(paid: Long = 0, issued: Long = 0, expiry: Long? = null) = MintQuoteInfo(
        id = "quote", request = "invoice", amount = 21, paymentMethod = PaymentMethodKind.Bolt11,
        state = if (paid > issued) MintQuoteState.Paid else MintQuoteState.Pending,
        expiryEpochSeconds = expiry, amountPaid = paid, amountIssued = issued, unit = "usd",
    )

    @Test fun inputNormalizesWithoutChangingQuoteCase() {
        val input = request(" mint.example/path/ ", " AbC-123 ").validated()
        assertEquals("https://mint.example/path", input.mintUrl)
        assertEquals("AbC-123", input.quoteId)
    }
    @Test fun invalidEndpointsAndUnboundedSearchesAreRejected() {
        for (url in listOf("http://mint.example", "https://name:password@mint.example", "https://mint.example?seed=x", "https://mint.example#x", "https://mint.example:99999")) {
            assertThrows(IllegalArgumentException::class.java) { request(url).validated() }
        }
        for (limit in listOf(0u, 1u, 100_000u, UInt.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { request(limit = limit).validated() }
        }
        assertEquals(10_000u, request(limit = 10_000u).validated().searchLimit)
    }
    @Test fun missingAndWhitespaceQuoteIdsAreRejected() {
        for (id in listOf("", "   ", "quote id", "quote\nother", "x".repeat(1_025))) {
            assertThrows(IllegalArgumentException::class.java) { request(id = id).validated() }
        }
    }
    @Test fun paidCounterDoesNotReportRecoveredFunds() {
        assertEquals(DepositRecoveryStatus.Pending, DepositRecoveryResult.from(quote(paid = 21)).status)
        assertEquals(0L, DepositRecoveryResult.from(quote(paid = 21)).amount)
    }
    @Test fun onlyNewlyIssuedFundsAreReportedAndUnitIsPreserved() {
        val result = DepositRecoveryResult.from(quote(paid = 42, issued = 21), newlyIssued = 21)
        assertEquals(DepositRecoveryStatus.Recovered, result.status)
        assertEquals(21L, result.amount)
        assertEquals(21L, result.remaining)
        assertEquals("usd", result.unit)
        assertTrue(result.message.contains("0.21"))
    }
    @Test fun alreadyClaimedAndUnpaidDepositsDoNotReportSuccess() {
        assertEquals(DepositRecoveryStatus.AlreadyClaimed, DepositRecoveryResult.from(quote(paid = 21, issued = 21)).status)
        assertEquals(DepositRecoveryStatus.Waiting, DepositRecoveryResult.from(quote()).status)
        assertEquals(DepositRecoveryStatus.Pending, DepositRecoveryResult.from(quote(), retryPending = true).status)
    }
    @Test fun expiredUnpaidInvoiceDoesNotPromiseMorePolling() {
        assertEquals(DepositRecoveryStatus.Expired, DepositRecoveryResult.from(quote(expiry = 1)).status)
        assertEquals(DepositRecoveryStatus.Pending, DepositRecoveryResult.from(quote(paid = 21, expiry = 1)).status)
    }
}
