package org.cashu.wallet.integration

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.cashu.wallet.Core.CDK.CdkWalletGateway
import org.cashu.wallet.Core.CashuRequestPersistence
import org.cashu.wallet.Core.CashuRequestStore
import org.cashu.wallet.Core.PaymentRequestBuilder
import org.cashu.wallet.Core.PaymentRequestDecodeResult
import org.cashu.wallet.Core.PaymentRequestDecoder
import org.cashu.wallet.Core.payCashuPaymentRequestAndRefresh
import org.cashu.wallet.Models.CashuRequest
import org.cashu.wallet.Models.MeltPaymentResult
import org.cashu.wallet.Models.MeltQuoteInfo
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.MintQuoteState
import org.cashu.wallet.Models.PaymentMethodKind
import org.cashu.wallet.Models.RestoreMintResult
import org.cashu.wallet.Models.SendTokenResult
import org.cashu.wallet.Models.WalletTransaction
import org.cashu.wallet.ui.receive.ReceiveLightningSettlementGateway
import org.cashu.wallet.ui.receive.chooseReceiveLightningQuote
import org.cashu.wallet.ui.receive.persistReceiveLightningQuoteIntent
import org.cashu.wallet.ui.receive.settleReceiveLightningQuote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NoNetworkFakeGatewayIntegrationTest {
    @Test
    fun fakeGatewayReceiveLightningSettlesThroughRequestStoreWithoutNetwork() = runBlocking {
        val cashuRequests = CashuRequestStore(MemoryCashuRequestPersistence())
        val gateway = FakeNoNetworkCdkGateway()

        val quote = chooseReceiveLightningQuote(
            requestMethod = PaymentMethodKind.Bolt11,
            requestAmount = 21,
            effectiveUnit = "sat",
            forceNew = false,
            existingAmountlessOffer = { null },
            existingOnchainMintQuote = { null },
            createMintQuote = gateway::createMintQuote,
        )
        persistReceiveLightningQuoteIntent(
            cashuRequestStore = cashuRequests,
            quote = quote,
            fallbackMintUrl = MintUrl,
        )

        gateway.markMintQuotePaid(quote.id)
        val paidQuote = gateway.checkMintQuote(quote.id)
        val amount = settleReceiveLightningQuote(
            quote = paidQuote,
            settlementGateway = gateway.settlementGateway(),
            cashuRequestStore = cashuRequests,
        ).getOrThrow()

        val request = cashuRequests.request(quote.id)!!
        assertEquals(21L, amount)
        assertEquals(listOf(quote.id), gateway.mintTokenCalls)
        assertTrue(gateway.refreshBalanceCalled)
        assertTrue(gateway.loadTransactionsCalled)
        assertEquals("bolt11", request.quoteKind)
        assertEquals(21L, request.totalReceived)
        assertEquals(1, request.receivedPayments.size)
    }

    @Test
    fun fakeGatewayPaysCashuRequestAndRefreshesLocalStateWithoutNetwork() = runBlocking {
        val gateway = FakeNoNetworkCdkGateway()
        gateway.ensureWallet(MintUrl)
        val encoded = PaymentRequestBuilder.build(
            id = "integration-request",
            amount = 42,
            unit = "sat",
            mints = listOf(MintUrl),
            description = "No-network integration request",
            nostrPubkeyHex = TestNostrPubkeyHex,
            relays = emptyList(),
        )
        val decoded = PaymentRequestDecoder.decode(encoded, includeCashuPaymentRequests = true)
        val summary = (decoded as PaymentRequestDecodeResult.CashuPaymentRequest).summary

        payCashuPaymentRequestAndRefresh(
            encoded = encoded,
            customAmountSats = summary.amount,
            preferredMintURL = MintUrl,
            payCashuPaymentRequest = gateway::payCashuPaymentRequest,
            refreshBalance = { gateway.refreshBalance() },
            loadTransactions = { gateway.loadTransactions() },
        )

        assertEquals(listOf("No-network integration request"), gateway.paidCashuRequestDescriptions)
        assertEquals(42L, gateway.totalBalance(MintUrl))
        assertTrue(gateway.refreshBalanceCalled)
        assertTrue(gateway.loadTransactionsCalled)
    }

    private class FakeNoNetworkCdkGateway : CdkWalletGateway {
        private val mints = mutableMapOf<String, MintInfo>()
        private val quotes = mutableMapOf<String, MintQuoteInfo>()
        private val paidRequests = mutableListOf<String>()
        private var nextQuote = 1
        private var balance = 0L

        var refreshBalanceCalled = false
            private set
        var loadTransactionsCalled = false
            private set
        val mintTokenCalls = mutableListOf<String>()
        val paidCashuRequestDescriptions: List<String>
            get() = paidRequests.toList()

        override suspend fun initializeLogging(level: String) = Unit
        override suspend fun generateMnemonic(): String = "abandon ".repeat(11).trim() + " about"
        override suspend fun mnemonicEntropy(mnemonic: String): ByteArray = ByteArray(16)
        override suspend fun validateMnemonic(mnemonic: String): Boolean = true
        override suspend fun openWalletRepository(mnemonic: String, databasePath: String) = Unit
        override suspend fun closeWalletRepository() = Unit

        override suspend fun ensureWallet(mintUrl: String, unit: String) {
            mints[mintUrl] = MintInfo(
                url = mintUrl,
                name = "Fake Mint",
                balance = balance,
                units = listOf(unit),
                mintUnits = listOf(unit),
                supportedMintMethods = listOf(PaymentMethodKind.Bolt11, PaymentMethodKind.Bolt12, PaymentMethodKind.Onchain),
                supportedMeltMethods = listOf(PaymentMethodKind.Bolt11),
            )
        }

        override suspend fun removeWallet(mintUrl: String, unit: String) {
            mints.remove(mintUrl)
        }

        override suspend fun fetchMintInfo(mintUrl: String): MintInfo? = mints[mintUrl]
        override suspend fun fetchFullMintInfo(mintUrl: String): MintInfo? = mints[mintUrl]
        override suspend fun restoreMint(mintUrl: String): RestoreMintResult =
            RestoreMintResult(mintUrl = mintUrl, mintName = "Fake Mint", spent = 0, unspent = 0, pending = 0)
        override suspend fun totalBalance(mintUrl: String): Long = balance
        override suspend fun unitBalance(mintUrl: String, unit: String): Long = balance
        override suspend fun unitBalanceIfExists(mintUrl: String, unit: String): Long? = balance

        override suspend fun createMintQuote(
            amount: Long?,
            method: PaymentMethodKind,
            mintUrl: String,
            unit: String,
        ): MintQuoteInfo {
            val id = "quote-${nextQuote++}"
            return MintQuoteInfo(
                id = id,
                request = when (method) {
                    PaymentMethodKind.Bolt11 -> "lnbc${amount ?: 0}fake"
                    PaymentMethodKind.Bolt12 -> "lno1fake"
                    PaymentMethodKind.Onchain -> "bc1qfakeaddress"
                },
                amount = amount,
                paymentMethod = method,
                state = MintQuoteState.Unpaid,
                expiryEpochSeconds = null,
                mintUrl = mintUrl,
                unit = unit,
            ).also { quotes[id] = it }
        }

        fun markMintQuotePaid(quoteId: String) {
            quotes[quoteId] = quotes.getValue(quoteId).copy(state = MintQuoteState.Paid)
        }

        override suspend fun checkMintQuote(quoteId: String): MintQuoteInfo = quotes.getValue(quoteId)
        override fun subscribeToMintQuote(quoteId: String): Flow<MintQuoteInfo> = flowOf(quotes.getValue(quoteId))
        override suspend fun listUnissuedMintQuotes(): List<MintQuoteInfo> = quotes.values.filter { it.state != MintQuoteState.Issued }

        override suspend fun mintTokens(quoteId: String): Long {
            val quote = quotes.getValue(quoteId)
            val amount = quote.amount ?: 0L
            mintTokenCalls += quoteId
            balance += amount
            quotes[quoteId] = quote.copy(state = MintQuoteState.Issued, amountIssued = amount)
            return amount
        }

        override suspend fun mintNPCQuote(quote: org.cashu.wallet.Core.NPCQuote, p2pkPubkey: String?): Long =
            unsupported()
        override suspend fun createMeltQuote(request: String, amountSats: Long?, preferredMintURL: String?): MeltQuoteInfo =
            unsupported()
        override suspend fun listMeltQuotes(): List<MeltQuoteInfo> = emptyList()
        override suspend fun meltTokens(quoteId: String, mintUrl: String?): MeltPaymentResult = unsupported()
        override suspend fun sendEcashToken(
            amount: Long,
            memo: String?,
            p2pkPubkey: String?,
            mintUrl: String,
            unit: String,
        ): SendTokenResult = unsupported()
        override suspend fun receiveEcashToken(tokenString: String, p2pkSigningKeys: List<String>): Long = unsupported()
        override suspend fun calculateReceiveFee(tokenString: String): Long = 0
        override suspend fun checkTokenSpendable(token: String, mintUrl: String): Boolean = true
        override suspend fun listTransactions(mintUrls: List<String>): List<WalletTransaction> = emptyList()

        override suspend fun payCashuPaymentRequest(
            encoded: String,
            customAmountSats: Long?,
            preferredMintURL: String?,
        ) {
            val request = PaymentRequestDecoder.decode(
                encoded,
                includeCashuPaymentRequests = true,
            ) as PaymentRequestDecodeResult.CashuPaymentRequest
            paidRequests += request.summary.description ?: request.summary.encoded
            balance += customAmountSats ?: request.summary.amount ?: 0L
        }

        fun refreshBalance() {
            refreshBalanceCalled = true
        }

        fun loadTransactions() {
            loadTransactionsCalled = true
        }

        fun settlementGateway(): ReceiveLightningSettlementGateway =
            object : ReceiveLightningSettlementGateway {
                override suspend fun refreshBalance() {
                    this@FakeNoNetworkCdkGateway.refreshBalance()
                }

                override suspend fun loadTransactions() {
                    this@FakeNoNetworkCdkGateway.loadTransactions()
                }

                override suspend fun mintTokens(quoteId: String): Long =
                    this@FakeNoNetworkCdkGateway.mintTokens(quoteId)
            }

        private fun unsupported(): Nothing = error("Unsupported in fake no-network gateway")
    }

    private class MemoryCashuRequestPersistence : CashuRequestPersistence {
        private var requests: List<CashuRequest> = emptyList()
        override var currentCashuRequestId: String? = null

        override fun loadCashuRequests(): List<CashuRequest> = requests

        override fun saveCashuRequests(requests: List<CashuRequest>) {
            this.requests = requests
        }
    }

    private companion object {
        const val MintUrl = "https://fake.mint.example"
        const val TestNostrPubkeyHex = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
