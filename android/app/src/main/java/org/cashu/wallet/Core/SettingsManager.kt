package org.cashu.wallet.Core

import java.security.SecureRandom
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.cashu.wallet.Core.Protocols.SecureStorage
import org.cashu.wallet.Core.Protocols.StorageKeys
import org.cashu.wallet.Models.P2PKKeyInfo

data class SettingsState(
    val useBitcoinSymbol: Boolean = false,
    val showFiatBalance: Boolean = false,
    val bitcoinPriceCurrency: String = "USD",
    val checkPendingOnStartup: Boolean = true,
    val checkSentTokens: Boolean = true,
    val autoPasteEcashReceive: Boolean = true,
    val useWebsockets: Boolean = true,
    val enablePaymentRequests: Boolean = false,
    val receivePaymentRequestsAutomatically: Boolean = false,
    val showP2PKButtonInDrawer: Boolean = false,
    val amountDisplayPrimary: String = "fiat",
    val homeBalanceUnit: String = "sat",
    val sentryEnabled: Boolean = false,
    val appLockEnabled: Boolean = false,
    val checkIncomingInvoices: Boolean = true,
    val periodicallyCheckIncomingInvoices: Boolean = true,
    val nostrSignerType: String = "SEED",
    val nostrRelays: List<String> = emptyList(),
    val p2pkKeys: List<P2PKKeyInfo> = emptyList(),
)

internal data class LegacySettingsSecretMigration(
    val p2pkKeysToPersist: List<P2PKKeyInfo>?,
)

internal data class SettingsWalletScopedSnapshot(
    val preferences: PreferenceSnapshot,
    val p2pkKeys: List<P2PKKeyInfo>,
)

internal object LegacySettingsSecretMigrator {
    fun migrate(
        p2pkRecords: List<LegacyP2PKKeyRecord>,
        loadSecret: (String) -> String?,
        saveSecret: (String, String) -> Unit,
    ): LegacySettingsSecretMigration {
        var shouldPersistP2PK = false
        val p2pkMetadata = p2pkRecords.map { record ->
            migrateSecret(
                key = secureP2PKPrivateKey(record.metadata.id),
                legacyValue = record.privateKey,
                loadSecret = loadSecret,
                saveSecret = saveSecret,
            )
            shouldPersistP2PK = shouldPersistP2PK || record.shouldRewriteMetadata || record.hasLegacySecret
            record.metadata
        }

        return LegacySettingsSecretMigration(
            p2pkKeysToPersist = p2pkMetadata.takeIf { shouldPersistP2PK },
        )
    }

    private fun migrateSecret(
        key: String,
        legacyValue: String,
        loadSecret: (String) -> String?,
        saveSecret: (String, String) -> Unit,
    ) {
        if (legacyValue.isBlank() || loadSecret(key) != null) return
        saveSecret(key, legacyValue)
    }

    fun secureP2PKPrivateKey(id: String): String = "settings.p2pk.$id.privateKey"
}

class SettingsManager(
    private val settingsStore: SettingsStore,
    private val secureStorage: SecureStorage,
    private val nostrService: NostrService,
) {
    private val secureRandom = SecureRandom()

    companion object {
        val supportedFiatCurrencies = listOf(
            "USD", "EUR", "AUD", "BRL", "CAD", "CHF", "CNY", "CZK", "DKK", "GBP",
            "HKD", "HUF", "ILS", "INR", "JPY", "KRW", "MXN", "NZD", "NOK", "PLN",
            "RUB", "SEK", "SGD", "THB", "TRY", "ZAR",
        )

        fun normalizeP2PKPublicKeyForSend(pubkey: String?): String? {
            val trimmed = pubkey?.trim()?.lowercase().orEmpty()
            if (trimmed.isEmpty()) return null

            val isHex = trimmed.all { it in '0'..'9' || it in 'a'..'f' }
            if (trimmed.length == 64 && isHex) {
                return "02$trimmed"
            }

            require(
                trimmed.length == 66 &&
                    (trimmed.startsWith("02") || trimmed.startsWith("03")) &&
                    isHex,
            ) {
                "Invalid P2PK pubkey. Use a 66-character hex key with 02/03 prefix."
            }
            return trimmed
        }

        fun normalizeP2PKPublicKeyForComparison(pubkey: String): String {
            val trimmed = pubkey.trim().lowercase()
            return if (trimmed.length == 66 && (trimmed.startsWith("02") || trimmed.startsWith("03"))) {
                trimmed.drop(2)
            } else {
                trimmed
            }
        }
    }

    init {
        migrateLegacyStoredSecrets()
    }

    private val mutableState = MutableStateFlow(loadState())
    val state: StateFlow<SettingsState> = mutableState.asStateFlow()

    // Wired by AppContainer (same pattern as NPCService.quoteClaimHandler).
    var sentryService: SentryService? = null

    fun setUseBitcoinSymbol(value: Boolean) = update { settingsStore.useBitcoinSymbol = value }
    fun setShowFiatBalance(value: Boolean) = update {
        settingsStore.showFiatBalance = value
        settingsStore.priceEnabled = value
    }
    fun setUseWebsockets(value: Boolean) = update { settingsStore.useWebsockets = value }
    fun setCheckIncomingInvoices(value: Boolean) = update { settingsStore.checkIncomingInvoices = value }
    // TODO(runtime-parity): Keep this storage-only until Swift wires matching startup processors.
    fun setCheckPendingOnStartup(value: Boolean) = update { settingsStore.checkPendingOnStartup = value }
    fun setPeriodicallyCheckIncomingInvoices(value: Boolean) = update {
        settingsStore.periodicallyCheckIncomingInvoices = value
    }
    fun setCheckSentTokens(value: Boolean) = update { settingsStore.checkSentTokens = value }
    fun setAutoPasteEcashReceive(value: Boolean) = update { settingsStore.autoPasteEcashReceive = value }
    // TODO(runtime-parity): Payment request processing is not started from these Swift parity toggles yet.
    fun setEnablePaymentRequests(value: Boolean) = update { settingsStore.enablePaymentRequests = value }
    fun setReceivePaymentRequestsAutomatically(value: Boolean) = update {
        settingsStore.receivePaymentRequestsAutomatically = value
    }
    fun setShowP2PKButtonInDrawer(value: Boolean) = update { settingsStore.showP2PKButtonInDrawer = value }
    // Mirrors Swift SettingsManager.sentryEnabled didSet: persist, then start/stop the SDK on change.
    fun setSentryEnabled(value: Boolean) {
        val previous = settingsStore.sentryEnabled
        update { settingsStore.sentryEnabled = value }
        if (value == previous) return
        if (value) sentryService?.initialize() else sentryService?.shutdown()
    }
    fun setAppLockEnabled(value: Boolean) = update { settingsStore.appLockEnabled = value }
    fun setBitcoinPriceCurrency(value: String) = update {
        val normalized = value.uppercase()
        if (normalized in supportedFiatCurrencies) {
            settingsStore.bitcoinPriceCurrency = normalized
            settingsStore.priceCurrencyCode = normalized
        }
    }
    fun setAmountDisplayPrimary(value: String) = update {
        settingsStore.amountDisplayPrimary = AmountDisplayPrimary.fromRaw(value).rawValue
    }
    fun setHomeBalanceUnit(unit: String) = update { settingsStore.homeBalanceUnit = unit }

    fun addRelay(relay: String) = update {
        val normalized = relay.trim()
        if (normalized.isNotEmpty() && normalized !in settingsStore.nostrRelays) {
            settingsStore.nostrRelays = settingsStore.nostrRelays + normalized
        }
    }

    fun removeRelay(relay: String) = update {
        settingsStore.nostrRelays = settingsStore.nostrRelays.filterNot { it == relay }
    }

    fun resetNostrRelaysToDefault() = update {
        settingsStore.resetNostrRelaysToDefault()
    }

    fun importP2PKPublicKey(publicKey: String, label: String = "P2PK key") {
        val normalized = normalizeP2PKPublicKeyForSend(publicKey) ?: return
        require(!isKnownP2PKPublicKey(normalized)) { "Key already exists." }
        val key = P2PKKeyInfo(
            id = UUID.randomUUID().toString(),
            publicKey = normalized,
            label = label,
        )
        update { settingsStore.p2pkKeys = settingsStore.p2pkKeys + key }
    }

    fun generateP2PKKey(): Boolean =
        runCatching {
            val privateKey = generateRandomPrivateKey()
            addP2PKPrivateKey(privateKey)
        }.isSuccess

    fun importP2PKNsec(nsec: String) {
        val trimmed = nsec.trim()
        require(trimmed.startsWith("nsec1", ignoreCase = true)) { "Invalid nsec format." }
        val privateKey = Bech32.decode("nsec", trimmed)
        require(privateKey.size == 32) { "Invalid nsec format." }
        addP2PKPrivateKey(privateKey)
    }

    fun removeP2PKKey(id: String) = update {
        secureStorage.delete(secureP2PKPrivateKey(id))
        settingsStore.p2pkKeys = settingsStore.p2pkKeys.filterNot { it.id == id }
    }

    fun p2pkSigningKeysFor(pubkeys: List<String>): List<String> {
        if (pubkeys.isEmpty()) return emptyList()
        require(pubkeys.any(::isKnownP2PKPublicKey)) {
            "This token is locked to a P2PK key that is not stored on this device."
        }
        val signingKeys = allP2PKSigningKeyHexes()
        require(signingKeys.isNotEmpty()) {
            "Missing encrypted P2PK private key."
        }
        return signingKeys
    }

    fun allP2PKSigningKeyHexes(): List<String> {
        val candidates = buildList {
            primaryP2PKPrivateKeyHex()?.let(::add)
            settingsStore.p2pkKeys.forEach { key ->
                secureStorage.loadString(secureP2PKPrivateKey(key.id))?.let(::add)
            }
        }
        val seen = mutableSetOf<String>()
        return candidates.mapNotNull { hex ->
            val normalized = hex.trim().lowercase()
            normalized.takeIf { it.isNotEmpty() && seen.add(it) }
        }
    }

    fun isKnownP2PKPublicKey(pubkey: String): Boolean {
        val target = normalizeP2PKForComparison(pubkey)
        primaryP2PKPublicKey()?.let { primary ->
            if (normalizeP2PKForComparison(primary) == target) return true
        }
        return settingsStore.p2pkKeys.any { normalizeP2PKForComparison(it.publicKey) == target }
    }

    fun primaryP2PKPublicKey(): String? {
        val publicKeyHex = nostrService.state.value.publicKeyHex
        return publicKeyHex.takeIf { it.length == 64 }?.let { "02$it" }
    }

    fun primaryP2PKPrivateKeyHex(): String? =
        nostrService.currentPrivateKey()?.trim()?.lowercase()?.takeIf { it.length == 64 }

    fun primaryP2PKNsec(): String? =
        primaryP2PKPrivateKeyHex()?.let { hex ->
            runCatching { Bech32.encode("nsec", NostrService.hexToBytes(hex)) }.getOrNull()
        }

    fun primaryP2PKIsSeedBacked(): Boolean =
        nostrService.state.value.signerType == NostrSignerType.Seed

    fun p2pkPrivateKeyHex(id: String): String? =
        secureStorage.loadString(secureP2PKPrivateKey(id))?.trim()?.lowercase()

    fun p2pkNsec(id: String): String? =
        p2pkPrivateKeyHex(id)?.let { hex ->
            runCatching { Bech32.encode("nsec", NostrService.hexToBytes(hex)) }.getOrNull()
        }

    fun setP2PKKeyLabel(id: String, label: String) = update {
        val trimmed = label.trim().ifBlank { "P2PK key" }
        settingsStore.p2pkKeys = settingsStore.p2pkKeys.map {
            if (it.id == id) it.copy(label = trimmed) else it
        }
    }

    fun markP2PKKeyUsed(publicKey: String) = update {
        val comparable = normalizeP2PKForComparison(publicKey)
        settingsStore.p2pkKeys = settingsStore.p2pkKeys.map {
            if (normalizeP2PKForComparison(it.publicKey) == comparable) {
                it.copy(used = true, usedCount = it.usedCount + 1)
            } else {
                it
            }
        }
    }

    fun resetWalletScopedData() = update {
        deleteWalletScopedSecrets(snapshotWalletScopedData(), deleteNostrPrivateKey = true)
        settingsStore.clearWalletScopedData()
    }

    internal fun snapshotWalletScopedData(): SettingsWalletScopedSnapshot =
        SettingsWalletScopedSnapshot(
            preferences = settingsStore.snapshotWalletScopedData(),
            p2pkKeys = settingsStore.p2pkKeys,
        )

    internal fun prepareForWalletReplacement() = update {
        settingsStore.clearWalletScopedData()
        settingsStore.nostrSignerType = NostrSignerType.Seed.rawValue
    }

    internal fun restoreWalletScopedData(snapshot: SettingsWalletScopedSnapshot) {
        settingsStore.restoreWalletScopedData(snapshot.preferences)
        mutableState.value = loadState()
    }

    internal fun deleteWalletScopedSecrets(
        snapshot: SettingsWalletScopedSnapshot,
        deleteNostrPrivateKey: Boolean,
    ) {
        snapshot.p2pkKeys.forEach { secureStorage.delete(secureP2PKPrivateKey(it.id)) }
        if (deleteNostrPrivateKey) secureStorage.delete(StorageKeys.secureNostrPrivateKey)
    }

    private fun migrateLegacyStoredSecrets() {
        val migration = LegacySettingsSecretMigrator.migrate(
            p2pkRecords = settingsStore.loadP2PKKeysWithLegacySecrets(),
            loadSecret = secureStorage::loadString,
            saveSecret = secureStorage::saveString,
        )
        migration.p2pkKeysToPersist?.let { settingsStore.p2pkKeys = it }
    }

    private fun update(block: () -> Unit) {
        block()
        mutableState.value = loadState()
    }

    private fun loadState(): SettingsState = SettingsState(
        useBitcoinSymbol = settingsStore.useBitcoinSymbol,
        showFiatBalance = settingsStore.showFiatBalance,
        bitcoinPriceCurrency = settingsStore.bitcoinPriceCurrency,
        checkPendingOnStartup = settingsStore.checkPendingOnStartup,
        checkSentTokens = settingsStore.checkSentTokens,
        autoPasteEcashReceive = settingsStore.autoPasteEcashReceive,
        useWebsockets = settingsStore.useWebsockets,
        enablePaymentRequests = settingsStore.enablePaymentRequests,
        receivePaymentRequestsAutomatically = settingsStore.receivePaymentRequestsAutomatically,
        showP2PKButtonInDrawer = settingsStore.showP2PKButtonInDrawer,
        amountDisplayPrimary = AmountDisplayPrimary.fromRaw(settingsStore.amountDisplayPrimary).rawValue,
        homeBalanceUnit = settingsStore.homeBalanceUnit,
        sentryEnabled = settingsStore.sentryEnabled,
        appLockEnabled = settingsStore.appLockEnabled,
        checkIncomingInvoices = settingsStore.checkIncomingInvoices,
        periodicallyCheckIncomingInvoices = settingsStore.periodicallyCheckIncomingInvoices,
        nostrSignerType = settingsStore.nostrSignerType,
        nostrRelays = settingsStore.nostrRelays,
        p2pkKeys = settingsStore.p2pkKeys,
    )

    private fun normalizeP2PKForComparison(pubkey: String): String {
        return normalizeP2PKPublicKeyForComparison(pubkey)
    }

    private fun addP2PKPrivateKey(privateKey: ByteArray) {
        require(privateKey.size == 32) { "Invalid nsec format." }
        val privateKeyHex = privateKey.toHex()
        val publicKey = "02${NostrService.publicKeyHex(privateKeyHex)}"
        val comparable = normalizeP2PKForComparison(publicKey)
        require(!isKnownP2PKPublicKey(publicKey) && settingsStore.p2pkKeys.none { normalizeP2PKForComparison(it.publicKey) == comparable }) {
            "Key already exists."
        }
        val id = UUID.randomUUID().toString()
        val key = P2PKKeyInfo(
            id = id,
            publicKey = publicKey,
            label = "P2PK key",
        )
        secureStorage.saveString(secureP2PKPrivateKey(id), privateKeyHex)
        update { settingsStore.p2pkKeys = settingsStore.p2pkKeys + key }
    }

    private fun generateRandomPrivateKey(): ByteArray {
        repeat(10) {
            val key = ByteArray(32).also(secureRandom::nextBytes)
            if (runCatching { NostrService.schnorrSign(ByteArray(32), key) }.isSuccess) {
                return key
            }
        }
        error("Failed to generate secure key.")
    }

    private fun secureP2PKPrivateKey(id: String): String =
        LegacySettingsSecretMigrator.secureP2PKPrivateKey(id)

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
