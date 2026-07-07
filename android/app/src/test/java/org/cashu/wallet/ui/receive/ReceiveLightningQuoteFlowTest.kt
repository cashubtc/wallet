package org.cashu.wallet.ui.receive

import kotlinx.coroutines.runBlocking
import org.cashu.wallet.Core.CashuRequestPersistence
import org.cashu.wallet.Core.CashuRequestStore
import org.cashu.wallet.Models.CashuRequest
import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.MintQuoteState
import org.cashu.wallet.Models.PaymentMethodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveLightningQuoteFlowTest {
    @Test
    fun reusesExistingAmountlessBolt12Offer() = runBlocking {
        val existing = quote(
            id = "existing-offer",
            method = PaymentMethodKind.Bolt12,
            amount = null,
        )
        var createCalled = false

        val selected = chooseReceiveLightningQuote(
            requestMethod = PaymentMethodKind.Bolt12,
            requestAmount = null,
            effectiveUnit = "sat",
            forceNew = false,
            existingAmountlessOffer = { existing },
            existingOnchainMintQuote = { null },
            createMintQuote = { _, _, _ ->
                createCalled = true
                quote(id = "created", method = PaymentMethodKind.Bolt12, amount = null)
            },
        )

        assertSame(existing, selected)
        assertFalse(createCalled)
    }

    @Test
    fun forceNewOnchainSkipsReusableAddressAndCreatesSatQuote() = runBlocking {
        var amountlessOfferChecked = false
        var onchainReuseChecked = false
        var createdAmount: Long? = 99
        var createdMethod: PaymentMethodKind? = null
        var createdUnit: String? = null
        val created = quote(id = "fresh-address", method = PaymentMethodKind.Onchain, amount = null)

        val selected = chooseReceiveLightningQuote(
            requestMethod = PaymentMethodKind.Onchain,
            requestAmount = null,
            effectiveUnit = "usd",
            forceNew = true,
            existingAmountlessOffer = {
                amountlessOfferChecked = true
                null
            },
            existingOnchainMintQuote = {
                onchainReuseChecked = true
                quote(id = "old-address", method = PaymentMethodKind.Onchain, amount = null)
            },
            createMintQuote = { amount, method, unit ->
                createdAmount = amount
                createdMethod = method
                createdUnit = unit
                created
            },
        )

        assertSame(created, selected)
        assertFalse(amountlessOfferChecked)
        assertFalse(onchainReuseChecked)
        assertNull(createdAmount)
        assertEquals(PaymentMethodKind.Onchain, createdMethod)
        assertEquals("sat", createdUnit)
    }

    @Test
    fun persistsReceiveQuoteIntentWithProtocolKindAndFallbackMint() {
        val store = CashuRequestStore(MemoryCashuRequestPersistence())
        val offer = quote(
            id = "offer-quote",
            method = PaymentMethodKind.Bolt12,
            amount = null,
            mintUrl = null,
            request = "lno1offer",
            unit = "sat",
        )

        persistReceiveLightningQuoteIntent(
            cashuRequestStore = store,
            quote = offer,
            fallbackMintUrl = ActiveMint,
        )

        val request = store.request("offer-quote")!!
        assertEquals("offer-quote", request.quoteId)
        assertEquals("bolt12", request.quoteKind)
        assertEquals(listOf(ActiveMint), request.mints)
        assertEquals("Reusable invoice", request.memo)
        assertEquals("lno1offer", request.encoded)
    }

    @Test
    fun paidQuoteMintsAndAttachesPaymentToMatchingRequest() = runBlocking {
        val store = CashuRequestStore(MemoryCashuRequestPersistence())
        persistReceiveLightningQuoteIntent(
            cashuRequestStore = store,
            quote = quote(id = "quote-paid", method = PaymentMethodKind.Bolt11, amount = 21),
            fallbackMintUrl = ActiveMint,
        )
        val gateway = FakeSettlementGateway(mintedAmount = 21)

        val amount = settleReceiveLightningQuote(
            quote = quote(
                id = "quote-paid",
                method = PaymentMethodKind.Bolt11,
                amount = 21,
                state = MintQuoteState.Paid,
            ),
            settlementGateway = gateway,
            cashuRequestStore = store,
        ).getOrThrow()

        val request = store.request("quote-paid")!!
        assertEquals(21L, amount)
        assertEquals(listOf("quote-paid"), gateway.mintCalls)
        assertFalse(gateway.refreshBalanceCalled)
        assertEquals(1, request.receivedPayments.size)
        assertEquals("quote-paid", request.receivedPayments.single().transactionId)
        assertEquals(21L, request.totalReceived)
    }

    @Test
    fun alreadyIssuedQuoteRefreshesStateAndAttachesIssuedAmountWithoutMintingAgain() = runBlocking {
        val store = CashuRequestStore(MemoryCashuRequestPersistence())
        persistReceiveLightningQuoteIntent(
            cashuRequestStore = store,
            quote = quote(id = "issued-onchain", method = PaymentMethodKind.Onchain, amount = null),
            fallbackMintUrl = ActiveMint,
        )
        val gateway = FakeSettlementGateway(mintedAmount = 99)

        val amount = settleReceiveLightningQuote(
            quote = quote(
                id = "issued-onchain",
                method = PaymentMethodKind.Onchain,
                amount = null,
                state = MintQuoteState.Issued,
                amountIssued = 42,
            ),
            settlementGateway = gateway,
            cashuRequestStore = store,
        ).getOrThrow()

        val request = store.request("issued-onchain")!!
        assertEquals(42L, amount)
        assertTrue(gateway.refreshBalanceCalled)
        assertTrue(gateway.loadTransactionsCalled)
        assertTrue(gateway.mintCalls.isEmpty())
        assertEquals(42L, request.totalReceived)
    }

    private fun quote(
        id: String,
        method: PaymentMethodKind,
        amount: Long?,
        state: MintQuoteState = MintQuoteState.Unpaid,
        amountIssued: Long = 0,
        mintUrl: String? = ActiveMint,
        request: String = "$id-request",
        unit: String = "sat",
    ) = MintQuoteInfo(
        id = id,
        request = request,
        amount = amount,
        paymentMethod = method,
        state = state,
        expiryEpochSeconds = null,
        mintUrl = mintUrl,
        amountIssued = amountIssued,
        unit = unit,
    )

    private class FakeSettlementGateway(
        private val mintedAmount: Long,
    ) : ReceiveLightningSettlementGateway {
        var refreshBalanceCalled = false
        var loadTransactionsCalled = false
        val mintCalls = mutableListOf<String>()

        override suspend fun refreshBalance() {
            refreshBalanceCalled = true
        }

        override suspend fun loadTransactions() {
            loadTransactionsCalled = true
        }

        override suspend fun mintTokens(quoteId: String): Long {
            mintCalls += quoteId
            return mintedAmount
        }
    }

    private class MemoryCashuRequestPersistence : CashuRequestPersistence {
        var requests: List<CashuRequest> = emptyList()
        override var currentCashuRequestId: String? = null

        override fun loadCashuRequests(): List<CashuRequest> = requests

        override fun saveCashuRequests(requests: List<CashuRequest>) {
            this.requests = requests
        }
    }

    private companion object {
        const val ActiveMint = "https://mint.example"
    }
}
