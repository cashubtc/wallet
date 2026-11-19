package com.cashu.me.Core

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.Core.Protocols.SecureStorage
import com.cashu.me.Core.Protocols.StorageKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pins CDK-backed Nostr key derivation to the public keys the wallet's previous
 * Kotlin secp256k1 implementation produced, so existing identities, npubs and
 * P2PK keys are unchanged.
 */
@RunWith(AndroidJUnit4::class)
class CdkNostrKeysInstrumentedTest {
    @Test
    fun publicKeysMatchKnownVectors() {
        VECTORS.forEach { (secret, expected) ->
            assertEquals(secret, expected, CdkNostrKeys.publicKeyHex(secret))
            assertEquals(secret, expected, NostrService.publicKeyHex(secret))
        }
    }

    @Test
    fun malformedSecretKeysFailCleanly() {
        listOf(
            "0".repeat(64),
            // The secp256k1 group order n is out of range.
            "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364141",
            "f".repeat(64),
            "01".repeat(31),
            "zz".repeat(32),
            "",
        ).forEach { secret ->
            val error = assertThrows(secret, IllegalArgumentException::class.java) {
                CdkNostrKeys.publicKeyHex(secret)
            }
            assertEquals("Invalid Nostr private key.", error.message)
        }
    }

    @Test
    fun generatedSecretKeysAreDistinctHexAcceptedByCdk() {
        val first = CdkNostrKeys.generateSecretKeyHex()
        val second = CdkNostrKeys.generateSecretKeyHex()

        listOf(first, second).forEach { secret ->
            assertTrue(secret, HEX_64.matches(secret))
            assertTrue(HEX_64.matches(CdkNostrKeys.publicKeyHex(secret)))
        }
        assertNotEquals(first, second)
    }

    @Test
    fun seedIdentityKeepsItsExistingNpub() {
        val service = NostrService(MemorySecureStorage(), MemorySignerSettings())

        val state = service.deriveKeypairFromSeed(NostrService.hexToBytes(NIP06_SECRET))

        assertEquals(NIP06_PUBLIC, state.publicKeyHex)
        assertEquals(NIP06_NPUB, state.npub)
        assertEquals(NIP06_PUBLIC, service.seedDerivedPublicKeyHex())
        assertEquals(NIP06_NPUB, service.seedDerivedNpub())
    }

    @Test
    fun generatedAndImportedCustomKeysRoundTrip() {
        val storage = MemorySecureStorage()
        val service = NostrService(storage, MemorySignerSettings())
        service.deriveKeypairFromSeed(NostrService.hexToBytes(NIP06_SECRET))

        val generated = service.generateRandomKeypair()
        val storedKey = storage.loadString(StorageKeys.secureNostrPrivateKey)!!
        assertEquals(NostrSignerType.PrivateKey, generated.signerType)
        assertEquals(CdkNostrKeys.publicKeyHex(storedKey), generated.publicKeyHex)
        assertEquals(Bech32.encode("nsec", NostrService.hexToBytes(storedKey)), generated.nsec)

        val imported = service.importNsec(Bech32.encode("nsec", NostrService.hexToBytes(NIP06_SECRET)))
        assertEquals(NIP06_NPUB, imported.npub)
    }

    @Test
    fun invalidImportedKeyLeavesIdentityUnchanged() {
        val storage = MemorySecureStorage()
        val service = NostrService(storage, MemorySignerSettings())
        val initial = service.deriveKeypairFromSeed(NostrService.hexToBytes(NIP06_SECRET))

        assertThrows(IllegalArgumentException::class.java) {
            service.importNsec(Bech32.encode("nsec", ByteArray(32)))
        }

        assertEquals(initial, service.state.value)
        assertNull(storage.loadString(StorageKeys.secureNostrPrivateKey))
    }

    private class MemorySignerSettings : NostrSignerSettings {
        override var nostrSignerType: String = NostrSignerType.Seed.rawValue
    }

    private class MemorySecureStorage : SecureStorage {
        private val values = mutableMapOf<String, String>()
        override fun loadString(key: String): String? = values[key]
        override fun saveString(key: String, value: String) {
            values[key] = value
        }
        override fun delete(key: String) {
            values.remove(key)
        }
        override fun contains(key: String): Boolean = values.containsKey(key)
    }

    private companion object {
        val HEX_64 = Regex("[0-9a-f]{64}")

        // NIP-06 test vector.
        const val NIP06_SECRET = "7f7ff03d123792d6ac594bfa67bf6d0c0ab55b6b1fdb6249303fe861f1ccba9a"
        const val NIP06_PUBLIC = "17162c921dc4d2518f9a101db33695df1afb56ab82f5ff3e5da6eec3ca5cd917"
        const val NIP06_NPUB = "npub1zutzeysacnf9rru6zqwmxd54mud0k44tst6l70ja5mhv8jjumytsd2x7nu"

        // Secret key to x-only public key, as produced by the previous Kotlin implementation.
        val VECTORS = mapOf(
            "0000000000000000000000000000000000000000000000000000000000000001" to
                "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            "0000000000000000000000000000000000000000000000000000000000000003" to
                "f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9",
            NIP06_SECRET to NIP06_PUBLIC,
            "c15d739894c81a2fcfd3a2df85a0d2c0dbc47a280d092799f144d73d7ae78add" to
                "d41b22899549e1f3d335a31002cfd382174006e166d3e658e3a5eecdb6463573",
            // n - 1: odd-Y point whose x-only key equals the generator's.
            "fffffffffffffffffffffffffffffffebaaedce6af48a03bbfd25e8cd0364140" to
                "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
        )
    }
}
