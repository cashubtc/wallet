package com.cashu.me.Core

import com.cashu.me.Core.Protocols.CurrencyAmount
import com.cashu.me.Core.Protocols.CurrencyRegistry
import com.cashu.me.Models.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CustomPaymentMethodTest {
    private val branch = requireNotNull(PaymentMethodKind.fromRaw("branch"))

    @Test
    fun identitiesRoundTripAsLegacyStringsAndRejectInvalidEndpoints() {
        for (raw in listOf("bolt11", "bolt12", "onchain", "branch", "bank_transfer", "test-v2")) {
            val method = requireNotNull(PaymentMethodKind.fromRaw(raw))
            assertEquals("\"$raw\"", Json.encodeToString(method))
            assertEquals(method, Json.decodeFromString<PaymentMethodKind>("\"$raw\""))
        }
        for (raw in listOf("", "../branch", "branch/path", "branch?unit=sat", "Branch", "branch\n", "a".repeat(33))) {
            assertNull(raw, PaymentMethodKind.fromRaw(raw))
        }
        assertEquals("_", PaymentMethodKind.fromRaw("_")?.displayName)
        assertEquals("Bank Transfer", PaymentMethodKind.fromRaw("bank_transfer")?.displayName)
        assertEquals(PaymentMethodKind.Bolt11, PaymentMethodKind.fromRaw("BOLT11"))
    }

    @Test
    fun customUnitsKeepWholeAmountsAndRejectFractionalOrMalformedInput() {
        val bux = CurrencyRegistry.currencyForMintUnit("bux")
        assertEquals(0, bux.decimals)
        assertEquals("100 BUX", CurrencyAmount(100, bux).formatted())
        assertEquals(100L, UnitAmountEntry.validatedBaseUnits("100", bux.decimals))
        for (raw in listOf("1.5", "1.0", "-1", "1e2", "1,000", " 10", "10\n", "9999999999999")) {
            assertNull(raw, UnitAmountEntry.validatedBaseUnits(raw, bux.decimals))
        }
        assertEquals(150L, UnitAmountEntry.validatedBaseUnits("1.50", CurrencyRegistry.currencyForMintUnit("usd").decimals))
    }

    @Test
    fun capabilitiesKeepUnitLimitsNamesAndReportedEmptyAcrossPersistence() {
        val settings = AdvertisedPaymentMethod(branch, "bux", "Local cash", 10, 100)
        val mint = MintInfo(url = "https://mint.example", mintMethodSettings = listOf(settings), meltMethodSettings = emptyList())
        val restored = Json.decodeFromString<MintInfo>(Json.encodeToString(mint))
        assertEquals(listOf(branch), restored.mintMethods("bux"))
        assertTrue(restored.mintMethods("sat").isEmpty())
        assertTrue(restored.meltMethodSettings!!.isEmpty())
        assertEquals("Local cash", restored.methodName(branch, "bux"))
        assertEquals("Branch", settings.copy(name = "Bad\u0000name").displayName)
        assertFalse(settings.accepts(0)); assertFalse(settings.accepts(9))
        assertTrue(settings.accepts(10)); assertTrue(settings.accepts(100)); assertFalse(settings.accepts(101))
        assertEquals(listOf(PaymentMethodKind.Bolt11), Json.decodeFromString<MintInfo>("""{"url":"https://legacy.example"}""").mintMethods("sat"))
    }

    @Test
    fun partialIssuanceStaysPendingAndMonitoringReachesFullAmount() = runBlocking {
        val partial = MintQuoteInfo("quote", "opaque request", 100, paymentMethod = branch, state = MintQuoteState.Issued,
            expiryEpochSeconds = 1, mintUrl = "https://mint.example", amountPaid = 40, amountIssued = 40, unit = "bux")
        assertFalse(partial.hasSettledPayment)
        assertFalse(MintQuoteSchedulePolicy.observed(null, partial, 2_000).isComplete)
        val pending = pendingMintQuoteTransactions(listOf(partial), setOf("https://mint.example"), emptySet(), mutableMapOf(), 2_000).single()
        assertEquals(TransactionStatus.Pending, pending.status)
        assertEquals(TransactionKind.Custom, pending.kind)
        assertEquals("bux", pending.unit)
        assertEquals("quote", pending.mintQuoteIdForStatusRefresh)
        assertEquals("quote", TransactionDisplay.qrContent(pending))
        assertEquals("Branch pending", TransactionDisplay.title(pending))
        var checks = 0
        FocusedMintQuoteMonitor().monitor("quote", refresh = {
            checks++
            if (checks == 1) partial else partial.copy(amountPaid = 100, amountIssued = 100)
        }, sleep = {})
        assertEquals(2, checks)
        assertTrue(MintQuoteSchedulePolicy.observed(null, partial.copy(amountPaid = 100, amountIssued = 100), 2_000).isComplete)
    }

    @Test
    fun partialRequestSurvivesItsReceiptUntilAllPaidCreditIsIssued() {
        val partial = MintQuoteInfo("quote", "opaque request", 100, paymentMethod = branch,
            state = MintQuoteState.Issued, expiryEpochSeconds = 1, mintUrl = "https://mint.example",
            amountPaid = 40, amountIssued = 40, unit = "bux")
        fun pending(quote: MintQuoteInfo) = pendingMintQuoteTransactions(
            listOf(quote), setOf("https://mint.example"), setOf(quote.id), mutableMapOf(), 2_000)

        val request = pending(partial).single()
        assertEquals(TransactionStatus.Pending, request.status)
        assertEquals(100L, request.amount)
        assertEquals(40L, request.mintQuoteAmountPaid)
        assertEquals(60L, request.mintQuoteAmountRemaining)
        assertEquals(request, Json.decodeFromString<WalletTransaction>(Json.encodeToString(request)))
        val fields = TransactionDisplay.detailFields(request).associate { it.label to it.value }
        assertEquals("40 BUX", fields["Received"])
        assertEquals("60 BUX", fields["Remaining"])

        val awaitingIssuance = pending(partial.copy(amountPaid = 100, amountIssued = 40)).single()
        assertEquals(TransactionStatus.Pending, awaitingIssuance.status)
        assertEquals(0L, awaitingIssuance.mintQuoteAmountRemaining)
        assertTrue(pending(partial.copy(amountPaid = 100, amountIssued = 100)).isEmpty())
        assertTrue(pending(partial.copy(paymentMethod = PaymentMethodKind.Bolt11)).isEmpty())
    }
}
