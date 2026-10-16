package com.cashu.me.liveintegration

import com.cashu.me.Core.FocusedMintQuoteMonitor
import com.cashu.me.Core.WalletManager
import com.cashu.me.Core.ReceiveConfirmationOwner
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.test.fixtures.AppTestFixture
import com.cashu.me.test.fixtures.FixtureMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** Real app container, released CDK, durable wallet and manually paid fake Lightning backend. */
class MintEventLocalMintTest : PaymentFixtureTest() {
    private fun transport(vararg values: Pair<String, Boolean>) = call("$root/transport", "POST", buildJsonObject {
        values.forEach { (key, value) -> put(key, value) }
    })
    private fun records() = call(root).getValue("requests").jsonArray.map { it.jsonObject }
    private fun checks(id: String) = records().count { it["method"]?.jsonPrimitive?.content == "GET" && it["path"]?.jsonPrimitive?.content == "/v1/mint/quote/bolt11/$id" }
    private fun issues() = records().filter { it["method"]?.jsonPrimitive?.content == "POST" && it["path"]?.jsonPrimitive?.content == "/v1/mint/bolt11" && it["signature_retry"]?.jsonPrimitive?.booleanOrNull != true }
    private fun sockets(kind: String) = call(root).getValue("ws").jsonArray.count { it.jsonObject["kind"]?.jsonPrimitive?.content == kind }
    private suspend fun until(timeout: Long = 15_000, condition: () -> Boolean) {
        withTimeout(timeout) { while (!condition()) delay(100) }
    }
    private fun pay(invoice: String) { call("$root/pay/controlled", "POST", buildJsonObject { put("invoice", invoice) }) }
    private fun verify(manager: WalletManager, id: String, amount: Long) {
        assertEquals(amount, manager.state.value.balance)
        assertEquals(1, manager.state.value.transactions.count { it.quoteId == id })
    }

    @Test fun meltOnlyRecoveryPreservesSettledAndRejectedPayments() = runBlocking {
        for (lostResponse in listOf(false, true)) {
            val payer = wallet("cdk")
            payer.fund(100)
            val quote = payer.gateway.createMeltQuote(invoice(), null, payer.url)
            arm("/v1/melt/bolt11", if (lostResponse) "lose_response" else "reject")
            runCatching { payer.gateway.meltTokens(quote.id, payer.url) }
            payer.gateway.finalizePendingMelts(payer.url)
            val after = payer.balance()
            if (lostResponse) {
                val status = payer.gateway.checkMeltQuoteStatus(quote.id, payer.url)
                assertEquals(com.cashu.me.Models.MeltQuoteState.Paid, status.state)
                val receipts = payer.gateway.listTransactions(mapOf(payer.url to listOf("sat"))).filter { it.quoteId == quote.id }
                assertEquals(1, receipts.size)
                assertEquals(100L, after + receipts.single().amount + receipts.single().fee)
            } else {
                assertEquals("Rejected outgoing payment returns all spendable funds", 100L, after)
            }
            payer.gateway.finalizePendingMelts(payer.url)
            assertEquals("Repeated recovery cannot debit twice", after, payer.balance())
        }
    }

    @Test fun pushReducesRequestsAndDuplicateEventsCreditOnce() = runBlocking {
        transport("enabled" to true, "duplicate" to true)
        AppTestFixture.launch(FixtureMode.LivePayments, paymentMintUrl = mintUrl("controlled")).use { app ->
            val manager = app.container.walletManager
            val quote = manager.createMintQuote(42, PaymentMethodKind.Bolt11, "sat", null)
            val before = checks(quote.id)
            val legacy = launch { FocusedMintQuoteMonitor().monitor(quote.id, { manager.refreshPendingMintQuote(it, ReceiveConfirmationOwner.InFlow).quote }) }
            delay(6_500)
            legacy.cancelAndJoin()
            val baseline = checks(quote.id) - before
            val first = launch { manager.observeMintQuote(quote.id).collect() }
            val second = launch { manager.observeMintQuote(quote.id).collect() }
            try {
                until { sockets("ack") > 0 }
                delay(1_000)
                val start = checks(quote.id)
                delay(6_500)
                val improved = checks(quote.id) - start
                assertTrue("Baseline must exercise real polling: $baseline", baseline >= 2)
                assertTrue("Idle subscription must reduce GETs: $baseline -> $improved", improved <= 1)
                assertEquals("Observers must share one subscription", 1, sockets("subscribe"))
                assertEquals(0L, manager.state.value.balance)
                val paidAt = System.nanoTime()
                pay(quote.request)
                until(15_000) { manager.state.value.balance == 42L }
                val latency = (System.nanoTime() - paidAt) / 1_000_000
                delay(500)
                verify(manager, quote.id, 42)
                assertEquals(1, issues().size)
                println("MINT_EVENT_EVIDENCE android baseline_gets=$baseline watcher_gets=$improved payment_ms=$latency")
            } finally { first.cancelAndJoin(); second.cancelAndJoin() }
        }
    }

    @Test fun failedIssuanceRetainsBackoffAcrossBackgroundResume() = runBlocking {
        transport("enabled" to true, "duplicate" to true)
        AppTestFixture.launch(FixtureMode.LivePayments, paymentMintUrl = mintUrl("controlled")).use { app ->
            val manager = app.container.walletManager
            val quote = manager.createMintQuote(23, PaymentMethodKind.Bolt11, "sat", null)
            arm("/v1/mint/bolt11", "reject", count = -1)
            val watch = launch { manager.observeMintQuote(quote.id).collect() }
            try {
                until { sockets("ack") > 0 }
                pay(quote.request)
                until { app.container.walletStore.loadMintQuoteSchedules()[quote.id]?.consecutiveFailures == 1 }
                val firstFailure = System.currentTimeMillis()
                val firstDeadline = app.container.walletStore.loadMintQuoteSchedules().getValue(quote.id).nextAttemptAtEpochMillis
                assertTrue(firstDeadline - firstFailure in 4_500..5_100)
                val attemptsAfterFailure = issues().size
                assertEquals(0L, manager.state.value.balance)
                manager.stopPendingQuoteForegroundPolling()
                delay(500)
                manager.startPendingQuoteForegroundPolling()
                delay(1_000)
                assertEquals("Resume/events cannot bypass persisted backoff", attemptsAfterFailure, issues().size)
                until { app.container.walletStore.loadMintQuoteSchedules()[quote.id]?.consecutiveFailures == 2 }
                assertTrue(System.currentTimeMillis() >= firstDeadline)
                val secondFailure = System.currentTimeMillis()
                val secondDeadline = app.container.walletStore.loadMintQuoteSchedules().getValue(quote.id).nextAttemptAtEpochMillis
                assertTrue(secondDeadline - secondFailure in 14_500..15_100)
                call("$root/faults", "DELETE")
                until(25_000) { manager.state.value.balance == 23L }
                assertTrue("Successful retry cannot precede persisted deadline", System.currentTimeMillis() >= secondDeadline)
                assertEquals(1, issues().count { it["status"]?.jsonPrimitive?.int == 200 })
                verify(manager, quote.id, 23)
                println("MINT_EVENT_EVIDENCE android retry_delays_ms=${firstDeadline - firstFailure},${secondDeadline - secondFailure}")
            } finally { watch.cancelAndJoin() }
        }
    }

    @Test fun disconnectAndMissedPushRecoverWithoutDuplicateCredit() = runBlocking {
        transport("enabled" to true)
        AppTestFixture.launch(FixtureMode.LivePayments, paymentMintUrl = mintUrl("controlled")).use { app ->
            val manager = app.container.walletManager
            val quote = manager.createMintQuote(31, PaymentMethodKind.Bolt11, "sat", null)
            val watch = launch { manager.observeMintQuote(quote.id).collect() }
            try {
                until { sockets("ack") > 0 }
                transport("disconnect" to true)
                until { sockets("connected") >= 2 }
                manager.stopPendingQuoteForegroundPolling()
                delay(750)
                pay(quote.request)
                delay(750)
                assertEquals(0L, manager.state.value.balance)
                manager.startPendingQuoteForegroundPolling()
                until { manager.state.value.balance == 31L }
                verify(manager, quote.id, 31)
                assertEquals(1, issues().size)
            } finally { watch.cancelAndJoin() }
        }
    }

    @Test fun unsupportedWebsocketStillSettlesThroughReleasedSdkFallback() = runBlocking {
        transport("enabled" to false)
        AppTestFixture.launch(FixtureMode.LivePayments, paymentMintUrl = mintUrl("controlled")).use { app ->
            val manager = app.container.walletManager
            val quote = manager.createMintQuote(19, PaymentMethodKind.Bolt11, "sat", null)
            val watch = launch { manager.observeMintQuote(quote.id).collect() }
            try {
                until { sockets("attempt") > 0 }
                assertEquals(0, sockets("connected"))
                pay(quote.request)
                until { manager.state.value.balance == 19L }
                verify(manager, quote.id, 19)
                assertEquals(1, issues().size)
            } finally { watch.cancelAndJoin() }
        }
    }
}
