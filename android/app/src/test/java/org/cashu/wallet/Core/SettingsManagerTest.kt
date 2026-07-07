package org.cashu.wallet.Core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SettingsManagerTest {
    @Test
    fun appLockIsOffByDefault() {
        assertEquals(false, SettingsState().appLockEnabled)
    }

    @Test
    fun p2pkSendNormalizationAcceptsXOnlyHex() {
        val xOnly = "a".repeat(64)

        assertEquals(
            "02$xOnly",
            SettingsManager.normalizeP2PKPublicKeyForSend(xOnly),
        )
    }

    @Test
    fun p2pkSendNormalizationAcceptsCompressedHex() {
        val compressed = "03${"b".repeat(64)}"

        assertEquals(
            compressed,
            SettingsManager.normalizeP2PKPublicKeyForSend(" $compressed "),
        )
    }

    @Test
    fun p2pkSendNormalizationRejectsInvalidKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsManager.normalizeP2PKPublicKeyForSend("04${"c".repeat(64)}")
        }
    }

    @Test
    fun p2pkSendNormalizationTreatsBlankAsAbsent() {
        assertNull(SettingsManager.normalizeP2PKPublicKeyForSend(" "))
    }

    @Test
    fun p2pkComparisonNormalizationDropsCompressedPrefix() {
        val xOnly = "d".repeat(64)

        assertEquals(
            xOnly,
            SettingsManager.normalizeP2PKPublicKeyForComparison("02$xOnly"),
        )
    }

    @Test
    fun nostrRelayNormalizationAcceptsWebsocketUrls() {
        assertEquals(
            "wss://relay.example.com/path",
            SettingsManager.normalizeNostrRelayUrl(" WSS://Relay.Example.com/path "),
        )
        assertEquals(
            "wss://relay.example.com",
            SettingsManager.normalizeNostrRelayUrl("wss://relay.example.com/"),
        )
        assertEquals(
            "ws://localhost:8080",
            SettingsManager.normalizeNostrRelayUrl("ws://localhost:8080"),
        )
    }

    @Test
    fun nostrRelayNormalizationRejectsNonWebsocketUrls() {
        assertThrows(IllegalArgumentException::class.java) {
            SettingsManager.normalizeNostrRelayUrl("https://relay.example.com")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SettingsManager.normalizeNostrRelayUrl("wss://user:pass@relay.example.com")
        }
    }
}
