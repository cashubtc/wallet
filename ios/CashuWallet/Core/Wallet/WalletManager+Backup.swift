import Foundation
import Cdk

struct ICloudBackupInfo: Sendable {
    /// May be empty: a seed in iCloud Keychain is a valid backup on its own. The
    /// mint list is convenience (which mints to auto re-add), not a requirement.
    let mintURLs: [String]
    let timestamp: Date
}

/// Result of a `performICloudBackup()` attempt, so the UI can report the truth
/// instead of an unconditional "Backed up ✓".
enum ICloudBackupOutcome: Equatable {
    case success(mintCount: Int)
    case deferred
    case unavailable
    case noSeed
    case failed(String)
}

enum ICloudRestoreState {
    // Retain the existing key for upgrades; the barrier now covers every seed restore.
    static let incompleteKey = "cashu.local.icloudRestoreIncomplete"

    static func isIncomplete(defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: incompleteKey)
    }

    static func setIncomplete(
        _ incomplete: Bool,
        defaults: UserDefaults = .standard
    ) {
        if incomplete {
            defaults.set(true, forKey: incompleteKey)
        } else {
            defaults.removeObject(forKey: incompleteKey)
        }
    }

    static func pendingMintURLs(defaults: UserDefaults = .standard) -> [String] {
        defaults.stringArray(forKey: StorageKeys.pendingICloudRestoreMintURLs) ?? []
    }

    static func setPendingMintURLs(_ urls: [String], defaults: UserDefaults = .standard) {
        defaults.set(uniqueMintURLs(urls), forKey: StorageKeys.pendingICloudRestoreMintURLs)
    }

    static func removePendingMint(_ url: String, defaults: UserDefaults = .standard) {
        let identity = MintURLIdentity.normalized(url)
        setPendingMintURLs(pendingMintURLs(defaults: defaults).filter { $0 != identity }, defaults: defaults)
    }

    /// Completing a partial restore releases the onboarding barrier, but must
    /// never replace the backup with only the mints that happened to respond.
    static func mintURLsForBackup(current: [String], defaults: UserDefaults = .standard) -> [String] {
        uniqueMintURLs(current + pendingMintURLs(defaults: defaults))
    }

    private static func uniqueMintURLs(_ urls: [String]) -> [String] {
        var seen = Set<String>()
        return urls.map(MintURLIdentity.normalized).filter { !$0.isEmpty && seen.insert($0).inserted }
    }
}

enum ICloudRestorePolicy {
    static func shouldPerformBackup(restoreIncomplete: Bool) -> Bool {
        !restoreIncomplete
    }

    static func needsOnboarding(
        hasStoredMnemonic: Bool,
        restoreIncomplete: Bool,
        onboardingCompleted: Bool
    ) -> Bool {
        !hasStoredMnemonic || restoreIncomplete || !onboardingCompleted
    }
}

/// Tracks whether onboarding was fully passed (create path: past the first-mint
/// screen via Continue or Skip; restore path: restore finished). The seed is
/// persisted the moment a wallet is installed, so its presence alone cannot
/// distinguish "wallet ready" from "killed mid-onboarding" — this marker can.
/// Absent on both older installs and reinstalls. Only an existing local database
/// proves that an unmarked seed belongs to an older install.
enum OnboardingCompletionState {
    /// Keychain secrets can survive uninstall while the app's database and
    /// defaults do not. Never activate a seed left behind by itself, or write a
    /// marker that would activate it on the next launch. Keep it in Keychain
    /// until the user explicitly creates or restores a wallet.
    static func shouldLoadStoredWallet(
        hasStoredMnemonic: Bool,
        hasLocalDatabase: Bool,
        defaults: UserDefaults = .standard
    ) -> Bool {
        guard hasStoredMnemonic else { return false }
        if hasMarker(defaults: defaults) { return true }
        guard hasLocalDatabase else { return false }
        setCompleted(true, defaults: defaults)
        return true
    }

    static func isCompleted(defaults: UserDefaults = .standard) -> Bool {
        defaults.bool(forKey: StorageKeys.onboardingCompleted)
    }

    static func hasMarker(defaults: UserDefaults = .standard) -> Bool {
        defaults.object(forKey: StorageKeys.onboardingCompleted) != nil
    }

    static func setCompleted(_ completed: Bool, defaults: UserDefaults = .standard) {
        defaults.set(completed, forKey: StorageKeys.onboardingCompleted)
    }

    static func clear(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: StorageKeys.onboardingCompleted)
    }
}

extension WalletManager {
    // MARK: - Backup

    func getMnemonicWords() -> [String] {
        return mnemonic?.split(separator: " ").map(String.init) ?? []
    }

    func validateMnemonic(_ phrase: String) -> Bool {
        let normalizedPhrase = normalizeMnemonic(phrase)
        let words = normalizedPhrase.split(separator: " ").map(String.init)
        guard words.count == 12 || words.count == 24 else { return false }
        guard words.allSatisfy({ bip39WordList.contains($0) }) else { return false }
        return (try? Cdk.mnemonicToEntropy(mnemonic: normalizedPhrase)) != nil
    }

    /// Validate individual words and return which ones are invalid
    func invalidMnemonicWords(_ phrase: String) -> [Int] {
        let words = normalizeMnemonic(phrase).split(separator: " ").map(String.init)
        return words.enumerated().compactMap { index, word in
            bip39WordList.contains(word) ? nil : index
        }
    }

    func normalizeMnemonic(_ phrase: String) -> String {
        phrase
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .lowercased()
            .split(whereSeparator: { $0.isWhitespace })
            .joined(separator: " ")
    }

    // MARK: - iCloud Backup

    private enum ICloudKVKey {
        static let mintURLs = "cashu.icloud.mintURLs"
        static let timestamp = "cashu.icloud.backupTimestamp"
    }
    private static let iCloudEnabledKey = "cashu.local.icloudBackupEnabled"

    var iCloudBackupEnabled: Bool {
        get { UserDefaults.standard.bool(forKey: Self.iCloudEnabledKey) }
        set {
            UserDefaults.standard.set(newValue, forKey: Self.iCloudEnabledKey)
            objectWillChange.send()
            if newValue {
                performICloudBackup()
            } else {
                clearICloudBackupData()
            }
        }
    }

    var hasIncompleteICloudRestore: Bool {
        ICloudRestoreState.isIncomplete()
    }

    func setICloudRestoreIncomplete(_ incomplete: Bool) {
        ICloudRestoreState.setIncomplete(incomplete)
        objectWillChange.send()
    }

    var lastICloudBackupDate: Date? {
        let ts = NSUbiquitousKeyValueStore.default.double(forKey: ICloudKVKey.timestamp)
        return ts > 0 ? Date(timeIntervalSince1970: ts) : nil
    }

    func iCloudAvailable() -> Bool {
        FileManager.default.ubiquityIdentityToken != nil
    }

    func detectICloudBackup() -> ICloudBackupInfo? {
        guard keychainService.hasSynchronizableMnemonic() else {
            AppLogger.wallet.info("iCloud detect: no synchronizable seed → no backup")
            return nil
        }
        let store = NSUbiquitousKeyValueStore.default
        let urls = (store.array(forKey: ICloudKVKey.mintURLs) as? [String]) ?? []
        let ts = store.double(forKey: ICloudKVKey.timestamp)
        let timestamp = ts > 0 ? Date(timeIntervalSince1970: ts) : Date()
        AppLogger.wallet.info("iCloud detect: seed present, \(urls.count) mint(s)")
        return ICloudBackupInfo(mintURLs: urls, timestamp: timestamp)
    }

    /// Off-main variant of `detectICloudBackup`, for the iCloud-restore screen's
    /// entrance. The keychain query (`SecItemCopyMatching`) and the KV-store
    /// `synchronize()` flush both block the calling thread; on the main actor
    /// they stall the crossfade into the screen. `KeychainService` is stateless
    /// (only immutable constants over the thread-safe Security framework), so a
    /// fresh instance inside a detached task keeps the work off the main thread
    /// with no shared-state hazard. `ICloudBackupInfo` is `Sendable`.
    nonisolated static func detectICloudBackupOffMain() async -> ICloudBackupInfo? {
        await Task.detached(priority: .userInitiated) {
            guard KeychainService().hasSynchronizableMnemonic() else {
                AppLogger.wallet.info("iCloud detect (off-main): no synchronizable seed → no backup")
                return nil
            }
            let store = NSUbiquitousKeyValueStore.default
            store.synchronize()
            let urls = (store.array(forKey: ICloudKVKey.mintURLs) as? [String]) ?? []
            let ts = store.double(forKey: ICloudKVKey.timestamp)
            let timestamp = ts > 0 ? Date(timeIntervalSince1970: ts) : Date()
            AppLogger.wallet.info("iCloud detect (off-main): seed present, \(urls.count) mint(s)")
            return ICloudBackupInfo(mintURLs: urls, timestamp: timestamp)
        }.value
    }

    @discardableResult
    func performICloudBackup() -> ICloudBackupOutcome {
        guard ICloudRestorePolicy.shouldPerformBackup(
            restoreIncomplete: hasIncompleteICloudRestore
        ) else {
            AppLogger.wallet.info("iCloud backup deferred: wallet restore is incomplete")
            lastICloudBackupOutcome = .deferred
            return .deferred
        }
        guard iCloudBackupEnabled, iCloudAvailable() else {
            AppLogger.wallet.error("iCloud backup skipped: iCloud unavailable")
            lastICloudBackupOutcome = .unavailable
            return .unavailable
        }
        guard let currentMnemonic = mnemonic else {
            AppLogger.wallet.error("iCloud backup skipped: no seed in memory")
            lastICloudBackupOutcome = .noSeed
            return .noSeed
        }
        do {
            try keychainService.saveSynchronizableMnemonic(currentMnemonic)
            let store = NSUbiquitousKeyValueStore.default
            let backupURLs = ICloudRestoreState.mintURLsForBackup(current: mints.map(\.url))
            store.set(backupURLs, forKey: ICloudKVKey.mintURLs)
            store.set(Date().timeIntervalSince1970, forKey: ICloudKVKey.timestamp)
            store.synchronize()
            objectWillChange.send()
            AppLogger.wallet.info("iCloud backup ok: \(self.mints.count) mint(s)")
            lastICloudBackupOutcome = .success(mintCount: backupURLs.count)
            return .success(mintCount: backupURLs.count)
        } catch {
            AppLogger.wallet.error("iCloud backup failed: \(error)")
            let message = error.userFacingWalletMessage
            lastICloudBackupOutcome = .failed(message)
            return .failed(message)
        }
    }

    func clearICloudBackupData() {
        try? keychainService.deleteSynchronizableMnemonic()
        let store = NSUbiquitousKeyValueStore.default
        store.removeObject(forKey: ICloudKVKey.mintURLs)
        store.removeObject(forKey: ICloudKVKey.timestamp)
        store.synchronize()
        UserDefaults.standard.removeObject(forKey: Self.iCloudEnabledKey)
        objectWillChange.send()
    }

    func restoreFromICloudBackup(
        onPrepared: ([String]) -> Void,
        onProgress: (String, MintRestorePhase) -> Void
    ) async throws {
        guard let backup = await Self.detectICloudBackupOffMain() else {
            throw WalletError.networkError("No iCloud backup found.")
        }
        let recoveredMnemonic = try await Task.detached(priority: .userInitiated) {
            try KeychainService().loadSynchronizableMnemonic()
        }.value
        guard let recoveredMnemonic else {
            throw WalletError.networkError("iCloud Keychain item is missing.")
        }
        AppLogger.wallet.info("iCloud restore: starting, \(backup.mintURLs.count) mint(s) to restore")

        // Keep the user's backup preference intact while independently
        // suppressing writes. Persist the marker so an interruption cannot make
        // a partial wallet look complete on the next launch.
        try Task.checkCancellation()
        try await initializeRestoredWallet(mnemonic: recoveredMnemonic)
        ICloudRestoreState.setPendingMintURLs(backup.mintURLs)
        let urls = ICloudRestoreState.pendingMintURLs()
        // This preference can be restored now: the incomplete marker defers
        // backup writes until the user explicitly opens the recovered wallet.
        iCloudBackupEnabled = true
        onPrepared(urls)
        try await MintRestoreBatch.run(
            urls: urls,
            restore: { try await self.restoreFromMint(url: $0) },
            onProgress: onProgress
        )
        AppLogger.wallet.info("iCloud restore: mint recovery attempts finished")
    }
}
