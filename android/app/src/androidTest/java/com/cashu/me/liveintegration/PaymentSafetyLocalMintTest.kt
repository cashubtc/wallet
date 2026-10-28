package com.cashu.me.liveintegration

import androidx.test.platform.app.InstrumentationRegistry
import com.cashu.me.Core.CDK.CdkWalletGatewayImpl
import com.cashu.me.Core.CDK.MeltInputFeeChangedException
import com.cashu.me.Core.CDK.MeltPaymentRecoveryException
import com.cashu.me.Core.NostrService
import com.cashu.me.Models.MeltQuoteState
import com.cashu.me.Models.MeltSettlement
import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.MintQuoteState
import com.cashu.me.Models.PaymentMethodKind
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/** Production native gateway, real SQLite and mint proofs; only transport is controlled. */
open class PaymentFixtureTest {
    private val args = InstrumentationRegistry.getArguments()
    lateinit var fixture: String
    lateinit var id: String
    private val wallets = mutableListOf<TestWallet>()
    private val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "payments-${UUID.randomUUID()}")
    val root get() = "/sessions/$id"

    @Before fun setupFixture() {
        assumeTrue("Enable the local payment suite", args.getString("cashu.paymentFixtures") == "true")
        fixture = requireNotNull(args.getString("cashu.paymentFixtureUrl")) { "Payment fixture URL is required" }
        id = call("/sessions", "POST").getValue("id").jsonPrimitive.content
        directory.mkdirs()
    }

    @After fun closeFixture() = runBlocking {
        wallets.forEach { it.gateway.closeWalletRepository() }
        if (::id.isInitialized) call(root, "DELETE")
        directory.deleteRecursively()
        Unit
    }

    fun call(path: String, method: String = "GET", body: JsonObject? = null): JsonObject {
        val connection = (URL(fixture + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toString().toByteArray()) }
            check(connection.responseCode in 200..299) { "Fixture request failed: $path (${connection.responseCode})" }
            return Json.parseToJsonElement(connection.inputStream.bufferedReader().use { it.readText() }).jsonObject
        } finally { connection.disconnect() }
    }

    fun mintUrl(mint: String) = "$fixture$root/mint/$mint"
    suspend fun wallet(mint: String, absoluteUrl: String? = null): TestWallet = TestWallet(mint, absoluteUrl).also { it.open(); wallets += it }

    inner class TestWallet(val mint: String, absoluteUrl: String? = null) {
        var gateway = CdkWalletGatewayImpl()
        val url = absoluteUrl ?: mintUrl(mint)
        private val path = File(directory, "${UUID.randomUUID()}.db").absolutePath
        private lateinit var seed: String
        suspend fun open() {
            gateway.initializeLogging("warn")
            if (!::seed.isInitialized) seed = gateway.generateMnemonic()
            gateway.openWalletRepository(seed, path)
            gateway.ensureWallet(url)
        }
        suspend fun reopen() {
            gateway.closeWalletRepository()
            gateway = CdkWalletGatewayImpl()
            open()
        }
        suspend fun balance() = gateway.totalBalance(url)
        suspend fun quote(amount: Long) = gateway.createMintQuote(amount, PaymentMethodKind.Bolt11, url, "sat")
        suspend fun pay(quote: MintQuoteInfo, mint: String = this.mint): MintQuoteInfo {
            if (mint in listOf("controlled", "fees")) {
                call("$root/pay/$mint", "POST", buildJsonObject { put("invoice", quote.request) })
            }
            return awaitPaid(quote)
        }
        /** A second mint in this wallet's repository — the shape a transfer between two held mints runs in. */
        suspend fun hold(mint: String): String = mintUrl(mint).also { gateway.ensureWallet(it) }
        suspend fun awaitPaid(quote: MintQuoteInfo): MintQuoteInfo {
            repeat(80) {
                val current = gateway.checkMintQuote(quote.id)
                if (current.state == MintQuoteState.Paid || current.state == MintQuoteState.Issued) return current
                delay(100)
            }
            error("Mint quote did not become paid")
        }
        suspend fun fund(amount: Long = 100) { assertEquals(amount, gateway.mintTokens(pay(quote(amount)).id)) }
        suspend fun send(amount: Long, key: String? = null) = gateway.sendEcashToken(amount, "Payment test", key, url)
        suspend fun receive(token: String, keys: List<String> = emptyList()) = gateway.receiveEcashToken(token, keys)
    }

    fun invoice(amount: Long = 21, script: JsonObject? = null): String = call("$root/invoice", "POST", buildJsonObject {
        put("amount", amount)
        if (script != null) put("description", script.toString())
    }).getValue("invoice").jsonPrimitive.content

    fun arm(path: String, action: String, method: String = "POST", count: Int = 1) {
        call("$root/faults", "POST", buildJsonObject {
            put("path", path); put("action", action); put("method", method); put("remaining", count)
        })
    }

    suspend fun expectError(vararg fragments: String, operation: suspend () -> Unit) {
        val failure = runCatching { operation() }.exceptionOrNull()
        assertNotNull("Expected payment rejection", failure)
        val message = failure.toString().lowercase()
        assertTrue("Unexpected rejection: $failure", fragments.any { message.contains(it.lowercase()) })
    }
}

class PaymentSafetyLocalMintTest : PaymentFixtureTest() {
    @Test fun controlledQuoteRequiresPaymentAndIssuesOnlyOnce() = runBlocking {
        val wallet = wallet("controlled")
        val quote = wallet.quote(21)
        assertEquals(MintQuoteState.Unpaid, wallet.gateway.checkMintQuote(quote.id).state)
        assertEquals(0, wallet.balance())
        expectError("not paid", "unpaid", "20001", "Amount undefined") { wallet.gateway.mintTokens(quote.id) }
        wallet.gateway.recoverIncompleteSagas(wallet.url)
        wallet.pay(quote)
        assertEquals(21, wallet.gateway.mintTokens(quote.id))
        assertEquals(MintQuoteState.Issued, wallet.gateway.checkMintQuote(quote.id).state)
        assertEquals(0, wallet.gateway.mintUnissuedQuotes(wallet.url, "sat"))
        assertEquals(21, wallet.balance())
    }

    @Test fun bolt11InternalAndExternalPaymentsConserveBalanceAndPersistReceipt() = runBlocking {
        for (mint in listOf("controlled", "cdk")) {
            val payer = wallet(mint)
            val recipient = wallet(mint)
            payer.fund()
            val incoming = if (mint == "controlled") recipient.quote(21) else null
            val quote = payer.gateway.createMeltQuote(incoming?.request ?: invoice(), null, payer.url)
            // Nutshell 0.20.1 returns PENDING before persisting its async task.
            if (mint == "controlled") arm("/v1/melt/quote/bolt11/", "delay", "GET")
            val result = payer.gateway.meltTokens(quote.id, payer.url).result
            assertEquals(MeltSettlement.Settled, result.settlement)
            assertEquals(21, result.amount)
            assertEquals(100 - 21 - result.feePaid, payer.balance())
            payer.reopen()
            val history = payer.gateway.listTransactions(mapOf(payer.url to listOf("sat"))).filter { it.quoteId == quote.id }
            assertEquals(1, history.size)
            assertEquals(21, history.single().amount)
            assertEquals(result.feePaid, history.single().fee)
            if (incoming != null) {
                recipient.awaitPaid(incoming)
                assertEquals(21, recipient.gateway.mintTokens(incoming.id))
            }
        }
    }

    @Test fun lostMeltResponseRecoversWithoutSecondDebit() = runBlocking {
        val payer = wallet("cdk")
        payer.fund()
        val quote = payer.gateway.createMeltQuote(invoice(), null, payer.url)
        arm("/v1/melt/bolt11", "lose_response")
        runCatching { payer.gateway.meltTokens(quote.id, payer.url) }
        payer.gateway.recoverIncompleteSagas(payer.url)
        assertEquals(MeltQuoteState.Paid, payer.gateway.checkMeltQuoteStatus(quote.id, payer.url).state)
        val after = payer.balance()
        assertTrue(after < 100)
        payer.reopen()
        payer.gateway.recoverIncompleteSagas(payer.url)
        assertEquals(after, payer.balance())
        val receipts = payer.gateway.listTransactions(mapOf(payer.url to listOf("sat"))).filter { it.quoteId == quote.id }
        assertEquals(1, receipts.size)
        assertEquals(100, after + 21 + receipts.single().fee)
        val records = call(root).getValue("requests").jsonArray
        assertTrue(records.any { it.jsonObject["fault"]?.jsonPrimitive?.contentOrNull == "lose_response" && it.jsonObject["forwarded"]?.jsonPrimitive?.boolean == true })
    }

    @Test fun lostMintResponseRecoversPaidQuoteExactlyOnce() = runBlocking {
        assertLostMintResponse("controlled")
    }

    @Test fun cdkLostMintResponseRecoversPaidQuoteExactlyOnce() = runBlocking {
        assertLostMintResponse("cdk")
    }

    private suspend fun assertLostMintResponse(mint: String) {
        val wallet = wallet(mint)
        val quote = wallet.pay(wallet.quote(42))
        arm("/v1/mint/bolt11", "lose_response")
        runCatching { wallet.gateway.mintTokens(quote.id) }
        wallet.reopen()
        wallet.gateway.recoverIncompleteSagas(wallet.url)
        wallet.gateway.mintUnissuedQuotes(wallet.url, "sat")
        assertEquals(42, wallet.balance())
        assertEquals(0, wallet.gateway.mintUnissuedQuotes(wallet.url, "sat"))
        assertEquals(1, wallet.gateway.listTransactions(mapOf(wallet.url to listOf("sat"))).count { it.quoteId == quote.id })
        assertTrue(call(root).getValue("requests").jsonArray.any {
            it.jsonObject["fault"]?.jsonPrimitive?.contentOrNull == "lose_response" &&
                it.jsonObject["forwarded"]?.jsonPrimitive?.boolean == true
        })
    }

    @Test fun insufficientFundsAndOfflineQuoteDoNotDebit() = runBlocking {
        val wallet = wallet("controlled")
        wallet.fund()
        val quote = wallet.gateway.createMeltQuote(invoice(101), null, wallet.url)
        val rejected = runCatching { wallet.gateway.meltTokens(quote.id, wallet.url) }.exceptionOrNull()
        assertNotNull("The oversized payment must be rejected", rejected)
        if (rejected is MeltPaymentRecoveryException) {
            // CDK 0.18.2 may journal preparation before discovering the shortfall.
            // A compensated operation is safe only once its reservations clear.
            assertFalse("Insufficient funds must not leave an unresolved payment", rejected.unresolved)
        } else {
            val message = rejected.toString().lowercase()
            assertTrue("Unexpected rejection: $rejected", "insufficient" in message || "not enough" in message)
        }
        assertEquals(100, wallet.balance())
        wallet.reopen()
        assertEquals(100, wallet.balance())
        arm("/v1/melt/quote/bolt11", "reject")
        expectError("503", "unavailable", "http") { wallet.gateway.createMeltQuote(invoice(), null, wallet.url) }
        assertEquals(100, wallet.balance())
        val recipient = wallet("controlled")
        assertEquals(100, recipient.receive(wallet.send(100).token))
    }

    @Test fun backendFailureReturnsSpendableFunds() = runBlocking {
        val payer = wallet("cdk")
        payer.fund()
        val request = invoice(script = buildJsonObject {
            put("pay_invoice_state", "UNPAID"); put("check_payment_state", "UNPAID")
            put("pay_err", true); put("check_err", false)
        })
        val quote = payer.gateway.createMeltQuote(request, null, payer.url)
        runCatching { payer.gateway.meltTokens(quote.id, payer.url) }
        payer.gateway.recoverIncompleteSagas(payer.url)
        assertEquals(100, payer.balance())
        val receiver = wallet("cdk")
        assertEquals(100, receiver.receive(payer.send(100).token))
    }

    @Test fun paidBolt11RecoversAfterDatabaseReopenExactlyOnce() = runBlocking {
        val wallet = wallet("controlled")
        val quote = wallet.pay(wallet.quote(42))
        wallet.reopen()
        assertEquals(42, wallet.gateway.mintUnissuedQuotes(wallet.url, "sat"))
        assertEquals(0, wallet.gateway.mintUnissuedQuotes(wallet.url, "sat"))
        assertEquals(1, wallet.gateway.listTransactions(mapOf(wallet.url to listOf("sat"))).count { it.quoteId == quote.id })
    }

    @Test fun ecashClaimAfterReopenCannotBeRevokedOrReceivedTwice() = runBlocking {
        val sender = wallet("controlled")
        val receiver = wallet("controlled")
        sender.fund()
        val token = sender.send(21).token
        val operation = sender.gateway.listPendingSendOperationIds(sender.url).single()
        sender.reopen()
        assertTrue(sender.gateway.listPendingSendOperationIds(sender.url).contains(operation))
        assertEquals(21, receiver.receive(token))
        assertTrue(sender.gateway.checkPendingSendClaimed(sender.url, operation))
        expectError("spent", "claimed", "not found", "completed", "unknown") { sender.gateway.revokePendingSend(sender.url, operation) }
        expectError("spent", "already", "11001") { receiver.receive(token) }
        assertEquals(79, sender.balance())
        assertEquals(21, receiver.balance())
    }

    @Test fun revokeUnclaimedTokenRestoresSpendability() = runBlocking {
        val sender = wallet("controlled")
        val receiver = wallet("controlled")
        sender.fund()
        val token = sender.send(21).token
        val operation = sender.gateway.listPendingSendOperationIds(sender.url).single()
        assertEquals(21, sender.gateway.revokePendingSend(sender.url, operation))
        expectError("spent", "already", "11001") { receiver.receive(token) }
        assertEquals(100, receiver.receive(sender.send(100).token))
    }

    @Test fun zeroFeeReceivePreviewMatchesRedemption() = runBlocking {
        val sender = wallet("controlled")
        val receiver = wallet("controlled")
        sender.fund()
        val token = sender.gateway.sendEcashToken(21, "Preview memo", null, sender.url).token
        val preview = receiver.gateway.previewReceiveToken(token)
        assertEquals(21L, preview.info.amount)
        assertEquals("sat", preview.info.unit)
        assertEquals("Preview memo", preview.info.memo)
        assertEquals(0L, preview.fee)
        assertEquals(0L, receiver.balance())
        assertEquals(preview.info.amount - preview.fee, receiver.receive(token))
    }

    @Test fun feeChargingMintMaxSendAndReceiveConserveValue() = runBlocking {
        assertFeeChargingMintMaxSendAndReceive("fees")
    }

    @Test fun cdkFeeChargingMintMaxSendAndReceiveConserveValue() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("cashu.cdkFeeMintUrl")
        assumeTrue("Start the optional CDK 1000-ppk fixture", url != null)
        assertFeeChargingMintMaxSendAndReceive("cdk", url)
    }

    private suspend fun assertFeeChargingMintMaxSendAndReceive(mint: String, absoluteUrl: String? = null) {
        val sender = wallet(mint, absoluteUrl)
        val receiver = wallet(mint, absoluteUrl)
        sender.fund()
        val sent = sender.send(100)
        assertEquals(0, sent.fee)
        val preview = receiver.gateway.previewReceiveToken(sent.token)
        assertEquals(100L, preview.info.amount)
        assertTrue(preview.fee > 0)
        assertEquals(0L, receiver.balance())
        val received = receiver.receive(sent.token)
        assertEquals(preview.info.amount - preview.fee, received)
        assertEquals(0, sender.balance())
        assertEquals(received, receiver.balance())
    }

    @Test fun p2pkWrongKeyThenCorrectKeyOnBothMints() = runBlocking {
        val privateKey = "0".repeat(63) + "1"
        val publicKey = "02" + NostrService.publicKeyHex(privateKey)
        for (mint in listOf("nutshell", "cdk")) {
            val sender = wallet(mint)
            val receiver = wallet(mint)
            sender.fund()
            val token = sender.send(21, publicKey).token
            expectError("key", "signature", "witness", "sign", "p2pk") { receiver.receive(token, listOf("0".repeat(63) + "2")) }
            assertEquals(0, receiver.balance())
            assertEquals(21, receiver.receive(token, listOf(privateKey)))
        }
    }

    @Test fun concurrentReceiversCannotDoubleCredit() = runBlocking {
        assertConcurrentReceivers("controlled")
    }

    @Test fun cdkConcurrentReceiversCannotDoubleCredit() = runBlocking {
        assertConcurrentReceivers("cdk")
    }

    private suspend fun assertConcurrentReceivers(mint: String) {
        val sender = wallet(mint)
        val a = wallet(mint)
        val b = wallet(mint)
        sender.fund()
        val token = sender.send(21).token
        val results = coroutineScope {
            val first = async { runCatching { a.receive(token) }.isSuccess }
            val second = async { runCatching { b.receive(token) }.isSuccess }
            listOf(first.await(), second.await())
        }
        assertEquals(1, results.count { it })
        assertEquals(21, a.balance() + b.balance())
    }

    // Transfers between two held mints. The two controlled mints are separate
    // Lightning backends: a melt at one settles there but does not credit the
    // other's invoice. Each test pays the destination invoice explicitly, which
    // also proves issuance follows payment rather than the melt.

    private suspend fun TestWallet.history(mintUrl: String) =
        gateway.listTransactions(mapOf(mintUrl to listOf("sat")))

    @Test fun crossMintTransferExactConservesValue() = runBlocking {
        val wallet = wallet("controlled")
        val destination = wallet.hold("fees")
        wallet.fund()
        val incoming = wallet.gateway.createMintQuote(40, PaymentMethodKind.Bolt11, destination, "sat")
        val outgoing = wallet.gateway.createMeltQuote(incoming.request, null, wallet.url)
        assertEquals(40, outgoing.amount)
        arm("/v1/melt/quote/bolt11/", "delay", "GET")
        val result = wallet.gateway.meltTokens(outgoing.id, wallet.url).result
        assertEquals(MeltSettlement.Settled, result.settlement)
        assertEquals(MintQuoteState.Unpaid, wallet.gateway.checkMintQuote(incoming.id).state)
        wallet.pay(incoming, "fees")
        assertEquals(40, wallet.gateway.mintTokens(incoming.id))
        assertEquals(100 - 40 - result.feePaid, wallet.balance())
        assertEquals(40, wallet.gateway.totalBalance(destination))
        assertEquals(1, wallet.history(wallet.url).count { it.quoteId == outgoing.id })
        assertEquals(1, wallet.history(destination).count { it.quoteId == incoming.id })
    }

    @Test fun crossMintMaxOnFeeMintIsAcceptedByMint() = runBlocking {
        val wallet = wallet("fees")
        val destination = wallet.hold("controlled")
        wallet.fund()
        val plan = wallet.gateway.createMaxCrossMintQuotes(wallet.url, destination)
        val amount = requireNotNull(plan.mintQuote.amount)
        assertEquals(amount, plan.meltQuote.amount)
        assertTrue("1000 ppk charges one unit per input proof", plan.inputFee > 0)
        assertEquals(100, amount + plan.meltQuote.feeReserve + plan.inputFee)
        // Only the returned pair is kept locally, however many probes the search made.
        assertEquals(listOf(plan.mintQuote.id), wallet.gateway.listUnissuedMintQuotes().map { it.id })
        arm("/v1/melt/quote/bolt11/", "delay", "GET")
        val result = wallet.gateway.meltAllUnspentSkippingSwap(plan.meltQuote.id, wallet.url, plan.inputFee).result
        assertEquals(MeltSettlement.Settled, result.settlement)
        wallet.pay(plan.mintQuote, "controlled")
        assertEquals(amount, wallet.gateway.mintTokens(plan.mintQuote.id))
        // CDK reports the input fee inside the fee paid, and the unused part of
        // the reserve comes back as change rather than being lost.
        assertEquals(100, wallet.balance() + amount + result.feePaid)
        assertTrue(result.feePaid >= plan.inputFee)
        assertTrue(result.feePaid <= plan.inputFee + plan.meltQuote.feeReserve)
    }

    @Test fun crossMintTransferLostMeltResponseDoesNotDoubleDebit() = runBlocking {
        val wallet = wallet("controlled")
        val destination = wallet.hold("fees")
        wallet.fund()
        val incoming = wallet.gateway.createMintQuote(40, PaymentMethodKind.Bolt11, destination, "sat")
        val outgoing = wallet.gateway.createMeltQuote(incoming.request, null, wallet.url)
        arm("/v1/melt/bolt11", "lose_response")
        // Nutshell 0.20.1 can answer an immediate status read before its
        // background task has persisted the payment.
        arm("/v1/melt/quote/bolt11/", "delay", "GET")
        runCatching { wallet.gateway.meltTokens(outgoing.id, wallet.url) }
        wallet.gateway.recoverIncompleteSagas(wallet.url)
        assertEquals(MeltQuoteState.Paid, wallet.gateway.checkMeltQuoteStatus(outgoing.id, wallet.url).state)
        wallet.pay(incoming, "fees")
        assertEquals(40, wallet.gateway.mintUnissuedQuotes(destination, "sat"))
        assertEquals(0, wallet.gateway.mintUnissuedQuotes(destination, "sat"))
        val debits = wallet.history(wallet.url).filter { it.quoteId == outgoing.id }
        assertEquals(1, debits.size)
        assertEquals(100, wallet.balance() + 40 + debits.single().fee)
    }

    @Test fun crossMintMaxLostMeltResponseRecoversChange() = runBlocking {
        val wallet = wallet("fees")
        val destination = wallet.hold("controlled")
        wallet.fund()
        val plan = wallet.gateway.createMaxCrossMintQuotes(wallet.url, destination)
        val amount = requireNotNull(plan.mintQuote.amount)
        arm("/v1/melt/bolt11", "lose_response")
        arm("/v1/melt/quote/bolt11/", "delay", "GET")
        runCatching { wallet.gateway.meltAllUnspentSkippingSwap(plan.meltQuote.id, wallet.url, plan.inputFee) }
        wallet.gateway.recoverIncompleteSagas(wallet.url)
        assertEquals(MeltQuoteState.Paid, wallet.gateway.checkMeltQuoteStatus(plan.meltQuote.id, wallet.url).state)
        val debits = wallet.history(wallet.url).filter { it.quoteId == plan.meltQuote.id }
        assertEquals(1, debits.size)
        // Every proof went in as an input, so whatever the payment did not use
        // has to come back through recovery or the wallet lost it.
        assertEquals(100, wallet.balance() + amount + debits.single().fee)
    }

    @Test fun crossMintTransferReopenBetweenMeltAndMintIssuesExactlyOnce() = runBlocking {
        val wallet = wallet("fees")
        val destination = wallet.hold("controlled")
        wallet.fund()
        val plan = wallet.gateway.createMaxCrossMintQuotes(wallet.url, destination)
        val amount = requireNotNull(plan.mintQuote.amount)
        arm("/v1/melt/quote/bolt11/", "delay", "GET")
        wallet.gateway.meltAllUnspentSkippingSwap(plan.meltQuote.id, wallet.url, plan.inputFee)
        call("$root/pay/controlled", "POST", buildJsonObject { put("invoice", plan.mintQuote.request) })
        // The app was killed after paying and before issuing. Only what CDK
        // persisted for the returned pair can finish the transfer.
        wallet.reopen()
        wallet.hold("controlled")
        wallet.awaitPaid(plan.mintQuote)
        assertEquals(amount, wallet.gateway.mintUnissuedQuotes(destination, "sat"))
        assertEquals(0, wallet.gateway.mintUnissuedQuotes(destination, "sat"))
        assertEquals(1, wallet.history(destination).count { it.quoteId == plan.mintQuote.id })
    }

    @Test fun abandonedCrossMintPlanLeavesNoUnissuedQuote() = runBlocking {
        val wallet = wallet("fees")
        val destination = wallet.hold("controlled")
        wallet.fund()

        val incoming = wallet.gateway.createMintQuote(40, PaymentMethodKind.Bolt11, destination, "sat")
        val outgoing = wallet.gateway.createMeltQuote(incoming.request, null, wallet.url)
        assertTrue(wallet.gateway.removeUnusedQuotes(incoming.id, outgoing.id))
        val abandoned = wallet.gateway.createMaxCrossMintQuotes(wallet.url, destination)
        assertTrue(wallet.gateway.removeUnusedQuotes(abandoned.mintQuote.id, abandoned.meltQuote.id))

        assertTrue(wallet.gateway.listUnissuedMintQuotes().isEmpty())
        assertEquals(0, wallet.gateway.mintUnissuedQuotes(destination, "sat"))
        assertEquals(100, wallet.balance())

        // Once the melt has started, the destination quote is the only handle
        // on the paid invoice and must survive a discard.
        val started = wallet.gateway.createMaxCrossMintQuotes(wallet.url, destination)
        arm("/v1/melt/quote/bolt11/", "delay", "GET")
        wallet.gateway.meltAllUnspentSkippingSwap(started.meltQuote.id, wallet.url, started.inputFee)
        assertFalse(wallet.gateway.removeUnusedQuotes(started.mintQuote.id, started.meltQuote.id))
        assertEquals(listOf(started.mintQuote.id), wallet.gateway.listUnissuedMintQuotes().map { it.id })
    }

    /**
     * A proof set that no longer costs what the quote was sized for must not be
     * melted: the amount would not fit. The reservation is released and the
     * same quote still works once the fee matches.
     */
    @Test fun crossMintMaxChangedInputFeeCancelsBeforeAnythingIsSpent() = runBlocking {
        val wallet = wallet("fees")
        val destination = wallet.hold("controlled")
        wallet.fund()
        val plan = wallet.gateway.createMaxCrossMintQuotes(wallet.url, destination)

        val failure = runCatching {
            wallet.gateway.meltAllUnspentSkippingSwap(plan.meltQuote.id, wallet.url, plan.inputFee + 1)
        }.exceptionOrNull()
        assertTrue("Unexpected outcome: $failure", failure is MeltInputFeeChangedException)
        assertEquals(100, wallet.balance())

        arm("/v1/melt/quote/bolt11/", "delay", "GET")
        val result = wallet.gateway.meltAllUnspentSkippingSwap(plan.meltQuote.id, wallet.url, plan.inputFee).result
        assertEquals(MeltSettlement.Settled, result.settlement)
    }
}
