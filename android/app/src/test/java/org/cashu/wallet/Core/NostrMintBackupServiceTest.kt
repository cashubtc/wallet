package org.cashu.wallet.Core

import org.junit.Assert.assertEquals
import org.junit.Test

class NostrMintBackupServiceTest {
    @Test
    fun keepsOnlyWebsocketRelays() {
        val relays = listOf(
            "wss://relay.damus.io",
            "https://not-a-relay.example.com",
            "ws://localhost:7777",
            "relay.example.com",
        )

        assertEquals(
            listOf("wss://relay.damus.io", "ws://localhost:7777"),
            NostrMintBackupService.normalizedNostrBackupRelays(relays),
        )
    }

    @Test
    fun trimsWhitespaceAndAcceptsMixedCaseSchemes(): Unit = assertEquals(
        listOf("WSS://relay.example.com", "wss://nos.lol"),
        NostrMintBackupService.normalizedNostrBackupRelays(
            listOf("  WSS://relay.example.com  ", "wss://nos.lol\n"),
        ),
    )

    @Test
    fun dedupesPreservingOrder() {
        val relays = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.damus.io",
        )

        assertEquals(
            listOf("wss://relay.damus.io", "wss://nos.lol"),
            NostrMintBackupService.normalizedNostrBackupRelays(relays),
        )
    }
}
