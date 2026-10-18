package com.cashu.me.Core

import java.util.Base64
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.cashudevkit.FfiException
import org.cashudevkit.nip44Decrypt
import org.cashudevkit.nip44Encrypt
import org.cashudevkit.nostrGetPubkey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NIP44AndNIP17Test {
    private val alice = "1".padStart(64, '0')
    private val bob = "2".padStart(64, '0')
    private val json = Json { encodeDefaults = true }

    @Test
    fun nip44EncryptDecryptRoundTripBetweenTwoKeys() {
        val senderPrivateHex = "1".padStart(64, '0')
        val recipientPrivateHex = "2".padStart(64, '0')
        val recipientPubkey = NostrService.publicKeyHex(recipientPrivateHex)

        val payload = nip44Encrypt(
            plaintext = """{"hello":"cashu"}""",
            nostrSecretKey = senderPrivateHex,
            recipientPubkey = recipientPubkey,
        )

        assertEquals(
            """{"hello":"cashu"}""",
            nip44Decrypt(
                payload = payload,
                nostrSecretKey = recipientPrivateHex,
                senderPubkey = NostrService.publicKeyHex(senderPrivateHex),
            ),
        )
    }

    @Test
    fun nip17UnwrapReturnsInnerRumor() {
        val recipientPrivateHex = "2".padStart(64, '0')
        val senderPrivateHex = "3".padStart(64, '0')
        val ephemeralPrivateHex = "4".padStart(64, '0')
        val recipientPrivate = NostrService.hexToBytes(recipientPrivateHex)
        val recipientPubkey = NostrService.publicKeyHex(recipientPrivateHex)
        val senderPubkey = NostrService.publicKeyHex(senderPrivateHex)
        val ephemeralPubkey = NostrService.publicKeyHex(ephemeralPrivateHex)
        val rumorJson = """{"id":"rumor-1","pubkey":"$senderPubkey","created_at":100,"kind":14,"tags":[],"content":"{\"id\":\"request-1\",\"mint\":\"https://mint.example.com\",\"proofs\":[]}"}"""
        val sealContent = nip44Encrypt(
            plaintext = rumorJson,
            nostrSecretKey = senderPrivateHex,
            recipientPubkey = recipientPubkey,
        )
        val seal = NostrIncomingEvent(
            id = "seal-1",
            pubkey = senderPubkey,
            createdAt = 101,
            kind = 13,
            tags = emptyList(),
            content = sealContent,
            sig = "seal-signature",
        )
        val giftWrap = NostrIncomingEvent(
            id = "wrap-1",
            pubkey = ephemeralPubkey,
            createdAt = 102,
            kind = 1059,
            tags = listOf(listOf("p", recipientPubkey)),
            content = nip44Encrypt(
                plaintext = json.encodeToString(seal),
                nostrSecretKey = ephemeralPrivateHex,
                recipientPubkey = recipientPubkey,
            ),
            sig = "wrap-signature",
        )

        val rumor = NIP17.unwrap(giftWrap, recipientPrivate)

        assertThrows(FfiException::class.java) {
            NIP17.unwrap(giftWrap, NostrService.hexToBytes("5".padStart(64, '0')))
        }
        val invalidSeal = seal.copy(content = "not-base64!!!")
        val invalidWrap = giftWrap.copy(content = nip44Encrypt(ephemeralPrivateHex, recipientPubkey, json.encodeToString(invalidSeal)))
        assertThrows(FfiException::class.java) { NIP17.unwrap(invalidWrap, recipientPrivate) }

        assertEquals("rumor-1", rumor.id)
        assertEquals(senderPubkey, rumor.pubkey)
        assertEquals(14, rumor.kind)
        assertEquals("""{"id":"request-1","mint":"https://mint.example.com","proofs":[]}""", rumor.content)
    }

    // Fixed payloads from https://github.com/paulmillr/nip44/blob/main/nip44.vectors.json
    // These tests use the shipped Android native library, not a mock JVM implementation.
    @Test
    fun officialVectors() {
        assertEquals("a", nip44Decrypt("0000000000000000000000000000000000000000000000000000000000000002", nostrGetPubkey("0000000000000000000000000000000000000000000000000000000000000001"), "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"))
        assertEquals("🍕🫃", nip44Decrypt("0000000000000000000000000000000000000000000000000000000000000001", nostrGetPubkey("0000000000000000000000000000000000000000000000000000000000000002"), "AvAAAAAAAAAAAAAAAAAAAPAAAAAAAAAAAAAAAAAAAAAPSKSK6is9ngkX2+cSq85Th16oRTISAOfhStnixqZziKMDvB0QQzgFZdjLTPicCJaV8nDITO+QfaQ61+KbWQIOO2Yj"))
        assertEquals("表ポあA鷗ŒéＢ逍Üßªąñ丂㐀𠀀", nip44Decrypt("4b22aa260e4acb7021e32f38a6cdf4b673c6a277755bfce287e370c924dc936d", nostrGetPubkey("5c0c523f52a5b6fad39ed2403092df8cebc36318b39383bca6c00808626fab3a"), "ArY1I2xC2yDwIbuNHN/1ynXdGgzHLqdCrXUPMwELJPc7s7JqlCMJBAIIjfkpHReBPXeoMCyuClwgbT419jUWU1PwaNl4FEQYKCDKVJz+97Mp3K+Q2YGa77B6gpxB/lr1QgoqpDf7wDVrDmOqGoiPjWDqy8KzLueKDcm9BVP8xeTJIxs="))
    }

    @Test
    fun roundTripsAcrossPaddingBoundaries() {
        for (text in listOf(" ", "こんにちは世界 🌍", "A".repeat(33), "B".repeat(257), "C".repeat(2_000), "D".repeat(65_408))) {
            val payload = nip44Encrypt(alice, nostrGetPubkey(bob), text)
            assertEquals(text, nip44Decrypt(bob, nostrGetPubkey(alice), payload))
        }
    }

    @Test
    fun rejectsMalformedPayloadsWrongKeysAndTampering() {
        val sender = nostrGetPubkey(alice)
        val payload = nip44Encrypt(alice, nostrGetPubkey(bob), "secret")
        for (invalid in listOf("", "not-base64!!!", "dG9vc2hvcnQ=")) {
            assertThrows(FfiException::class.java) { nip44Decrypt(bob, sender, invalid) }
        }
        assertThrows(FfiException::class.java) { nip44Decrypt("3".padStart(64, '0'), sender, payload) }
        val raw = Base64.getDecoder().decode(payload)
        // Version, nonce, ciphertext and authentication tag.
        for (offset in listOf(0, 1, 50, raw.lastIndex)) {
            val corrupt = raw.copyOf()
            corrupt[offset] = (corrupt[offset].toInt() xor 1).toByte()
            assertThrows(FfiException::class.java) { nip44Decrypt(bob, sender, Base64.getEncoder().encodeToString(corrupt)) }
        }
    }

    @Test
    fun rejectsInvalidKeysAndPlaintextLengths() {
        val pubkey = nostrGetPubkey(bob)
        for (key in listOf("", "invalid", "0".repeat(64), "f".repeat(64))) {
            assertThrows(FfiException::class.java) { nip44Encrypt(key, pubkey, "test") }
        }
        for (key in listOf("", "79be", "f".repeat(64))) {
            assertThrows(FfiException::class.java) { nip44Encrypt(alice, key, "test") }
        }
        for (text in listOf("", "A".repeat(65_409), "A".repeat(65_535), "A".repeat(65_536))) {
            assertThrows(FfiException::class.java) { nip44Encrypt(alice, pubkey, text) }
        }
    }
}
