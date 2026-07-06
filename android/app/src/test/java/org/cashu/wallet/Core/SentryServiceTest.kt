package org.cashu.wallet.Core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeSentryGateway : SentryGateway {
    var startedDsns = mutableListOf<String>()
    var closeCount = 0
    var captured = mutableListOf<Throwable>()
    var breadcrumbs = mutableListOf<Pair<String, String>>()

    override fun start(dsn: String) {
        startedDsns.add(dsn)
    }

    override fun close() {
        closeCount += 1
    }

    override fun capture(error: Throwable) {
        captured.add(error)
    }

    override fun breadcrumb(message: String, category: String) {
        breadcrumbs.add(message to category)
    }
}

class SentryServiceTest {
    @Test
    fun sentryIsOffByDefault() {
        assertFalse(SettingsState().sentryEnabled)
    }

    @Test
    fun disabledServiceNeverTouchesTheSdk() {
        val gateway = FakeSentryGateway()
        val service = SentryService(gateway, isEnabled = { false })

        service.initialize()
        service.capture(RuntimeException("boom"))
        service.breadcrumb("melt started")

        assertTrue(gateway.startedDsns.isEmpty())
        assertTrue(gateway.captured.isEmpty())
        assertTrue(gateway.breadcrumbs.isEmpty())
    }

    @Test
    fun enabledServiceStartsCapturesAndAddsBreadcrumbs() {
        val gateway = FakeSentryGateway()
        val service = SentryService(gateway, isEnabled = { true })
        val error = RuntimeException("boom")

        service.initialize()
        service.capture(error)
        service.breadcrumb("melt started")

        assertEquals(1, gateway.startedDsns.size)
        assertEquals(listOf<Throwable>(error), gateway.captured)
        assertEquals(listOf("melt started" to "wallet"), gateway.breadcrumbs)
    }

    @Test
    fun breadcrumbDefaultsToWalletCategory() {
        val gateway = FakeSentryGateway()
        val service = SentryService(gateway, isEnabled = { true })

        service.breadcrumb("quote paid")
        service.breadcrumb("relay error", category = "nostr")

        assertEquals(listOf("quote paid" to "wallet", "relay error" to "nostr"), gateway.breadcrumbs)
    }

    @Test
    fun shutdownClosesEvenWhenDisabled() {
        val gateway = FakeSentryGateway()
        val service = SentryService(gateway, isEnabled = { false })

        service.shutdown()

        assertEquals(1, gateway.closeCount)
    }

    @Test
    fun initializeIsIdempotentUntilShutdown() {
        val gateway = FakeSentryGateway()
        val service = SentryService(gateway, isEnabled = { true })

        service.initialize()
        service.initialize()
        assertEquals(1, gateway.startedDsns.size)

        service.shutdown()
        service.initialize()
        assertEquals(2, gateway.startedDsns.size)
    }

    @Test
    fun toggleContractStartsOnEnableAndClosesOnDisable() {
        val gateway = FakeSentryGateway()
        var enabled = false
        val service = SentryService(gateway, isEnabled = { enabled })

        // Mirrors SettingsManager.setSentryEnabled: on -> initialize, off -> shutdown.
        enabled = true
        service.initialize()
        enabled = false
        service.shutdown()

        assertEquals(1, gateway.startedDsns.size)
        assertEquals(1, gateway.closeCount)
        assertTrue(gateway.captured.isEmpty())
    }

    @Test
    fun flagFlipsAreHonoredPerCall() {
        val gateway = FakeSentryGateway()
        var enabled = false
        val service = SentryService(gateway, isEnabled = { enabled })

        service.initialize()
        assertTrue(gateway.startedDsns.isEmpty())

        enabled = true
        service.initialize()
        assertEquals(1, gateway.startedDsns.size)

        enabled = false
        service.capture(RuntimeException("boom"))
        assertTrue(gateway.captured.isEmpty())
    }
}
