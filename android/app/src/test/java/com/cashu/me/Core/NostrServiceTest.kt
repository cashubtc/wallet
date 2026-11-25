package com.cashu.me.Core

import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Assert.fail
import org.junit.Test
import com.cashu.me.Core.Protocols.SecureStorage
import com.cashu.me.Core.Protocols.StorageKeys

class NostrServiceTest {
    @Test
    fun selectingMissingCustomKeyRequestsAChoiceWithoutSwitching() {
        assertEquals(
            NostrSignerSelectionAction.ChooseCustomKey,
            nostrSignerSelectionAction(
                current = NostrSignerType.Seed,
                requested = NostrSignerType.PrivateKey,
                hasCustomKey = false,
            ),
        )
    }

    @Test
    fun selectingStoredCustomKeySwitchesSigner() {
        assertEquals(
            NostrSignerSelectionAction.Switch,
            nostrSignerSelectionAction(
                current = NostrSignerType.Seed,
                requested = NostrSignerType.PrivateKey,
                hasCustomKey = true,
            ),
        )
    }

    @Test
    fun switchingToMissingCustomKeyRequiresExplicitSetup() {
        val error = assertThrows(IllegalStateException::class.java) {
            NostrService.requireStoredCustomKey(null)
        }

        assertEquals(
            "Generate or import a custom key before switching key sources.",
            error.message,
        )
    }

    @Test
    fun switchingToExistingCustomKeyUsesStoredIdentity() {
        val storedKey = "01".repeat(32)

        assertEquals(storedKey, NostrService.requireStoredCustomKey(storedKey))
    }

    @Test
    fun failedGenerationRestoresStoredKeySignerAndObservableIdentity() {
        val storage = FailingSecureStorage()
        val settings = FakeNostrSignerSettings()
        val service = NostrService(storage, settings, FakeNostrKeys())
        val initial = service.deriveKeypairFromSeed(privateKey(1))
        storage.failAfterNextSave = true

        assertFails { service.generateRandomKeypair() }

        assertEquals(initial, service.state.value)
        assertEquals(initial.nsec, Bech32.encode("nsec", NostrService.hexToBytes(service.currentPrivateKey()!!)))
        assertEquals(NostrSignerType.Seed.rawValue, settings.nostrSignerType)
        assertNull(storage.loadString(StorageKeys.secureNostrPrivateKey))
    }

    @Test
    fun failedResetRestoresCustomKeySignerAndObservableIdentity() {
        val storage = FailingSecureStorage()
        val settings = FakeNostrSignerSettings()
        val service = NostrService(storage, settings, FakeNostrKeys())
        service.deriveKeypairFromSeed(privateKey(1))
        val customNsec = Bech32.encode("nsec", privateKey(2))
        val initial = service.importNsec(customNsec)
        val storedCustomKey = storage.loadString(StorageKeys.secureNostrPrivateKey)
        storage.failAfterNextDelete = true

        assertFails { service.resetToSeedKey() }

        assertEquals(initial, service.state.value)
        assertEquals(NostrSignerType.PrivateKey.rawValue, settings.nostrSignerType)
        assertEquals(storedCustomKey, storage.loadString(StorageKeys.secureNostrPrivateKey))
    }

    @Test
    fun failedSignerChangeRestoresSignerAndObservableIdentity() {
        val storage = FailingSecureStorage(
            mutableMapOf(StorageKeys.secureNostrPrivateKey to privateKey(2).toHexString()),
        )
        val settings = FakeNostrSignerSettings()
        val service = NostrService(storage, settings, FakeNostrKeys())
        val initial = service.deriveKeypairFromSeed(privateKey(1))
        settings.failAfterNextSet = true

        assertFails { service.switchSignerType(NostrSignerType.PrivateKey) }

        assertEquals(initial, service.state.value)
        assertEquals(NostrSignerType.Seed.rawValue, settings.nostrSignerType)
        assertEquals(privateKey(2).toHexString(), storage.loadString(StorageKeys.secureNostrPrivateKey))
    }

    @Test
    fun bech32RoundTripsNsecPayload() {
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val encoded = Bech32.encode("nsec", key)

        assertTrue(encoded.startsWith("nsec1"))
        assertArrayEquals(key, Bech32.decode("nsec", encoded))
    }

    @Test
    fun identityUsesPublicKeyFromKeyProvider() {
        val keys = FakeNostrKeys()
        val service = NostrService(FailingSecureStorage(), FakeNostrSignerSettings(), keys)

        val state = service.deriveKeypairFromSeed(privateKey(1))

        val expectedPublicKey = keys.publicKeyHex(privateKey(1).toHexString())
        assertEquals(expectedPublicKey, state.publicKeyHex)
        assertEquals(Bech32.encode("npub", NostrService.hexToBytes(expectedPublicKey)), state.npub)
        assertEquals(expectedPublicKey, service.seedDerivedPublicKeyHex())
        assertEquals(state.npub, service.seedDerivedNpub())
    }

    @Test
    fun generatedKeyComesFromKeyProviderAndIsStored() {
        val storage = FailingSecureStorage()
        val settings = FakeNostrSignerSettings()
        val keys = FakeNostrKeys(generated = privateKey(7).toHexString())
        val service = NostrService(storage, settings, keys)
        service.deriveKeypairFromSeed(privateKey(1))

        val state = service.generateRandomKeypair()

        assertEquals(privateKey(7).toHexString(), storage.loadString(StorageKeys.secureNostrPrivateKey))
        assertEquals(Bech32.encode("nsec", privateKey(7)), state.nsec)
        assertEquals(keys.publicKeyHex(privateKey(7).toHexString()), state.publicKeyHex)
        assertEquals(NostrSignerType.PrivateKey.rawValue, settings.nostrSignerType)
    }

    @Test
    fun keyRejectedByKeyProviderLeavesIdentityUnchanged() {
        val storage = FailingSecureStorage()
        val settings = FakeNostrSignerSettings()
        val service = NostrService(storage, settings, FakeNostrKeys())
        val initial = service.deriveKeypairFromSeed(privateKey(1))

        val error = assertThrows(IllegalArgumentException::class.java) {
            service.importNsec(Bech32.encode("nsec", ByteArray(32)))
        }

        assertEquals("Invalid Nostr private key.", error.message)
        assertEquals(initial, service.state.value)
        assertEquals(NostrSignerType.Seed.rawValue, settings.nostrSignerType)
        assertNull(storage.loadString(StorageKeys.secureNostrPrivateKey))
    }

    @Test
    fun seedKeyRejectedByKeyProviderHasNoSeedIdentity() {
        val service = NostrService(FailingSecureStorage(), FakeNostrSignerSettings(), FakeNostrKeys())

        assertThrows(IllegalArgumentException::class.java) {
            service.deriveKeypairFromSeed(ByteArray(32))
        }

        assertFalse(service.hasSeedDerivedKey())
        assertEquals("", service.seedDerivedNpub())
        assertFalse(service.state.value.isInitialized)
    }

    private fun privateKey(value: Int): ByteArray = ByteArray(32).also { it[31] = value.toByte() }

    private fun ByteArray.toHexString(): String = joinToString("") { "%02x".format(it) }

    private fun assertFails(block: () -> Unit) {
        try {
            block()
            fail("Expected operation to fail")
        } catch (_: IllegalStateException) {
            // Expected injected persistence failure.
        }
    }

    /**
     * Deterministic stand-in for CDK, which cannot load in JVM unit tests.
     * Real secp256k1 vectors are covered by CdkNostrKeysInstrumentedTest.
     */
    private class FakeNostrKeys(
        private val generated: String = "07".padStart(64, '0'),
    ) : NostrKeys {
        override fun generateSecretKeyHex(): String = generated

        override fun publicKeyHex(secretKeyHex: String): String {
            require(secretKeyHex.length == 64 && secretKeyHex.any { it != '0' }) {
                "Invalid Nostr private key."
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(NostrService.hexToBytes(secretKeyHex))
                .joinToString("") { "%02x".format(it) }
        }
    }

    private class FakeNostrSignerSettings : NostrSignerSettings {
        private var value = NostrSignerType.Seed.rawValue
        var failAfterNextSet = false

        override var nostrSignerType: String
            get() = value
            set(newValue) {
                value = newValue
                if (failAfterNextSet) {
                    failAfterNextSet = false
                    throw IllegalStateException("Signer settings unavailable")
                }
            }
    }

    private class FailingSecureStorage(
        private val values: MutableMap<String, String> = mutableMapOf(),
    ) : SecureStorage {
        var failAfterNextSave = false
        var failAfterNextDelete = false

        override fun loadString(key: String): String? = values[key]

        override fun saveString(key: String, value: String) {
            values[key] = value
            if (failAfterNextSave) {
                failAfterNextSave = false
                throw IllegalStateException("Secure storage write failed")
            }
        }

        override fun delete(key: String) {
            values.remove(key)
            if (failAfterNextDelete) {
                failAfterNextDelete = false
                throw IllegalStateException("Secure storage delete failed")
            }
        }

        override fun contains(key: String): Boolean = values.containsKey(key)
    }
}
