package com.cashu.me.Core

import com.cashu.me.Models.CashuRequest
import com.cashu.me.Models.CashuRequestPayment
import com.cashu.me.Models.MeltQuoteInfo
import com.cashu.me.Models.MeltQuoteState
import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.MintQuoteState
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionTechnicalDetailsTest {
    private val cdkId = "a".repeat(64)
    private val proof = "b".repeat(64)
    private val dateMillis = 1_790_000_000_000L

    private fun transaction(
        id: String = cdkId,
        type: TransactionType = TransactionType.Incoming,
        kind: TransactionKind = TransactionKind.Lightning,
        status: TransactionStatus = TransactionStatus.Completed,
    ) = WalletTransaction(
        id = id, amount = 21, type = type, kind = kind, dateEpochMillis = dateMillis,
        status = status, mintUrl = "https://mint.example.com",
    )

    private fun mintQuote(
        request: String,
        method: PaymentMethodKind,
        state: MintQuoteState,
        amountPaid: Long,
        amountIssued: Long,
        expiry: Long?,
        updatedAt: Long = 0,
    ) = MintQuoteInfo(
        id = "quote", request = request, amount = null, paymentMethod = method, state = state,
        expiryEpochSeconds = expiry, amountPaid = amountPaid, amountIssued = amountIssued,
        updatedAtEpochSeconds = updatedAt,
    )

    private fun List<TechnicalDetailSection>.labels(title: String) =
        firstOrNull { it.title == title }?.rows?.map { it.label }.orEmpty()

    private fun List<TechnicalDetailSection>.row(label: String) =
        flatMap { it.rows }.firstOrNull { it.label == label }

    @Test
    fun settledBolt11MintShowsTransactionQuoteAndProof() {
        val tx = transaction().copy(
            quoteId = "quote-bolt11-0000000001",
            invoice = "lnbc210n1pexampleinvoicewithalongtail",
            paymentMethod = PaymentMethodKind.Bolt11,
            preimage = proof,
            sagaId = "5f1c2b3a-0000-4000-8000-00000000abcd",
        )
        val quote = mintQuote(
            "lnbc210n1pexampleinvoicewithalongtail", PaymentMethodKind.Bolt11, MintQuoteState.Issued,
            amountPaid = 21, amountIssued = 21, expiry = 1_790_000_600, updatedAt = 1_790_000_010,
        )

        val sections = TransactionTechnicalDetails.sections(tx, mintQuote = quote)

        assertEquals(listOf("Transaction", "Quote", "Payment"), sections.map { it.title })
        assertEquals(
            listOf("ID", "Method", "Direction", "Status", "Date", "Amount", "Fee", "Mint", "Saga ID"),
            sections.labels("Transaction"),
        )
        assertEquals(
            listOf("Quote ID", "Type", "Request", "State", "Amount paid", "Amount issued", "Expiry", "Last updated"),
            sections.labels("Quote"),
        )
        assertEquals(listOf("Payment Proof"), sections.labels("Payment"))
        assertEquals("aaaaaaaa…aaaaaa", sections.row("ID")?.value)
        assertEquals(cdkId, sections.row("ID")?.fullValue)
        assertEquals(true, sections.row("ID")?.copyable)
        assertEquals("Lightning (BOLT11)", sections.row("Method")?.value)
        assertEquals("Mint quote", sections.row("Type")?.value)
        assertEquals("Issued", sections.row("State")?.value)
        assertEquals("21 sat", sections.row("Amount")?.value)
        assertEquals("0 sat", sections.row("Fee")?.value)
        assertEquals("https://mint.example.com", sections.row("Mint")?.value)
        assertEquals("2026-09-21T14:13:20Z", sections.row("Date")?.fullValue)
        assertEquals(proof, sections.row("Payment Proof")?.fullValue)
    }

    @Test
    fun unpaidInvoiceRowHasNoCdkIdOrProof() {
        val tx = transaction(id = "quote-pending", status = TransactionStatus.Pending).copy(
            quoteId = "quote-pending",
            invoice = "lnbc1pendinginvoice",
            isUnpaidInvoice = true,
        )

        val sections = TransactionTechnicalDetails.sections(tx)

        assertEquals(listOf("Transaction", "Quote"), sections.map { it.title })
        assertFalse("ID" in sections.labels("Transaction"))
        assertEquals("Lightning (BOLT11)", sections.row("Method")?.value)
        assertEquals(listOf("Quote ID", "Type", "Request"), sections.labels("Quote"))
    }

    @Test
    fun bolt12OfferResolvesMethodFromQuoteAndNeverExpires() {
        val tx = transaction().copy(quoteId = "offer-quote", invoice = "lno1qexampleoffer")
        val quote = mintQuote(
            "lno1qexampleoffer", PaymentMethodKind.Bolt12, MintQuoteState.Paid,
            amountPaid = 42, amountIssued = 21, expiry = null,
        )

        val sections = TransactionTechnicalDetails.sections(tx, mintQuote = quote)

        assertEquals("Lightning (BOLT12)", sections.row("Method")?.value)
        assertEquals("Never", sections.row("Expiry")?.value)
        assertEquals("42 sat", sections.row("Amount paid")?.value)
        assertEquals("21 sat", sections.row("Amount issued")?.value)
        assertNull(sections.row("Last updated"))
        assertEquals(
            "Never",
            TransactionTechnicalDetails.sections(tx, mintQuote = quote.copy(expiryEpochSeconds = 253_402_300_799))
                .row("Expiry")?.value,
        )
    }

    @Test
    fun pendingOnchainDepositCarriesConfirmationsAndTxid() {
        val tx = transaction(id = "deposit-quote", kind = TransactionKind.Onchain, status = TransactionStatus.Pending).copy(
            quoteId = "deposit-quote",
            invoice = "bc1qexampledepositaddress000000",
            preimage = proof,
            statusNote = "Payment confirmed on-chain (45 confirmations)",
        )
        val quote = mintQuote(
            "bc1qexampledepositaddress000000", PaymentMethodKind.Onchain, MintQuoteState.Paid,
            amountPaid = 300_000, amountIssued = 0, expiry = 0, updatedAt = 1_790_000_000,
        )

        val sections = TransactionTechnicalDetails.sections(tx, mintQuote = quote)

        assertEquals("On-chain", sections.row("Method")?.value)
        assertEquals("Pending", sections.row("Status")?.value)
        assertEquals("Payment confirmed on-chain (45 confirmations)", sections.row("Status detail")?.value)
        assertEquals("300,000 sat", sections.row("Amount paid")?.value)
        assertEquals("0 sat", sections.row("Amount issued")?.value)
        assertEquals("Never", sections.row("Expiry")?.value)
        assertEquals(listOf("Transaction ID"), sections.labels("Payment"))
        assertEquals(proof, sections.row("Transaction ID")?.fullValue)
    }

    @Test
    fun completedOnchainDepositOmitsStatusDetail() {
        val tx = transaction(kind = TransactionKind.Onchain).copy(
            quoteId = "deposit-quote",
            invoice = "bc1qexampledepositaddress000000",
            paymentMethod = PaymentMethodKind.Onchain,
            statusNote = "stale note",
        )

        val sections = TransactionTechnicalDetails.sections(tx)

        assertEquals("Completed", sections.row("Status")?.value)
        assertNull(sections.row("Status detail"))
        assertEquals(listOf("Transaction", "Quote"), sections.map { it.title })
    }

    @Test
    fun meltShowsQuoteAmountsAndFallsBackToQuoteProof() {
        val tx = transaction(type = TransactionType.Outgoing).copy(
            quoteId = "melt-quote",
            invoice = "lnbc500n1pmeltinvoice",
            paymentMethod = PaymentMethodKind.Bolt11,
            fee = 2,
        )
        val quote = MeltQuoteInfo(
            id = "melt-quote", mintUrl = "https://mint.example.com", amount = 50, feeReserve = 4,
            paymentMethod = PaymentMethodKind.Bolt11, state = MeltQuoteState.Paid,
            expiryEpochSeconds = 1_790_000_600, request = "lnbc500n1pmeltinvoice", paymentProof = proof,
        )

        val sections = TransactionTechnicalDetails.sections(tx, meltQuote = quote)

        assertEquals("Melt quote", sections.row("Type")?.value)
        assertEquals(
            listOf("Quote ID", "Type", "Request", "State", "Quote amount", "Fee reserve", "Expiry"),
            sections.labels("Quote"),
        )
        assertEquals("2 sat", sections.row("Fee")?.value)
        assertEquals("4 sat", sections.row("Fee reserve")?.value)
        assertEquals(proof, sections.row("Payment Proof")?.fullValue)
    }

    @Test
    fun meltWithoutProofHasNoPaymentSection() {
        val tx = transaction(type = TransactionType.Outgoing, status = TransactionStatus.Pending).copy(
            quoteId = "melt-quote",
            invoice = "lnbc500n1pmeltinvoice",
        )

        val sections = TransactionTechnicalDetails.sections(tx)

        assertEquals(listOf("Transaction", "Quote"), sections.map { it.title })
        assertEquals("Melt quote", sections.row("Type")?.value)
    }

    @Test
    fun ecashSendNeverCopiesItsToken() {
        val token = "cashuBspendabletokenvalue0123456789"
        val tx = transaction(type = TransactionType.Outgoing, kind = TransactionKind.Ecash, status = TransactionStatus.Pending)
            .copy(token = token, sagaId = "5f1c2b3a-0000-4000-8000-00000000abcd")

        val sections = TransactionTechnicalDetails.sections(tx)

        assertEquals(listOf("Transaction"), sections.map { it.title })
        assertEquals("Ecash", sections.row("Method")?.value)
        assertEquals("Outgoing", sections.row("Direction")?.value)
        assertFalse(TransactionTechnicalDetails.copyAllText(sections).contains(token))
        assertFalse(sections.flatMap { it.rows }.any { it.fullValue.contains(token) })
    }

    @Test
    fun heldEcashReceiveHasNoCdkId() {
        val tx = transaction(id = "token-hash", kind = TransactionKind.Ecash, status = TransactionStatus.Pending).copy(
            token = "cashuBheldtoken",
            isPendingReceiveToken = true,
            statusNote = "Not claimed yet",
        )

        val sections = TransactionTechnicalDetails.sections(tx)

        assertFalse("ID" in sections.labels("Transaction"))
        assertEquals("Not claimed yet", sections.row("Status detail")?.value)
        assertFalse(TransactionTechnicalDetails.copyAllText(sections).contains("cashuBheldtoken"))
    }

    @Test
    fun quoteDomainModelsCarryNoSecretKey() {
        // The NUT-20 signing key stays in CDK: the gateway's domain mapping is
        // the only quote shape Details reads, and it has no field to leak.
        val fields = (MintQuoteInfo::class.java.declaredFields + MeltQuoteInfo::class.java.declaredFields)
            .map { it.name.lowercase() }
        assertTrue(fields.none { "secret" in it })
    }

    @Test
    fun copyAllIsSectionedLabelValueTextWithFullValues() {
        val tx = transaction().copy(
            quoteId = "quote-bolt11-0000000001",
            invoice = "lnbc210n1pexampleinvoicewithalongtail",
            paymentMethod = PaymentMethodKind.Bolt11,
        )

        val text = TransactionTechnicalDetails.copyAllText(TransactionTechnicalDetails.sections(tx))

        assertTrue(text.startsWith("Transaction\nID: $cdkId\nMethod: Lightning (BOLT11)\n"))
        assertTrue(text.contains("\n\nQuote\nQuote ID: quote-bolt11-0000000001\nType: Mint quote\n"))
        assertTrue(text.contains("Request: lnbc210n1pexampleinvoicewithalongtail"))
        assertTrue(text.contains("Date: 2026-09-21T14:13:20Z"))
    }

    @Test
    fun copyConfirmationsNameTheValue() {
        assertEquals("Copied ID", TransactionTechnicalDetails.copyConfirmation("ID"))
        assertEquals("Copied quote ID", TransactionTechnicalDetails.copyConfirmation("Quote ID"))
        assertEquals("Copied mint URL", TransactionTechnicalDetails.copyConfirmation("Mint"))
        assertEquals("Copied payment proof", TransactionTechnicalDetails.copyConfirmation("Payment Proof"))
        assertEquals("Copied transaction ID", TransactionTechnicalDetails.copyConfirmation("Transaction ID"))
    }
    @Test fun reusableOfferDetailsKeepQuoteAndIndividualPaymentIdentities() {
        val request = CashuRequest(id = "offer-intent", encoded = "lno1fixture",
            mints = listOf("https://mint.example.com"), createdAtEpochMillis = dateMillis,
            quoteId = "offer-quote", quoteKind = "bolt12",
            receivedPayments = listOf(CashuRequestPayment("payment-one", 21, dateMillis),
                                      CashuRequestPayment("payment-two", 34, dateMillis)))
        val quote = mintQuote("lno1fixture", PaymentMethodKind.Bolt12, MintQuoteState.Paid, 100, 55, 0, 1)
        val sections = TransactionTechnicalDetails.requestSections(request, quote)
        assertEquals(listOf("Request", "Quote", "Payment 1", "Payment 2"), sections.map { it.title })
        assertEquals("offer-intent", sections.row("ID")?.fullValue)
        assertEquals("Lightning (BOLT12)", sections.row("Method")?.value)
        assertEquals("Any", sections.row("Amount")?.value)
        assertEquals("55 sat", sections.row("Total received")?.value)
        assertEquals("55 sat", sections.row("Amount issued")?.value)
        assertEquals("100 sat", sections.row("Amount paid")?.value)
        assertEquals("offer-quote", sections.row("Quote ID")?.fullValue)
        assertEquals("https://mint.example.com", sections.row("Mint")?.value)
        val copied = TransactionTechnicalDetails.copyAllText(sections)
        assertTrue(copied.contains("Transaction ID: payment-one"))
        assertTrue(copied.contains("Transaction ID: payment-two"))
        assertNull(sections.row("Status"))
    }

    @Test fun requestWithoutStoredQuoteRetainsRequestAndPaymentReferences() {
        val request = CashuRequest(id = "offer", encoded = "lno1fixture", createdAtEpochMillis = dateMillis,
            quoteId = "missing-quote", quoteKind = "bolt12")
        val sections = TransactionTechnicalDetails.requestSections(request)
        assertEquals("missing-quote", sections.row("Quote ID")?.fullValue)
        assertEquals("lno1fixture", sections.row("Request")?.fullValue)
        assertNull(sections.row("State"))
        assertEquals(listOf("Request", "Quote"), sections.map { it.title })
    }

    @Test fun onchainExplorerUsesResolvedQuoteProofAndTransactionProofTakesPrecedence() {
        val tx = transaction(type = TransactionType.Outgoing, kind = TransactionKind.Onchain).copy(
            quoteId = "melt-quote", invoice = "bc1qfixture", preimage = "")
        val quote = MeltQuoteInfo(id = "melt-quote", amount = 21, feeReserve = 2, request = "bc1qfixture",
            paymentMethod = PaymentMethodKind.Onchain, state = MeltQuoteState.Paid,
            expiryEpochSeconds = 0, paymentProof = proof, mintUrl = "https://mint.example.com")
        val sections = TransactionTechnicalDetails.sections(tx, meltQuote = quote)
        assertEquals(proof, sections.row("Transaction ID")?.fullValue)
        assertEquals("https://mempool.space/tx/$proof", TransactionTechnicalDetails.explorerUrl(tx, sections))
        val latest = tx.copy(preimage = cdkId)
        assertEquals("https://mempool.space/tx/$cdkId",
            TransactionTechnicalDetails.explorerUrl(latest, TransactionTechnicalDetails.sections(latest, meltQuote = quote)))
        val noAddress = tx.copy(invoice = null, preimage = null)
        assertTrue(TransactionTechnicalDetails.explorerUrl(noAddress, TransactionTechnicalDetails.sections(noAddress, meltQuote = quote))!!.endsWith("/tx/$proof"))
    }

}
