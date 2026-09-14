package com.cashu.me.Core

import com.cashu.me.Core.Protocols.StorageKeys
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class NPCReceiveMonitoringTest {
    @Test(timeout = 10_000)
    fun visibleSheetChecksWithoutPeriodicPollingAndStopsOnDismiss() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.service.initializeWithSeed(byteArrayOf(1))
            fixture.service.connect()
            val address = fixture.service.state.value.lightningAddress
            val monitor = launch { fixture.service.monitorPayments(NPCReceiveSession(address)) }
            delay(70)
            assertTrue("Focused receive must not wait for the two-minute poll", fixture.requests >= 3)
            monitor.cancelAndJoin()
            val stoppedCount = fixture.requests
            delay(40)
            assertEquals(stoppedCount, fixture.requests)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun focusedChecksRespectPrivacyAndStopWhenAddressIsDisabled() = runBlocking {
        val fixture = Fixture(this, allowChecks = false)
        try {
            fixture.service.initializeWithSeed(byteArrayOf(1))
            fixture.service.connect()
            val address = fixture.service.state.value.lightningAddress
            val monitor = launch { fixture.service.monitorPayments(NPCReceiveSession(address)) }
            delay(40)
            assertEquals(1, fixture.requests) // Connection only, no payment polling.
            fixture.settings.value = fixture.settings.value.copy(checkIncomingInvoices = true)
            delay(40)
            assertTrue(fixture.requests > 1)
            fixture.service.setEnabled(false)
            monitor.join()
            val stoppedCount = fixture.requests
            delay(30)
            assertEquals(stoppedCount, fixture.requests)
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun detectingPaymentWithAutoClaimOffDoesNotPublishConfirmation() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.service.setAutomaticClaim(false)
            fixture.quotes = listOf(NPCQuote("paid", 21, "https://mint.example", state = "PAID",
                locked = false, createdAtEpochSeconds = 100, paidAtEpochSeconds = 101))
            val receipts = mutableListOf<NPCPaymentReceipt>()
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                fixture.service.receivedPayments.collect { receipts += it }
            }
            fixture.service.initializeWithSeed(byteArrayOf(1))
            fixture.service.connect()
            val monitor = launch { fixture.service.monitorPayments(NPCReceiveSession(fixture.service.state.value.lightningAddress)) }
            delay(40)
            assertEquals(listOf("paid"), fixture.service.state.value.pendingPaidQuotes.map { it.id })
            assertTrue(receipts.isEmpty())
            monitor.cancelAndJoin()
            collector.cancelAndJoin()
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun baselineRejectsOldCreditsAndAcceptsNewCreditsRegardlessOfClockOrTimestamp() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.service.setAutomaticClaim(false)
            fixture.quotes = listOf(NPCQuote("old", 21, "https://mint.example", state = "PAID",
                locked = false, createdAtEpochSeconds = 100, paidAtEpochSeconds = 101))
            fixture.service.initializeWithSeed(byteArrayOf(1))
            fixture.service.connect()
            val address = fixture.service.state.value.lightningAddress
            val session = NPCReceiveSession(address)
            val payment = NPCPaymentReceipt("new", address, 21, 101)
            assertFalse(payment.belongsToReceiveSession(session))
            val monitor = launch { fixture.service.monitorPayments(session) }
            withTimeout(1_000) { while (session.priorPaidQuoteIds.value == null) yield() }
            assertEquals(setOf("old"), session.priorPaidQuoteIds.value)
            for (timestamp in listOf(0L, 100L, 101L, Long.MAX_VALUE, null)) {
                assertTrue(payment.copy(paidAtEpochSeconds = timestamp).belongsToReceiveSession(session))
                assertFalse(payment.copy(quoteId = "old", paidAtEpochSeconds = timestamp).belongsToReceiveSession(session))
            }
            assertEquals(ReceiveConfirmationOwner.InFlow, fixture.service.publishReceivedPayment(payment))
            assertEquals(ReceiveConfirmationOwner.Home, fixture.service.publishReceivedPayment(payment.copy(quoteId = "old")))
            assertFalse(payment.copy(address = "other@example.com").belongsToReceiveSession(session))
            assertFalse(payment.copy(amount = 0).belongsToReceiveSession(session))
            monitor.cancelAndJoin()
            assertEquals(ReceiveConfirmationOwner.Home, fixture.service.publishReceivedPayment(payment))

            // Resuming the same sheet must not absorb payments made while away.
            fixture.quotes = fixture.quotes + fixture.quotes.single().copy(id = "new")
            val resumed = launch { fixture.service.monitorPayments(session) }
            delay(30)
            assertTrue(payment.belongsToReceiveSession(session))
            resumed.cancelAndJoin()
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun paidInvoiceDoesNotConfirmUntilClaimFinishesAndDismissalDoesNotCancelClaim() = runBlocking {
        val fixture = Fixture(this)
        try {
            fixture.service.initializeWithSeed(byteArrayOf(1))
            fixture.service.connect()
            val address = fixture.service.state.value.lightningAddress
            val session = NPCReceiveSession(address)
            val monitor = launch { fixture.service.monitorPayments(session) }
            withTimeout(1_000) { while (session.priorPaidQuoteIds.value == null) yield() }
            val claimStarted = CompletableDeferred<Unit>()
            val creditReady = CompletableDeferred<Unit>()
            val confirmed = CompletableDeferred<ReceiveConfirmationOwner>()
            fixture.service.quoteClaimHandler = object : NPCQuoteClaimHandler {
                override fun isNPCQuoteProcessed(quoteId: String) = confirmed.isCompleted
                override suspend fun claimNPCQuote(quote: NPCQuote, p2pkPubkey: String?): Boolean {
                    claimStarted.complete(Unit)
                    creditReady.await()
                    confirmed.complete(fixture.service.publishReceivedPayment(
                        NPCPaymentReceipt(quote.id, address, quote.amount, quote.paidAtEpochSeconds)))
                    return true
                }
            }
            fixture.quotes = listOf(NPCQuote("new", 21, "https://mint.example", state = "PAID",
                locked = false, createdAtEpochSeconds = 0, paidAtEpochSeconds = null))
            claimStarted.await()
            assertFalse(confirmed.isCompleted)
            monitor.cancelAndJoin()
            creditReady.complete(Unit)
            assertEquals(ReceiveConfirmationOwner.Home, confirmed.await())
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun claimFailureCanBeRetriedWithoutReportingAnUncreditedPayment() = runBlocking {
        val fixture = Fixture(this, allowChecks = false)
        try {
            fixture.service.initializeWithSeed(byteArrayOf(1))
            fixture.service.connect()
            val paid = NPCQuote("paid", 21, "https://mint.example", state = "PAID",
                locked = false, createdAtEpochSeconds = 100, paidAtEpochSeconds = 101)
            fixture.quotes = listOf(paid)
            var attempts = 0
            fixture.service.quoteClaimHandler = object : NPCQuoteClaimHandler {
                override fun isNPCQuoteProcessed(quoteId: String) = attempts >= 2
                override suspend fun claimNPCQuote(quote: NPCQuote, p2pkPubkey: String?): Boolean {
                    assertEquals(listOf(paid), fixture.service.state.value.claimingQuotes)
                    attempts++
                    return attempts >= 2
                }
            }
            fixture.settings.value = fixture.settings.value.copy(checkIncomingInvoices = true)
            fixture.service.retryPayments()
            assertEquals(listOf(paid), fixture.service.state.value.pendingPaidQuotes)
            assertNotNull(fixture.service.state.value.errorMessage)
            assertTrue(fixture.service.state.value.claimingQuotes.isEmpty())
            fixture.service.retryPayments()
            assertEquals(2, attempts)
            assertTrue(fixture.service.state.value.pendingPaidQuotes.isEmpty())
            assertTrue(fixture.service.state.value.claimingQuotes.isEmpty())
            assertNull(fixture.service.state.value.errorMessage)
        } finally { fixture.close() }
    }

    private class Fixture(parent: CoroutineScope, allowChecks: Boolean = true) {
        private val scope = CoroutineScope(parent.coroutineContext + SupervisorJob())
        val settings = MutableStateFlow(SettingsState(checkIncomingInvoices = allowChecks,
            periodicallyCheckIncomingInvoices = false))
        var requests = 0
        var quotes = emptyList<NPCQuote>()
        val service = NPCService(
            prefs = InMemorySharedPreferences(mutableMapOf(StorageKeys.npcEnabled to true)),
            settingsState = settings,
            scope = scope,
            receiveRefreshIntervalMillis = 10,
            makeClient = { _, _ -> object : NPCClient {
                override suspend fun getQuotes(): List<NPCQuote> { requests += 1; return quotes }
                override suspend fun setMintUrl(mintUrl: String): String = mintUrl
                override fun close() = Unit
            } },
            deriveKeys = { "01".repeat(32) to "02".repeat(32) },
        )
        suspend fun close() { service.resetForWalletBoundary(); scope.cancel() }
    }
}
