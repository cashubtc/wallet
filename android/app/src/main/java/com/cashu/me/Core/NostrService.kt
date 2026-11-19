package com.cashu.me.Core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.cashu.me.Core.Protocols.SecureStorage
import com.cashu.me.Core.Protocols.StorageKeys

enum class NostrSignerType(
    val rawValue: String,
    val displayName: String,
    val description: String,
) {
    Seed("SEED", "Wallet Seed", "Derived from your wallet's seed phrase"),
    PrivateKey("PRIVATEKEY", "Custom Key", "Use a custom Nostr private key");

    companion object {
        fun fromRaw(value: String?): NostrSignerType = entries.firstOrNull { it.rawValue == value } ?: Seed
    }
}

internal enum class NostrSignerSelectionAction {
    NoChange,
    Switch,
    ChooseCustomKey,
}

internal fun nostrSignerSelectionAction(
    current: NostrSignerType,
    requested: NostrSignerType,
    hasCustomKey: Boolean,
): NostrSignerSelectionAction = when {
    current == requested -> NostrSignerSelectionAction.NoChange
    requested == NostrSignerType.PrivateKey && !hasCustomKey ->
        NostrSignerSelectionAction.ChooseCustomKey
    else -> NostrSignerSelectionAction.Switch
}

data class NostrState(
    val publicKeyHex: String = "",
    val npub: String = "",
    val nsec: String = "",
    val isInitialized: Boolean = false,
    val signerType: NostrSignerType = NostrSignerType.Seed,
)

class NostrService(
    private val secureStorage: SecureStorage,
    private val settingsStore: NostrSignerSettings,
    private val keys: NostrKeys = CdkNostrKeys,
) {
    private val mutableState = MutableStateFlow(NostrState(signerType = NostrSignerType.fromRaw(settingsStore.nostrSignerType)))
    val state: StateFlow<NostrState> = mutableState.asStateFlow()
    private var privateKeyBytes: ByteArray? = null
    private var seedPrivateKeyBytes: ByteArray? = null
    private var currentSeed: ByteArray? = null

    /**
     * Derives the Nostr keypair from the wallet's NIP-06 secret key. The
     * caller passes the NIP-06-derived key (m/44'/1237'/0'/0/0 from the BIP39
     * seed); the first 32 bytes are used as the private key.
     */
    fun deriveKeypairFromSeed(seed: ByteArray): NostrState {
        require(seed.size >= 32) { "Nostr seed must contain at least 32 bytes." }
        currentSeed = seed.copyOf()
        val seedKey = seed.copyOfRange(0, 32)
        seedPrivateKeyBytes = seedKey.takeIf { publicKeyOrNull(it) != null }?.copyOf()
        val customKey = secureStorage.loadString(StorageKeys.secureNostrPrivateKey)
        return if (NostrSignerType.fromRaw(settingsStore.nostrSignerType) == NostrSignerType.PrivateKey && customKey != null) {
            setPrivateKey(hexToBytes(customKey), NostrSignerType.PrivateKey)
        } else {
            settingsStore.nostrSignerType = NostrSignerType.Seed.rawValue
            setPrivateKey(seedKey, NostrSignerType.Seed)
        }
    }

    fun currentPrivateKey(): String? = privateKeyBytes?.toHex() ?: secureStorage.loadString(StorageKeys.secureNostrPrivateKey)

    fun importNsec(nsec: String): NostrState {
        val key = Bech32.decode("nsec", nsec)
        require(key.size == 32) { "Invalid nsec private key length." }
        return setPrivateKey(key, NostrSignerType.PrivateKey, CustomKeyMutation.Save)
    }

    fun generateRandomKeypair(): NostrState {
        val key = hexToBytes(keys.generateSecretKeyHex())
        return setPrivateKey(key, NostrSignerType.PrivateKey, CustomKeyMutation.Save)
    }

    fun resetToSeedKey(): NostrState {
        val seed = currentSeed ?: throw IllegalStateException("No wallet seed is available for Nostr reset.")
        return setPrivateKey(seed.copyOfRange(0, 32), NostrSignerType.Seed, CustomKeyMutation.Delete)
    }

    fun switchSignerType(type: NostrSignerType): NostrState = when (type) {
        NostrSignerType.Seed -> resetToSeedKey()
        NostrSignerType.PrivateKey -> {
            val customKey = requireStoredCustomKey(
                secureStorage.loadString(StorageKeys.secureNostrPrivateKey),
            )
            setPrivateKey(hexToBytes(customKey), NostrSignerType.PrivateKey, CustomKeyMutation.Keep)
        }
    }

    fun hasCustomPrivateKey(): Boolean = secureStorage.contains(StorageKeys.secureNostrPrivateKey)

    fun hasSeedDerivedKey(): Boolean = seedPrivateKeyBytes != null

    fun seedDerivedPublicKeyHex(): String =
        seedPrivateKeyBytes?.let { publicKeyOrNull(it)?.toHex() }.orEmpty()

    /** Seed-derived private key hex — the wallet's primary P2PK signing key (iOS primaryP2PKPrivateKeyHex). */
    fun seedDerivedPrivateKeyHex(): String? = seedPrivateKeyBytes?.toHex()

    fun seedDerivedNpub(): String =
        seedPrivateKeyBytes?.let { key -> publicKeyOrNull(key)?.let { Bech32.encode("npub", it) } }.orEmpty()

    fun resetForWalletBoundary(deleteStoredKey: Boolean) {
        if (deleteStoredKey) secureStorage.delete(StorageKeys.secureNostrPrivateKey)
        settingsStore.nostrSignerType = NostrSignerType.Seed.rawValue
        privateKeyBytes = null
        seedPrivateKeyBytes = null
        currentSeed = null
        mutableState.value = NostrState(signerType = NostrSignerType.Seed)
    }

    private enum class CustomKeyMutation { Keep, Save, Delete }

    private fun setPrivateKey(
        key: ByteArray,
        signerType: NostrSignerType,
        customKeyMutation: CustomKeyMutation = CustomKeyMutation.Keep,
    ): NostrState {
        // Prepare the complete replacement before changing persistence or the
        // observable identity. A malformed key can therefore never partially
        // apply a signer change or reset.
        require(key.size == 32) { "Invalid Nostr private key." }
        val publicKey = hexToBytes(keys.publicKeyHex(key.toHex()))
        val nextState = NostrState(
            publicKeyHex = publicKey.toHex(),
            npub = Bech32.encode("npub", publicKey),
            nsec = Bech32.encode("nsec", key),
            isInitialized = true,
            signerType = signerType,
        )

        val previousCustomKey = secureStorage.loadString(StorageKeys.secureNostrPrivateKey)
        val previousSignerType = settingsStore.nostrSignerType
        try {
            when (customKeyMutation) {
                CustomKeyMutation.Keep -> Unit
                CustomKeyMutation.Save -> secureStorage.saveString(StorageKeys.secureNostrPrivateKey, key.toHex())
                CustomKeyMutation.Delete -> secureStorage.delete(StorageKeys.secureNostrPrivateKey)
            }
            settingsStore.nostrSignerType = signerType.rawValue
        } catch (error: Exception) {
            // Secure-storage implementations can throw after performing their
            // write. Restore both persisted values best-effort, while retaining
            // the original failure for the caller and UI.
            runCatching {
                if (previousCustomKey == null) {
                    secureStorage.delete(StorageKeys.secureNostrPrivateKey)
                } else {
                    secureStorage.saveString(StorageKeys.secureNostrPrivateKey, previousCustomKey)
                }
            }.exceptionOrNull()?.let(error::addSuppressed)
            runCatching { settingsStore.nostrSignerType = previousSignerType }
                .exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }

        privateKeyBytes = key.copyOf()
        mutableState.value = nextState
        return nextState
    }

    private fun publicKeyOrNull(secret: ByteArray): ByteArray? =
        try {
            hexToBytes(keys.publicKeyHex(secret.toHex()))
        } catch (_: IllegalArgumentException) {
            null
        }

    companion object {
        internal fun requireStoredCustomKey(customKey: String?): String =
            customKey ?: throw IllegalStateException(
                "Generate or import a custom key before switching key sources.",
            )

        /** Returns the x-only public key hex for [privateKeyHex], derived by CDK. */
        fun publicKeyHex(privateKeyHex: String): String = CdkNostrKeys.publicKeyHex(privateKeyHex)

        fun hexToBytes(hex: String): ByteArray {
            val clean = hex.trim().removePrefix("0x")
            require(clean.length % 2 == 0) { "Hex string must have an even length." }
            return clean.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
