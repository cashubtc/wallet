package com.cashu.me.Core

import com.cashu.me.Core.Protocols.StorageKeys
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
            val monitor = launch { fixture.service.monitorPayments(address, System.currentTimeMillis()) }
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
            val monitor = launch { fixture.service.monitorPayments(address, System.currentTimeMillis()) }
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
            val monitor = launch { fixture.service.monitorPayments(fixture.service.state.value.lightningAddress, 100_000) }
            delay(40)
            assertEquals(listOf("paid"), fixture.service.state.value.pendingPaidQuotes.map { it.id })
            assertTrue(receipts.isEmpty())
            monitor.cancelAndJoin()
            collector.cancelAndJoin()
        } finally { fixture.close() }
    }

    @Test(timeout = 10_000)
    fun confirmedCreditGivesVisibleSheetHapticOwnershipOnlyForItsPayment() = runBlocking {
        val fixture = Fixture(this, allowChecks = false)
        try {
            fixture.service.initializeWithSeed(byteArrayOf(1))
            fixture.service.connect()
            val address = fixture.service.state.value.lightningAddress
            val monitor = launch { fixture.service.monitorPayments(address, 100_750) }
            yield()
            val payment = NPCPaymentReceipt("new", address, 21, 101)
            assertEquals(ReceiveConfirmationOwner.InFlow, fixture.service.publishReceivedPayment(payment))
            assertEquals(ReceiveConfirmationOwner.Home, fixture.service.publishReceivedPayment(payment.copy(paidAtEpochSeconds = 99)))
            assertEquals(ReceiveConfirmationOwner.Home, fixture.service.publishReceivedPayment(payment.copy(address = "other@example.com")))
            assertEquals(ReceiveConfirmationOwner.Home, fixture.service.publishReceivedPayment(payment.copy(amount = 0)))
            assertFalse(payment.copy(paidAtEpochSeconds = null).belongsToReceiveSession(address, 100_750))
            monitor.cancelAndJoin()
            assertEquals(ReceiveConfirmationOwner.Home, fixture.service.publishReceivedPayment(payment))
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
