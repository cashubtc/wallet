import Foundation

final class WalletStore {
    private let storage: StorageProtocol
    private let secureStorage: SecureStorageProtocol
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(
        storage: StorageProtocol = UserDefaultsStorage(),
        secureStorage: SecureStorageProtocol = KeychainService()
    ) {
        self.storage = storage
        self.secureStorage = secureStorage
    }

    var activeMintURL: String? {
        get { value(forKey: StorageKeys.activeMintUrl) }
        set { setOptional(newValue, forKey: StorageKeys.activeMintUrl) }
    }

    func loadMints() -> [MintInfo] {
        value(forKey: StorageKeys.mints, legacyKeys: [StorageKeys.Legacy.mints]) ?? []
    }

    func saveMints(_ mints: [MintInfo]) {
        set(mints, forKey: StorageKeys.mints)
    }

    // Pending/saved token entries embed full redeemable ecash token strings —
    // bearer instruments, spendable by anyone who reads them — so they live in
    // the Keychain, not UserDefaults (which any unencrypted backup or
    // filesystem read exposes). Loads transparently migrate values persisted
    // by older builds out of UserDefaults.

    func loadPendingTokens() -> [PendingToken] {
        secureValue(
            forKey: StorageKeys.pendingTokens,
            defaultsKeys: [StorageKeys.pendingTokens, StorageKeys.Legacy.pendingTokens]
        ) ?? []
    }

    func savePendingTokens(_ tokens: [PendingToken]) {
        setSecure(tokens, forKey: StorageKeys.pendingTokens)
    }

    func loadPendingReceiveTokens() -> [PendingReceiveToken] {
        secureValue(
            forKey: StorageKeys.pendingReceiveTokens,
            defaultsKeys: [StorageKeys.pendingReceiveTokens, StorageKeys.Legacy.pendingReceiveTokens]
        ) ?? []
    }

    func savePendingReceiveTokens(_ tokens: [PendingReceiveToken]) {
        setSecure(tokens, forKey: StorageKeys.pendingReceiveTokens)
    }

    func loadClaimedTokens() -> [ClaimedToken] {
        value(forKey: StorageKeys.claimedTokens, legacyKeys: [StorageKeys.Legacy.claimedTokens]) ?? []
    }

    func saveClaimedTokens(_ tokens: [ClaimedToken]) {
        set(tokens, forKey: StorageKeys.claimedTokens)
    }

    func loadSavedTokens() -> [String: String] {
        secureValue(
            forKey: StorageKeys.savedTokens,
            defaultsKeys: [StorageKeys.savedTokens, StorageKeys.Legacy.savedTokens]
        ) ?? [:]
    }

    func saveSavedTokens(_ tokens: [String: String]) {
        setSecure(tokens, forKey: StorageKeys.savedTokens)
    }

    func loadPaymentPreimages() -> [String: String] {
        value(
            forKey: StorageKeys.paymentPreimages,
            legacyKeys: [StorageKeys.Legacy.paymentPreimages]
        ) ?? [:]
    }

    func savePaymentPreimages(_ preimages: [String: String]) {
        set(preimages, forKey: StorageKeys.paymentPreimages)
    }

    func loadMeltQuoteFees() -> [String: UInt64] {
        value(forKey: StorageKeys.meltQuoteFees) ?? [:]
    }

    func saveMeltQuoteFees(_ fees: [String: UInt64]) {
        set(fees, forKey: StorageKeys.meltQuoteFees)
    }

    /// Melt quotes a mint accepted for asynchronous (NUT-05) settlement that we
    /// haven't observed in a terminal state yet. Keyed by quote ID → mint URL.
    func loadPendingMeltQuotes() -> [String: String] {
        value(forKey: StorageKeys.pendingMeltQuotes) ?? [:]
    }

    func savePendingMeltQuotes(_ quotes: [String: String]) {
        set(quotes, forKey: StorageKeys.pendingMeltQuotes)
    }

    func loadMintQuoteTimestamps() -> [String: TimeInterval] {
        value(
            forKey: StorageKeys.mintQuoteTimestamps,
            legacyKeys: [StorageKeys.Legacy.mintQuoteTimestamps]
        ) ?? [:]
    }

    func saveMintQuoteTimestamps(_ timestamps: [String: TimeInterval]) {
        set(timestamps, forKey: StorageKeys.mintQuoteTimestamps)
    }

    func loadProcessedNPCQuotes() -> [String] {
        value(forKey: StorageKeys.processedNPCQuotes) ?? []
    }

    func saveProcessedNPCQuotes(_ quoteIds: [String]) {
        set(quoteIds, forKey: StorageKeys.processedNPCQuotes)
    }

    func removeAllWalletData() {
        remove(keys: StorageKeys.walletDataKeys + StorageKeys.walletDataLegacyKeys)
        remove(keys: storage.keys(withPrefix: StorageKeys.walletDataPrefix))
        for key in StorageKeys.secureWalletDataKeys {
            do {
                try secureStorage.deleteSecret(forKey: key)
            } catch {
                AppLogger.wallet.error("Failed to remove secure \(key): \(error)")
            }
        }
    }

    // MARK: - Secure token storage (Keychain)

    /// Raw snapshot of the Keychain-held token entries, for the wallet-swap
    /// rollback path (`installCleanWallet`): the UserDefaults snapshot no
    /// longer covers these keys, so a failed swap needs this to put the old
    /// wallet's redeemable tokens back.
    func secureWalletDataSnapshot() -> [String: String] {
        var snapshot: [String: String] = [:]
        for key in StorageKeys.secureWalletDataKeys {
            if let secret = try? secureStorage.loadSecret(forKey: key) {
                snapshot[key] = secret
            }
        }
        return snapshot
    }

    func restoreSecureWalletData(_ snapshot: [String: String]) {
        for key in StorageKeys.secureWalletDataKeys {
            do {
                if let secret = snapshot[key] {
                    try secureStorage.saveSecret(secret, forKey: key)
                } else {
                    try secureStorage.deleteSecret(forKey: key)
                }
            } catch {
                AppLogger.wallet.error("Failed to restore secure \(key): \(error)")
            }
        }
    }

    /// Keychain-first read. Falls back to (and migrates away from) any copy an
    /// older build left in the plain storage backend, current or legacy key.
    private func secureValue<T: Codable>(forKey key: String, defaultsKeys: [String]) -> T? {
        if let json = try? secureStorage.loadSecret(forKey: key),
           let data = json.data(using: .utf8),
           let value = try? decoder.decode(T.self, from: data) {
            return value
        }

        for defaultsKey in defaultsKeys {
            guard let value: T = try? storage.get(forKey: defaultsKey) else { continue }
            if setSecure(value, forKey: key) {
                // Migrated — clear every plaintext copy.
                for staleKey in defaultsKeys {
                    try? storage.remove(forKey: staleKey)
                }
            }
            return value
        }

        return nil
    }

    @discardableResult
    private func setSecure<T: Codable>(_ value: T, forKey key: String) -> Bool {
        do {
            let data = try encoder.encode(value)
            guard let json = String(data: data, encoding: .utf8) else {
                AppLogger.wallet.error("Failed to encode secure \(key)")
                return false
            }
            try secureStorage.saveSecret(json, forKey: key)
            return true
        } catch {
            AppLogger.wallet.error("Failed to save secure \(key): \(error)")
            return false
        }
    }

    private func value<T: Codable>(forKey key: String, legacyKeys: [String] = []) -> T? {
        if let value: T = try? storage.get(forKey: key) {
            return value
        }

        for legacyKey in legacyKeys {
            if let value: T = try? storage.get(forKey: legacyKey) {
                set(value, forKey: key)
                return value
            }
        }

        return nil
    }

    private func set<T: Codable>(_ value: T, forKey key: String) {
        do {
            try storage.set(value, forKey: key)
        } catch {
            AppLogger.wallet.error("Failed to save \(key): \(error)")
        }
    }

    private func setOptional<T: Codable>(_ value: T?, forKey key: String) {
        do {
            if let value {
                try storage.set(value, forKey: key)
            } else {
                try storage.remove(forKey: key)
            }
        } catch {
            AppLogger.wallet.error("Failed to update \(key): \(error)")
        }
    }

    private func remove(keys: [String]) {
        for key in Set(keys) {
            do {
                try storage.remove(forKey: key)
            } catch {
                AppLogger.wallet.error("Failed to remove \(key): \(error)")
            }
        }
    }
}
