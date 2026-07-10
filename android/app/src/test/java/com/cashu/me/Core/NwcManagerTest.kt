package com.cashu.me.Core

import kotlinx.coroutines.runBlocking
import com.cashu.me.Core.CDK.NwcServiceGateway
import com.cashu.me.Core.CDK.NwcServiceHandle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NwcManagerTest {
    private val seed = ByteArray(64) { it.toByte() }

    @Test
    fun enablingCreatesAndStartsCdkServiceWithDefaultMint() = runBlocking {
        val store = FakeNwcStore()
        val gateway = FakeNwcGateway()
        val manager = manager(store, gateway)

        manager.setEnabled(true, defaultMintUrl = "https://mint.example/")

        assertTrue(manager.state.value.isEnabled)
        assertTrue(manager.state.value.isRunning)
        assertEquals("https://mint.example", manager.state.value.selectedMintUrl)
        assertEquals("nostr+walletconnect://service-1?relay=wss%3A%2F%2Frelay.example&secret=client-1", store.value.connectionUri)
        assertEquals(1, gateway.calls.size)
        assertEquals("https://mint.example", gateway.calls.single().mintUrl)
        assertEquals(listOf("wss://relay.example"), gateway.calls.single().relays)
        assertArrayEquals(seed, gateway.calls.single().seed)
        assertNull(gateway.calls.single().clientSecretKey)
        assertNull(gateway.calls.single().maxPaymentMsat)
    }

    @Test
    fun startupRestoresPersistedClientSecretAndPaymentLimit() = runBlocking {
        val existingUri = "nostr+walletconnect://service?relay=wss%3A%2F%2Frelay.example&secret=old%2Bsecret"
        val store = FakeNwcStore(
            NwcStoredSettings(
                isEnabled = true,
                selectedMintUrl = "https://mint.example",
                budgetSats = 21,
                connectionUri = existingUri,
            ),
        )
        val gateway = FakeNwcGateway()
        val manager = manager(store, gateway)

        manager.startIfEnabled()

        val call = gateway.calls.single()
        assertEquals("old+secret", call.clientSecretKey)
        assertEquals(21_000uL, call.maxPaymentMsat)
        assertEquals(existingUri, manager.state.value.connectionUri)
        assertTrue(manager.state.value.isRunning)
    }

    @Test
    fun resettingConnectionStopsOldServiceAndRotatesClientSecret() = runBlocking {
        val store = FakeNwcStore(
            NwcStoredSettings(
                isEnabled = true,
                selectedMintUrl = "https://mint.example",
                connectionUri = "nostr+walletconnect://service?secret=old-client",
            ),
        )
        val gateway = FakeNwcGateway()
        val manager = manager(store, gateway)
        manager.startIfEnabled()
        val previous = gateway.handles.single()

        manager.regenerateConnection()

        assertTrue(previous.stopped)
        assertTrue(previous.closed)
        assertEquals(2, gateway.calls.size)
        assertEquals("old-client", gateway.calls.first().clientSecretKey)
        assertNull(gateway.calls.last().clientSecretKey)
        assertTrue(manager.state.value.connectionUri.orEmpty().contains("secret=client-2"))
        assertTrue(manager.state.value.isRunning)
    }

    @Test
    fun enablingWithoutMintTurnsSettingBackOff() = runBlocking {
        val store = FakeNwcStore()
        val gateway = FakeNwcGateway()
        val manager = manager(store, gateway)

        manager.setEnabled(true)

        assertFalse(manager.state.value.isEnabled)
        assertFalse(manager.state.value.isRunning)
        assertEquals("Select a mint to use with Nostr Wallet Connect.", manager.state.value.errorMessage)
        assertTrue(gateway.calls.isEmpty())
    }

    @Test
    fun walletBoundarySnapshotCanBeRestoredAfterServiceReset() = runBlocking {
        val original = NwcStoredSettings(
            isEnabled = true,
            selectedMintUrl = "https://mint.example",
            budgetSats = 500,
            connectionUri = "nostr+walletconnect://service?secret=rollback-client",
        )
        val store = FakeNwcStore(original)
        val gateway = FakeNwcGateway()
        val manager = manager(store, gateway)
        manager.startIfEnabled()
        val snapshot = manager.snapshotWalletScopedData()

        manager.resetForWalletBoundary()
        assertEquals(NwcStoredSettings(), store.value)
        assertFalse(manager.state.value.isEnabled)

        manager.restoreWalletScopedData(snapshot)
        manager.startIfEnabled()

        assertEquals(original, store.value)
        assertEquals("rollback-client", gateway.calls.last().clientSecretKey)
        assertTrue(manager.state.value.isRunning)
    }

    @Test
    fun connectionUriParserHandlesFallbackQueryAndEncoding() {
        assertEquals(
            "a+b c",
            NwcManager.clientSecretFromConnectionUri("nostr+walletconnect://service?relay=x&secret=a%2Bb+c"),
        )
        assertNull(NwcManager.clientSecretFromConnectionUri("not a uri"))
    }

    private fun manager(store: FakeNwcStore, gateway: FakeNwcGateway) = NwcManager(
        store = store,
        gateway = gateway,
        seedProvider = { seed },
        relayProvider = { listOf("wss://relay.example") },
    )
}

private class FakeNwcStore(initial: NwcStoredSettings = NwcStoredSettings()) : NwcStore {
    var value: NwcStoredSettings = initial

    override fun load(): NwcStoredSettings = value

    override fun saveEnabled(value: Boolean) {
        this.value = this.value.copy(isEnabled = value)
    }

    override fun saveSelectedMint(value: String?) {
        this.value = this.value.copy(selectedMintUrl = value)
    }

    override fun saveBudgetSats(value: Long?) {
        this.value = this.value.copy(budgetSats = value)
    }

    override fun saveConnectionUri(value: String?) {
        this.value = this.value.copy(connectionUri = value)
    }

    override fun clear() {
        value = NwcStoredSettings()
    }
}

private class FakeNwcGateway : NwcServiceGateway {
    data class Call(
        val mintUrl: String,
        val relays: List<String>,
        val seed: ByteArray,
        val clientSecretKey: String?,
        val maxPaymentMsat: ULong?,
    )

    val calls = mutableListOf<Call>()
    val handles = mutableListOf<FakeNwcHandle>()

    override suspend fun createOrRestoreNwcService(
        mintUrl: String,
        relays: List<String>,
        seed: ByteArray,
        clientSecretKey: String?,
        maxPaymentMsat: ULong?,
    ): NwcServiceHandle {
        val number = calls.size + 1
        calls += Call(mintUrl, relays, seed.copyOf(), clientSecretKey, maxPaymentMsat)
        return FakeNwcHandle(
            connectionUri = "nostr+walletconnect://service-$number?" +
                "relay=wss%3A%2F%2Frelay.example&secret=${clientSecretKey ?: "client-$number"}",
        ).also(handles::add)
    }
}

private class FakeNwcHandle(
    override val connectionUri: String,
) : NwcServiceHandle {
    private var running = false
    var stopped = false
    var closed = false

    override suspend fun start() {
        running = true
    }

    override suspend fun stop() {
        stopped = true
        running = false
    }

    override suspend fun isRunning(): Boolean = running

    override suspend fun close() {
        closed = true
    }
}
