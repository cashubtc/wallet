import Foundation
import Cdk

/// Publishes the wallet's mint list as an encrypted NUT-27 backup on Nostr and
/// finds existing backups during restore. All protocol work (key derivation,
/// encryption, relay publishing) happens inside cdk via `WalletRepository`;
/// this service only adds settings gating, relay hygiene, and UI-facing state.
@MainActor
final class NostrMintBackupService: ObservableObject {
    static let shared = NostrMintBackupService()

    @Published private(set) var isBackingUp = false
    @Published private(set) var isSearching = false
    @Published private(set) var lastBackupDate: Date?

    /// Injected by `WalletManager` alongside its own repository lifecycle.
    var walletRepository: WalletRepository?

    private init() {
        lastBackupDate = UserDefaults.standard.object(forKey: StorageKeys.nostrMintBackupLastBackupDate) as? Date
    }

    /// Fire-and-forget trigger after mint-list changes — the Nostr twin of
    /// `performICloudBackup()`. Failures only log; the mint operation that
    /// triggered the backup must not surface a relay error.
    func backupCurrentMintsIfEnabled() async {
        guard SettingsManager.shared.nostrMintBackupEnabled else { return }
        do {
            try await backupMints()
        } catch {
            AppLogger.wallet.error("Nostr mint backup failed: \(error)")
        }
    }

    func backupMints() async throws {
        guard SettingsManager.shared.useWebsockets else {
            throw NostrMintBackupError.webSocketsDisabled
        }
        guard let walletRepository else {
            throw NostrMintBackupError.notInitialized
        }
        let relays = normalizedRelays(SettingsManager.shared.nostrRelays)
        guard !relays.isEmpty else {
            throw NostrMintBackupError.noRelays
        }

        isBackingUp = true
        defer { isBackingUp = false }

        _ = try await walletRepository.backupMints(
            relays: relays,
            options: BackupOptions(client: "cashu.me")
        )

        let date = Date()
        lastBackupDate = date
        UserDefaults.standard.set(date, forKey: StorageKeys.nostrMintBackupLastBackupDate)
    }

    /// Fetch the newest mint-list backup for the currently opened wallet seed.
    /// Returns the backed-up mint URLs (empty when the relays have no backup).
    func fetchBackedUpMintURLs() async throws -> [String] {
        guard SettingsManager.shared.useWebsockets else {
            throw NostrMintBackupError.webSocketsDisabled
        }
        guard let walletRepository else {
            throw NostrMintBackupError.notInitialized
        }
        let relays = normalizedRelays(SettingsManager.shared.nostrRelays)
        guard !relays.isEmpty else {
            throw NostrMintBackupError.noRelays
        }

        isSearching = true
        defer { isSearching = false }

        let backup = try await walletRepository.fetchMintBackup(
            relays: relays,
            options: RestoreOptions(timeoutSecs: 4)
        )
        return backup.mints.map(\.url)
    }

    func resetForWalletBoundary() {
        lastBackupDate = nil
        UserDefaults.standard.removeObject(forKey: StorageKeys.nostrMintBackupLastBackupDate)
    }

    private func normalizedRelays(_ relays: [String]) -> [String] {
        var seen = Set<String>()
        return relays.compactMap { relay in
            let trimmed = relay.trimmingCharacters(in: .whitespacesAndNewlines)
            let lower = trimmed.lowercased()
            guard lower.hasPrefix("wss://") || lower.hasPrefix("ws://") else { return nil }
            guard seen.insert(trimmed).inserted else { return nil }
            return trimmed
        }
    }
}

enum NostrMintBackupError: LocalizedError {
    case notInitialized
    case noRelays
    case webSocketsDisabled

    var errorDescription: String? {
        switch self {
        case .notInitialized:
            return "Wallet is not initialized."
        case .noRelays:
            return "No Nostr relays are configured."
        case .webSocketsDisabled:
            return "Websocket connections are disabled."
        }
    }
}
