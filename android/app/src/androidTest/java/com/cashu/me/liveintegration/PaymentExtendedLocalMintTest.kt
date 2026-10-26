package com.cashu.me.liveintegration

import androidx.test.platform.app.InstrumentationRegistry
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Core.CashuRequestDeliveryReturned
import com.cashu.me.Core.PendingSendRecovery
import com.cashu.me.Core.SendRecoveryOutcome
import com.cashu.me.Models.TransactionStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import com.cashu.me.test.FullOnly
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

@FullOnly
class PaymentExtendedLocalMintTest : PaymentFixtureTest() {
    @Test fun cashuRequestFixedAndAmountlessHttpDelivery() = runBlocking {
        assertRequestDelivery("controlled")
    }

    @Test fun cdkCashuRequestFixedAndAmountlessHttpDelivery() = runBlocking {
        assertRequestDelivery("cdk")
    }

    // Runnable reproduction, excluded from the required manifest until CDK is fixed.
    @Test fun cashuRequestReceiverFeeRegression() = runBlocking {
        assumeTrue("Known CDK 0.18 receiver-fee regression; see CI/payment-tests/README.md",
            InstrumentationRegistry.getArguments().getString("cashu.paymentKnownRegressions") == "true")
        assertRequestDelivery("fees")
    }

    @Test fun cdkCashuRequestReceiverFeeRegression() = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("cashu.cdkFeeMintUrl")
        assumeTrue("Start the optional CDK 1000-ppk fixture", url != null)
        assertRequestDelivery("cdk", url)
    }

    private suspend fun assertRequestDelivery(mint: String, absoluteUrl: String? = null) {
        val credits = mutableListOf<Long>()
        for (amount in listOf(21L, null)) {
            val payer = wallet(mint, absoluteUrl)
            val receiver = wallet(mint, absoluteUrl)
            payer.fund()
            val encoded = call("$root/request", "POST", buildJsonObject {
                put("target", "$fixture$root/receive")
                putJsonArray("mints") { add(payer.url) }
                if (amount != null) put("amount", amount)
            }).getValue("request").jsonPrimitive.content
            payer.gateway.payCashuPaymentRequest(encoded, if (amount == null) 21 else null, payer.url)
            val delivered = call("$root/received-token")
            assertEquals(id, delivered.getValue("id").jsonPrimitive.content)
            val credited = receiver.receive(delivered.getValue("token").jsonPrimitive.content)
            credits += credited
            if (mint == "fees" || absoluteUrl != null) {
                println("REQUEST_FEE_DIAGNOSTIC mint=$mint fixed=${amount != null} requested=21 credited=$credited sender_remaining=${payer.balance()}")
            }
            if (mint == "controlled" || (mint == "cdk" && absoluteUrl == null)) {
                assertEquals("A zero-fee request must debit exactly the requested amount", 79, payer.balance())
            } else {
                assertTrue(payer.balance() <= 79)
            }
        }
        assertTrue("Fixed and amountless requests must both credit 21 sats; actual=$credits", credits.all { it == 21L })
    }

    @Test fun cashuRequestDeliveryFailureReturnsFunds() = runBlocking {
        val payer = wallet("controlled")
        payer.fund()
        val encoded = call("$root/request", "POST", buildJsonObject {
            put("target", "$fixture$root/unavailable"); put("amount", 21)
        }).getValue("request").jsonPrimitive.content
        val error = runCatching { payer.gateway.payCashuPaymentRequest(encoded, null, payer.url) }.exceptionOrNull()
        assertTrue(error is CashuRequestDeliveryReturned)
        assertTrue(payer.gateway.listPendingSendOperationIds(payer.url).isEmpty())
        assertEquals(100, payer.balance())
        payer.reopen()
        assertEquals(100, payer.balance())
        assertEquals(TransactionStatus.Failed, payer.gateway.listTransactions(mapOf(payer.url to listOf("sat"))).single {
            it.type == com.cashu.me.Models.TransactionType.Outgoing
        }.status)
        val recipient = wallet("controlled")
        assertEquals(100, recipient.receive(payer.send(100).token))
    }

    @Test fun failedAcknowledgementRevokesUnclaimedDeliveredToken() = runBlocking {
        val payer = wallet("controlled")
        val recipient = wallet("controlled")
        payer.fund()
        val encoded = request(payer.url, "receive?fail=true")
        val failure = runCatching { payer.gateway.payCashuPaymentRequest(encoded, null, payer.url) }.exceptionOrNull()
        assertTrue(failure is CashuRequestDeliveryReturned)
        val token = call("$root/received-token").getValue("token").jsonPrimitive.content
        assertTrue(runCatching { recipient.receive(token) }.isFailure)
        assertEquals(0, recipient.balance())
        assertEquals(100, payer.balance())
    }

    @Test fun failedAcknowledgementAfterClaimDoesNotRefund() = runBlocking {
        exerciseDelayedDelivery(claim = true)
    }

    @Test fun failedReclaimStaysPendingWithoutCreatingAnotherSend() = runBlocking {
        exerciseDelayedDelivery(claim = false)
    }

    private suspend fun exerciseDelayedDelivery(claim: Boolean) = coroutineScope {
        val payer = wallet("controlled")
        val recipient = wallet("controlled")
        payer.fund()
        val encoded = request(payer.url, "receive?fail=true&wait=true")
        val payment = async { runCatching { payer.gateway.payCashuPaymentRequest(encoded, null, payer.url) }.exceptionOrNull() }
        var token: String? = null
        repeat(100) {
            if (token == null) {
                token = runCatching { call("$root/received-token").getValue("token").jsonPrimitive.content }.getOrNull()
                if (token == null) delay(100)
            }
        }
        assertNotNull("Delivery must arrive before the fault", token)
        if (claim) assertEquals(21, recipient.receive(checkNotNull(token)))
        else arm("/v1/swap", "reject", count = 20)
        call("$root/release-delivery", "POST")
        val recovery = payment.await() as PendingSendRecovery
        assertEquals(if (claim) SendRecoveryOutcome.Completed else SendRecoveryOutcome.Pending, recovery.outcome)
        assertEquals(79, payer.balance())
        if (claim) {
            assertEquals(21, recipient.balance())
            assertTrue(payer.gateway.listPendingSendOperationIds(payer.url).isEmpty())
        } else {
            val pending = payer.gateway.listPendingSendOperationIds(payer.url)
            val retry = runCatching { payer.gateway.payCashuPaymentRequest(encoded, null, payer.url) }.exceptionOrNull()
            assertTrue(retry is PendingSendRecovery)
            assertEquals(pending, payer.gateway.listPendingSendOperationIds(payer.url))
        }
    }

    @Test fun existingSharedTokenIsNeverAutomaticallyReclaimed() = runBlocking {
        val payer = wallet("controlled")
        val recipient = wallet("controlled")
        payer.fund()
        val shared = payer.send(21)
        val failure = runCatching { payer.gateway.payCashuPaymentRequest(request(payer.url, "unavailable"), null, payer.url) }.exceptionOrNull()
        assertTrue(failure is PendingSendRecovery)
        assertEquals(79, payer.balance())
        assertEquals(21, recipient.receive(shared.token))
    }

    private fun request(mintUrl: String, target: String): String = call("$root/request", "POST", buildJsonObject {
        put("target", "$fixture$root/$target"); put("amount", 21)
        putJsonArray("mints") { add(mintUrl) }
    }).getValue("request").jsonPrimitive.content

    @Test fun repeatedFeePreviewReleasesReservedProofs() = runBlocking {
        val sender = wallet("controlled")
        sender.fund()
        repeat(3) { assertEquals(0, sender.gateway.estimateCashuPaymentRequestFee(80, sender.url)) }
        assertEquals(100, sender.balance())
        val recipient = wallet("controlled")
        assertEquals(100, recipient.receive(sender.send(100).token))
    }

    @Test fun multipleMintUnitsDoNotCrossCredit() = runBlocking {
        val payer = wallet("cdk")
        payer.fund(21)
        payer.gateway.ensureWallet(payer.url, "usd")
        val quote = payer.gateway.createMintQuote(125, PaymentMethodKind.Bolt11, payer.url, "usd")
        payer.awaitPaid(quote)
        assertEquals(125, payer.gateway.mintTokens(quote.id))
        val recipient = wallet("cdk")
        recipient.gateway.ensureWallet(recipient.url, "usd")
        val token = payer.gateway.sendEcashToken(25, null, null, payer.url, "usd")
        assertEquals(25, recipient.receive(token.token))
        assertEquals(21, payer.balance())
        assertEquals(100, payer.gateway.unitBalance(payer.url, "usd"))
        assertEquals(25, recipient.gateway.unitBalance(recipient.url, "usd"))
        assertEquals(0, recipient.balance())
    }

    @Test fun nwcPaymentLimitReplayAndBalance() = runBlocking {
        val payer = wallet("cdk")
        payer.fund()
        val relay = fixture.replace("http://", "ws://") + root + "/relay"
        val service = payer.gateway.createOrRestoreNwcService(payer.url, listOf(relay), ByteArray(64) { 1 }, null, 21_000u)
        service.start()
        try {
            fun request(method: String, invoice: String? = null, duplicate: Boolean = false) =
                call("$root/nwc", "POST", buildJsonObject {
                    put("uri", service.connectionUri); put("method", method); put("duplicate", duplicate)
                    if (invoice != null) putJsonObject("params") { put("invoice", invoice) }
                })
            assertEquals(100_000, request("get_balance").getValue("result").jsonObject.getValue("balance").jsonPrimitive.long)
            val rejected = request("pay_invoice", invoice(22))
            assertEquals("QUOTA_EXCEEDED", rejected.getValue("error").jsonObject.getValue("code").jsonPrimitive.content)
            assertEquals(100, payer.balance())
            val paid = request("pay_invoice", invoice(), true)
            assertNotNull(paid["result"])
            assertNull(paid["timeout"])
            val receipts = payer.gateway.listTransactions(mapOf(payer.url to listOf("sat"))).filter { it.type == com.cashu.me.Models.TransactionType.Outgoing }
            assertEquals(1, receipts.size)
            assertEquals(100, payer.balance() + 21 + receipts.single().fee)
            assertEquals(payer.balance() * 1000, request("get_balance").getValue("result").jsonObject.getValue("balance").jsonPrimitive.long)
            assertEquals(1, payer.gateway.listTransactions(mapOf(payer.url to listOf("sat"))).count {
                it.type == com.cashu.me.Models.TransactionType.Outgoing
            })
        } finally {
            service.stop()
            service.close()
        }
    }
}
