import Foundation
import Cdk

extension WalletManager {
    // MARK: - Public Initialization

    /// Initialize the wallet - call this from App.task
    func initialize() async {
        guard !hasInitialized else { return }
        hasInitialized = true
        // UI-test support: wipe any persisted wallet so onboarding always shows
        // from a known-empty state. Driven by RESET_WALLET=1 in the test launch
        // environment; no effect in normal runs.
        if IntegrationTestConfig.shouldResetWallet {
            try? keychainService.deleteMnemonic()
            try? keychainService.deleteNostrPrivateKey()
            SettingsManager.shared.resetWalletScopedData()
        }

        if IntegrationTestConfig.shouldSeedWallet {
            do {
                try await installSeededUITestWallet()
                return
            } catch {
                AppLogger.wallet.error("Seeded UI-test wallet initialization error: \(error)")
            }
        }

        await loadWalletState()
    }

    private func loadWalletState() async {
        do {
            NSUbiquitousKeyValueStore.default.synchronize()
            Cdk.initLogging(level: "info")

            if let storedMnemonic = try keychainService.loadMnemonic() {
                mnemonic = storedMnemonic
                try await initializeWalletForLaunch(mnemonic: storedMnemonic)
                needsOnboarding = false
                isInitialized = true
                SentryService.breadcrumb("Wallet loaded", category: "wallet.lifecycle")
            } else {
                needsOnboarding = true
                isInitialized = true
            }
        } catch {
            AppLogger.wallet.error("Wallet initialization error: \(error)")
            SentryService.capture(error)
            isInitialized = true
            needsOnboarding = true
        }
    }

    // MARK: - Wallet Setup

    /// Create a new wallet with a fresh mnemonic
    func createNewWallet() async throws {
        isLoading = true
        defer { isLoading = false }

        let newMnemonic = try generateMnemonic()
        try await installCleanWallet(mnemonic: newMnemonic)
        SentryService.breadcrumb("Wallet created", category: "wallet.lifecycle")
    }

    /// Restore wallet from mnemonic - Phase 1: Initialize wallet state
    /// After calling this, use restoreFromMint() to recover proofs via NUT-09,
    /// then call completeRestore() to finish onboarding.
    func initializeRestoredWallet(mnemonic: String) async throws {
        isLoading = true
        defer { isLoading = false }

        let normalizedMnemonic = normalizeMnemonic(mnemonic)
        guard validateMnemonic(normalizedMnemonic) else {
            throw WalletError.invalidMnemonic
        }

        try await installCleanWallet(mnemonic: normalizedMnemonic)
        SentryService.breadcrumb("Wallet restored from seed", category: "wallet.lifecycle")
    }

    /// Restore wallet from mnemonic - Phase 2: Recover proofs from a mint via NUT-09
    /// Returns the restore result with spent/unspent/pending amounts.
    func restoreFromMint(url: String) async throws -> RestoreMintResult {
        guard let walletRepository = walletRepository else {
            throw WalletError.notInitialized
        }

        let normalizedUrl = url.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))

        let mintUrl = MintUrl(url: normalizedUrl)

        // Create wallet for this mint
        try await walletRepository.createWallet(mintUrl: mintUrl, unit: .sat, targetProofCount: nil)

        // Get the wallet instance
        let wallet = try await walletRepository.getWallet(mintUrl: mintUrl, unit: .sat)

        // Fetch mint info for display name
        let info = try? await wallet.fetchMintInfo()
        let mintName = info?.name ?? "Unknown Mint"

        // Perform NUT-09 restore - this derives proofs from the seed and checks their state with the mint
        let restored = try await wallet.restore()

        // Ensure mint is in our saved list
        await mintService.ensureMintTracked(url: normalizedUrl, name: mintName)

        // Refresh balance after restore
        await refreshBalance()

        SentryService.breadcrumb("Wallet restore from mint completed", category: "wallet.lifecycle")
        return RestoreMintResult(
            mintUrl: normalizedUrl,
            mintName: mintName,
            iconUrl: info?.iconUrl,
            spent: restored.spent.value,
            unspent: restored.unspent.value,
            pending: restored.pending.value
        )
    }

    /// Restore wallet from mnemonic - Phase 3: Complete restore and dismiss onboarding
    func completeRestore() async {
        completeOnboarding()
    }

    func completeOnboarding() {
        transactionService.loadCachedState()
        needsOnboarding = false
        CashuRequestListener.shared.attach(walletManager: self)
        Task { await CashuRequestListener.shared.start() }
    }

    /// Legacy restore for backward compatibility (initializes + completes without NUT-09)
    func restoreWallet(mnemonic: String) async throws {
        try await initializeRestoredWallet(mnemonic: mnemonic)
        await completeRestore()
    }

    func deleteWallet() async throws {
        isLoading = true
        defer { isLoading = false }

        resetRuntimeState()
        try removeAllWalletDatabaseArtifacts()
        walletStore.removeAllWalletData()
        SettingsManager.shared.resetWalletScopedData()
        CashuRequestStore.shared.resetForWalletBoundary()
        CashuRequestListener.shared.resetForWalletBoundary()
        MintLogoCache.shared.clear()
        processedQuotes.removeAll()
        try keychainService.deleteLocalWalletSecrets()
        // iCloud backup survives a local deletion — the user can restore it from
        // Restore Wallet → Restore from iCloud.
        needsOnboarding = true
        isInitialized = true
        SentryService.breadcrumb("Wallet deleted", category: "wallet.lifecycle")
    }

    private struct UserDefaultsSnapshot {
        let keys: Set<String>
        let values: [String: Any]
    }

    private struct WalletFileBackup {
        let originalURL: URL
        let backupURL: URL
    }

    private func installCleanWallet(mnemonic newMnemonic: String) async throws {
        // Build a disposable repository before touching the current wallet.
        // This validates the seed and CDK/SQLite initialization as one staged
        // operation, so a bad replacement cannot mutate live state.
        try proveWalletCanInitialize(mnemonic: newMnemonic)

        let previousMnemonic = try mnemonic ?? keychainService.loadMnemonic()
        let defaultsSnapshot = walletBoundaryDefaultsSnapshot()

        // Release SQLite/WAL handles before moving the live database into its
        // rollback snapshot. If the move itself fails, restore the small amount
        // of runtime/defaults state resetRuntimeState may have touched.
        resetRuntimeState()
        let fileBackups: [WalletFileBackup]
        do {
            fileBackups = try backupWalletDatabaseFiles()
        } catch {
            restoreWalletBoundaryDefaults(defaultsSnapshot)
            if let previousMnemonic {
                do {
                    mnemonic = previousMnemonic
                    try await initializeWalletForLaunch(mnemonic: previousMnemonic)
                } catch let rollbackError {
                    throw WalletReplacementRollbackError(
                        replacementError: error,
                        rollbackError: rollbackError
                    )
                }
            }
            throw error
        }

        do {
            removeWalletBoundaryDefaults(defaultsSnapshot)
            walletStore.removeAllWalletData()
            SettingsStore.shared.clearWalletScopedData()
            NostrService.shared.resetForWalletBoundary(deleteStoredKey: false)
            NPCService.shared.resetForWalletBoundary()
            CashuRequestStore.shared.resetForWalletBoundary()
            CashuRequestListener.shared.resetForWalletBoundary()

            try initializeWalletForCreation(mnemonic: newMnemonic)

            // Keychain is the commit point and deliberately the final throwing
            // operation. Before this succeeds every mutation above can be rolled
            // back to the prior database/defaults/runtime snapshot.
            try keychainService.saveMnemonic(newMnemonic)
            mnemonic = newMnemonic
            SettingsManager.shared.resetWalletScopedData(resetRuntimeServices: false)
            removeWalletFileBackupsAfterCommit(fileBackups)
            performICloudBackup()
        } catch {
            SentryService.capture(error)
            do {
                try await rollbackWalletReplacement(
                    previousMnemonic: previousMnemonic,
                    defaultsSnapshot: defaultsSnapshot,
                    fileBackups: fileBackups
                )
            } catch let rollbackError {
                SentryService.capture(rollbackError)
                throw WalletReplacementRollbackError(
                    replacementError: error,
                    rollbackError: rollbackError
                )
            }

            throw error
        }
    }

    private func rollbackWalletReplacement(
        previousMnemonic: String?,
        defaultsSnapshot: UserDefaultsSnapshot,
        fileBackups: [WalletFileBackup]
    ) async throws {
        resetRuntimeState()
        restoreWalletBoundaryDefaults(defaultsSnapshot)
        CashuRequestStore.shared.reloadFromDefaults()

        var firstFailure: Error?
        func record(_ error: Error) {
            if firstFailure == nil {
                firstFailure = error
            }
        }

        do {
            try removeWalletDatabaseFiles()
        } catch {
            record(error)
        }

        do {
            try restoreWalletFileBackups(fileBackups)
        } catch {
            record(error)
        }

        do {
            try restoreKeychainMnemonic(previousMnemonic)
        } catch {
            record(error)
        }

        if let firstFailure {
            throw firstFailure
        }

        guard let previousMnemonic else { return }
        NostrService.shared.reloadSignerTypeFromSettings()
        NPCService.shared.reloadWalletScopedStateFromSettings()
        mnemonic = previousMnemonic
        try await initializeWalletForLaunch(mnemonic: previousMnemonic)
        await NPCService.shared.initializeIfEnabled()
        await CashuRequestListener.shared.start()
    }

    private func initializeWalletForLaunch(mnemonic: String) async throws {
        try initializeWalletRepository(mnemonic: mnemonic)

        mintService.loadCachedMints()
        await performBestEffortWalletStartupMaintenance()
        await refreshBalance()
        transactionService.loadCachedState()

        initializeNostrKeypairLocally(mnemonic: mnemonic)
        setupNPCQuoteListener()
    }

    private func initializeWalletForCreation(mnemonic: String) throws {
        try initializeWalletRepository(mnemonic: mnemonic)

        mintService.loadCachedMints()
        balance = mints.reduce(UInt64(0)) { $0 + $1.balance }
        transactionService.loadCachedState()

        initializeNostrKeypairLocally(mnemonic: mnemonic)
        setupNPCQuoteListener()
    }

    private func installSeededUITestWallet() async throws {
        try await installCleanWallet(mnemonic: IntegrationTestConfig.seedMnemonic)
        installSeededUITestMintIfNeeded()
        completeOnboarding()
        isInitialized = true
    }

    private func installSeededUITestMintIfNeeded() {
        guard IntegrationTestConfig.shouldSeedMint,
              let rawURL = IntegrationTestConfig.seedMintURL,
              !rawURL.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            return
        }

        let normalizedURL = rawURL
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "/"))

        let mint = MintInfo(
            url: normalizedURL,
            name: "Cashu mint",
            description: "Seeded for UI tests",
            isActive: true,
            balance: 0,
            iconUrl: nil,
            units: ["sat"],
            supportedMintMethods: [.bolt11],
            supportedMeltMethods: [.bolt11],
            onchainMintConfirmations: nil,
            lastUpdated: Date()
        )

        mints = [mint]
        activeMint = mint
        mintService.saveMints()
    }

    private func initializeWalletRepository(mnemonic: String) throws {
        let databaseURL = try walletDatabaseURL()
        let repository = try initializeRepositoryWithRecovery(mnemonic: mnemonic, databaseURL: databaseURL)
        
        db = repository.db
        walletRepository = repository.repository
        processedQuotes = Set(walletStore.loadProcessedNPCQuotes())
    }

    private func proveWalletCanInitialize(mnemonic: String) throws {
        _ = try Cdk.mnemonicToEntropy(mnemonic: mnemonic)

        let fileManager = FileManager.default
        let temporaryDirectory = try temporaryWalletDirectoryURL()
        try fileManager.createDirectory(at: temporaryDirectory, withIntermediateDirectories: true)
        defer {
            try? fileManager.removeItem(at: temporaryDirectory)
        }

        let temporaryDatabaseURL = temporaryDirectory.appendingPathComponent(walletDatabaseFilename)
        _ = try createRepository(mnemonic: mnemonic, databaseURL: temporaryDatabaseURL)
    }

    private func resetRuntimeState() {
        if let npcQuoteObserver {
            NotificationCenter.default.removeObserver(npcQuoteObserver)
            self.npcQuoteObserver = nil
        }

        walletRepository = nil
        db = nil
        mnemonic = nil
        balance = 0
        balancesByUnit = [:]
        pendingBalance = 0
        activeUnit = "sat"
        errorMessage = nil
        pendingMeltWaiters.values.forEach { $0.cancel() }
        pendingMeltWaiters.removeAll()
        mintQuoteSyncsInFlight.removeAll()
        isSyncingMintQuotes = false
        lastMintQuoteSyncAt = nil
        npcQuotesInFlight.removeAll()
        processedQuotes.removeAll()
        mintService.clearState()
        transactionService.clearState()
        tokenService.clearState()
        lightningService.clearState()
    }

    private func generateMnemonic() throws -> String {
        try Cdk.generateMnemonic()
    }

    private func walletDatabaseURL() throws -> URL {
        let walletDirectoryURL = try walletDirectoryURL(create: true)
        let currentDatabaseURL = walletDirectoryURL.appendingPathComponent(walletDatabaseFilename)
        try migrateLegacyWalletDatabaseIfNeeded(to: currentDatabaseURL)
        return currentDatabaseURL
    }

    private func applicationSupportURL(create: Bool = true) throws -> URL {
        try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: create
        )
    }

    private func walletDirectoryURL(create: Bool) throws -> URL {
        let applicationSupportURL = try FileManager.default.url(
            for: .applicationSupportDirectory,
            in: .userDomainMask,
            appropriateFor: nil,
            create: create
        )
        
        let walletDirectoryURL = applicationSupportURL.appendingPathComponent(walletDatabaseDirectoryName, isDirectory: true)
        if create && !FileManager.default.fileExists(atPath: walletDirectoryURL.path) {
            try FileManager.default.createDirectory(at: walletDirectoryURL, withIntermediateDirectories: true)
        }

        return walletDirectoryURL
    }

    private func temporaryWalletDirectoryURL() throws -> URL {
        let applicationSupportURL = try applicationSupportURL()
        return applicationSupportURL.appendingPathComponent(
            "\(walletDatabaseDirectoryName).restore.\(UUID().uuidString)",
            isDirectory: true
        )
    }

    private func legacyWalletDatabaseURL() -> URL {
        let documentsPath = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        return documentsPath.appendingPathComponent("cashu_wallet.db")
    }

    private func migrateLegacyWalletDatabaseIfNeeded(to currentDatabaseURL: URL) throws {
        let legacyDatabaseURL = legacyWalletDatabaseURL()
        
        guard FileManager.default.fileExists(atPath: legacyDatabaseURL.path) else { return }
        guard !FileManager.default.fileExists(atPath: currentDatabaseURL.path) else { return }
        
        try FileManager.default.moveItem(at: legacyDatabaseURL, to: currentDatabaseURL)
        
        for suffix in ["-wal", "-shm", "-journal"] {
            let legacySidecarURL = URL(fileURLWithPath: legacyDatabaseURL.path + suffix)
            guard FileManager.default.fileExists(atPath: legacySidecarURL.path) else { continue }
            
            let currentSidecarURL = URL(fileURLWithPath: currentDatabaseURL.path + suffix)
            if FileManager.default.fileExists(atPath: currentSidecarURL.path) {
                try FileManager.default.removeItem(at: currentSidecarURL)
            }
            try FileManager.default.moveItem(at: legacySidecarURL, to: currentSidecarURL)
        }
    }

    private func walletBoundaryDefaultsSnapshot() -> UserDefaultsSnapshot {
        let defaults = UserDefaults.standard
        let prefixKeys = defaults.dictionaryRepresentation().keys.filter {
            $0.hasPrefix(StorageKeys.walletDataPrefix) || $0.hasPrefix(StorageKeys.npcDataPrefix)
        }
        let keys = Set(StorageKeys.walletBoundaryKeys + prefixKeys)
        var values: [String: Any] = [:]

        for key in keys {
            if let value = defaults.object(forKey: key) {
                values[key] = value
            }
        }

        return UserDefaultsSnapshot(keys: keys, values: values)
    }

    private func removeWalletBoundaryDefaults(_ snapshot: UserDefaultsSnapshot) {
        for key in snapshot.keys {
            UserDefaults.standard.removeObject(forKey: key)
        }
    }

    private func restoreWalletBoundaryDefaults(_ snapshot: UserDefaultsSnapshot) {
        for key in snapshot.keys {
            if let value = snapshot.values[key] {
                UserDefaults.standard.set(value, forKey: key)
            } else {
                UserDefaults.standard.removeObject(forKey: key)
            }
        }
    }

    private func walletDatabaseBoundaryURLs() throws -> [URL] {
        let walletDirectoryURL = try walletDirectoryURL(create: false)
        let legacyDatabaseURL = legacyWalletDatabaseURL()
        let legacySidecars = ["-wal", "-shm", "-journal"].map {
            URL(fileURLWithPath: legacyDatabaseURL.path + $0)
        }

        return [walletDirectoryURL, legacyDatabaseURL] + legacySidecars
    }

    private func backupWalletDatabaseFiles() throws -> [WalletFileBackup] {
        let fileManager = FileManager.default
        let timestamp = Int(Date().timeIntervalSince1970)
        var backups: [WalletFileBackup] = []

        do {
            for originalURL in try walletDatabaseBoundaryURLs() {
                guard fileManager.fileExists(atPath: originalURL.path) else { continue }

                let backupURL = originalURL.deletingLastPathComponent()
                    .appendingPathComponent("\(originalURL.lastPathComponent).replacing.\(timestamp).\(UUID().uuidString)")

                if fileManager.fileExists(atPath: backupURL.path) {
                    try fileManager.removeItem(at: backupURL)
                }

                try fileManager.moveItem(at: originalURL, to: backupURL)
                backups.append(WalletFileBackup(originalURL: originalURL, backupURL: backupURL))
            }
        } catch {
            try? restoreWalletFileBackups(backups)
            throw error
        }

        return backups
    }

    private func restoreWalletFileBackups(_ backups: [WalletFileBackup]) throws {
        let fileManager = FileManager.default

        for backup in backups.reversed() {
            if fileManager.fileExists(atPath: backup.originalURL.path) {
                try fileManager.removeItem(at: backup.originalURL)
            }

            guard fileManager.fileExists(atPath: backup.backupURL.path) else { continue }
            try fileManager.moveItem(at: backup.backupURL, to: backup.originalURL)
        }
    }

    private func removeWalletFileBackups(_ backups: [WalletFileBackup]) throws {
        let fileManager = FileManager.default

        for backup in backups {
            guard fileManager.fileExists(atPath: backup.backupURL.path) else { continue }
            try fileManager.removeItem(at: backup.backupURL)
        }
    }

    private func removeWalletFileBackupsAfterCommit(_ backups: [WalletFileBackup]) {
        do {
            try removeWalletFileBackups(backups)
        } catch {
            // The new mnemonic and repository are already committed. Never roll
            // them back because cleanup of an inaccessible backup failed.
            AppLogger.wallet.error("Failed to remove replaced wallet backup: \(error)")
            SentryService.capture(error)
        }
    }

    private func restoreKeychainMnemonic(_ previousMnemonic: String?) throws {
        if let previousMnemonic {
            try keychainService.saveMnemonic(previousMnemonic)
        } else {
            try keychainService.deleteMnemonic()
        }
    }

    private func removeWalletDatabaseFiles() throws {
        let fileManager = FileManager.default

        for url in try walletDatabaseBoundaryURLs() {
            guard fileManager.fileExists(atPath: url.path) else { continue }
            try fileManager.removeItem(at: url)
        }
    }

    private func removeAllWalletDatabaseArtifacts() throws {
        let fileManager = FileManager.default
        var urls = try walletDatabaseBoundaryURLs()

        let applicationSupportURL = try applicationSupportURL(create: false)
        if let contents = try? fileManager.contentsOfDirectory(
            at: applicationSupportURL,
            includingPropertiesForKeys: nil
        ) {
            urls += contents.filter {
                $0.lastPathComponent.hasPrefix("\(walletDatabaseDirectoryName).replacing.")
                    || $0.lastPathComponent.hasPrefix("\(walletDatabaseDirectoryName).restore.")
            }
        }

        let documentsURL = legacyWalletDatabaseURL().deletingLastPathComponent()
        if let contents = try? fileManager.contentsOfDirectory(
            at: documentsURL,
            includingPropertiesForKeys: nil
        ) {
            urls += contents.filter {
                $0.lastPathComponent.hasPrefix("cashu_wallet.db")
                    && $0.lastPathComponent.contains(".replacing.")
            }
        }

        for url in Set(urls) {
            guard fileManager.fileExists(atPath: url.path) else { continue }
            try fileManager.removeItem(at: url)
        }
    }

    private func initializeRepositoryWithRecovery(
        mnemonic: String,
        databaseURL: URL
    ) throws -> (db: WalletSqliteDatabase, repository: WalletRepository) {
        do {
            return try createRepository(mnemonic: mnemonic, databaseURL: databaseURL)
        } catch {
            guard shouldAttemptDatabaseRecovery(after: error, databaseURL: databaseURL) else {
                throw error
            }
            
            let backupURL = try backupCorruptedDatabase(at: databaseURL)
            AppLogger.wallet.info("Wallet DB recovery: moved corrupted database to \(backupURL.path)")
            return try createRepository(mnemonic: mnemonic, databaseURL: databaseURL)
        }
    }

    private func createRepository(
        mnemonic: String,
        databaseURL: URL
    ) throws -> (db: WalletSqliteDatabase, repository: WalletRepository) {
        let database = try WalletSqliteDatabase(filePath: databaseURL.path)
        let repository = try WalletRepository(
            mnemonic: mnemonic,
            store: customWalletStore(db: database)
        )
        return (database, repository)
    }

    private func shouldAttemptDatabaseRecovery(after error: Error, databaseURL: URL) -> Bool {
        guard FileManager.default.fileExists(atPath: databaseURL.path) else {
            return false
        }
        
        let errorDescription = String(describing: error).lowercased()
        return errorDescription.contains("sqlite")
            || errorDescription.contains("database")
            || errorDescription.contains("corrupt")
            || errorDescription.contains("malformed")
            || errorDescription.contains("walletdb")
    }

    private func backupCorruptedDatabase(at databaseURL: URL) throws -> URL {
        let timestamp = Int(Date().timeIntervalSince1970)
        let backupURL = databaseURL.deletingLastPathComponent()
            .appendingPathComponent("\(walletDatabaseFilename).corrupt.\(timestamp)")
        
        if FileManager.default.fileExists(atPath: backupURL.path) {
            try FileManager.default.removeItem(at: backupURL)
        }
        
        try FileManager.default.moveItem(at: databaseURL, to: backupURL)
        
        for suffix in ["-wal", "-shm", "-journal"] {
            let sidecarURL = URL(fileURLWithPath: databaseURL.path + suffix)
            guard FileManager.default.fileExists(atPath: sidecarURL.path) else { continue }
            
            let sidecarBackupURL = URL(fileURLWithPath: backupURL.path + suffix)
            if FileManager.default.fileExists(atPath: sidecarBackupURL.path) {
                try FileManager.default.removeItem(at: sidecarBackupURL)
            }
            try FileManager.default.moveItem(at: sidecarURL, to: sidecarBackupURL)
        }
        
        return backupURL
    }

    func trackedMintUrlsForWalletAccess() -> [String] {
        var urls = mints.map(\.url).filter { !$0.isEmpty }
        
        if let activeUrl = activeMint?.url, !activeUrl.isEmpty, !urls.contains(activeUrl) {
            urls.append(activeUrl)
        }
        
        return Array(Set(urls))
    }

    private func performBestEffortWalletStartupMaintenance() async {
        guard let walletRepository else { return }
        let mintUrls = trackedMintUrlsForWalletAccess()
        guard !mintUrls.isEmpty else { return }

        for mintUrlString in mintUrls {
            do {
                let wallet = try await walletRepository.getWallet(
                    mintUrl: MintUrl(url: mintUrlString),
                    unit: .sat
                )
                await recoverIncompleteSagasIfNeeded(wallet: wallet, mintUrl: mintUrlString)
                await refreshKeysetsIfNeeded(wallet: wallet, mintUrl: mintUrlString)
            } catch {
                AppLogger.wallet.error(
                    "Wallet startup maintenance failed for mint \(mintUrlString, privacy: .public): \(String(describing: error), privacy: .public)"
                )
            }
        }

        // Saga recovery above only single-polls async-accepted (NUT-05) melts and
        // skips them while still pending; re-arm their completion tracking here.
        await syncPendingMeltQuotes()
    }

    private func recoverIncompleteSagasIfNeeded(wallet: Wallet, mintUrl: String) async {
        do {
            let report = try await wallet.recoverIncompleteSagas()
            if report.recovered > 0 || report.compensated > 0 || report.skipped > 0 || report.failed > 0 {
                AppLogger.wallet.info(
                    "Recovered wallet sagas for mint \(mintUrl, privacy: .public): recovered=\(report.recovered, privacy: .public) compensated=\(report.compensated, privacy: .public) skipped=\(report.skipped, privacy: .public) failed=\(report.failed, privacy: .public)"
                )
            }
        } catch {
            AppLogger.wallet.error(
                "Failed to recover wallet sagas for mint \(mintUrl, privacy: .public): \(String(describing: error), privacy: .public)"
            )
        }
    }

    private func refreshKeysetsIfNeeded(wallet: Wallet, mintUrl: String) async {
        do {
            let keysets = try await wallet.refreshKeysets()
            AppLogger.wallet.info(
                "Refreshed \(keysets.count, privacy: .public) keysets for mint \(mintUrl, privacy: .public)"
            )
        } catch {
            AppLogger.wallet.error(
                "Failed to refresh keysets for mint \(mintUrl, privacy: .public): \(String(describing: error), privacy: .public)"
            )
        }
    }

    func ensureMintTrackedForToken(_ tokenString: String) async throws {
        let token = try tokenService.decodeToken(tokenString: tokenString)
        let tokenMintUrl = try token.mintUrl().url
        await mintService.ensureMintTracked(url: tokenMintUrl)
    }
}

private struct WalletReplacementRollbackError: LocalizedError {
    let replacementError: Error
    let rollbackError: Error

    var errorDescription: String? {
        "Wallet replacement failed and the previous wallet could not be fully restored. "
            + "Replacement error: \(replacementError.localizedDescription). "
            + "Rollback error: \(rollbackError.localizedDescription)."
    }
}
