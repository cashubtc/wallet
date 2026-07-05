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
            Cdk.initLogging(level: "info")

            if let storedMnemonic = try keychainService.loadMnemonic() {
                mnemonic = storedMnemonic
                initializeWalletForLaunch(mnemonic: storedMnemonic)
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

        try proveWalletCanInitialize(mnemonic: normalizedMnemonic)
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
        try keychainService.deleteMnemonic()
        try? keychainService.deleteNostrPrivateKey()
        try removeWalletDatabaseFiles()
        walletStore.removeAllWalletData()
        SettingsManager.shared.resetWalletScopedData()
        CashuRequestStore.shared.resetForWalletBoundary()
        CashuRequestListener.shared.resetForWalletBoundary()
        MintLogoCache.shared.clear()
        processedQuotes.removeAll()
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
        let previousMnemonic = mnemonic ?? (try? keychainService.loadMnemonic())
        let defaultsSnapshot = walletBoundaryDefaultsSnapshot()
        let fileBackups = try backupWalletDatabaseFiles()

        do {
            resetRuntimeState()
            removeWalletBoundaryDefaults(defaultsSnapshot)
            walletStore.removeAllWalletData()
            SettingsStore.shared.clearWalletScopedData()
            NostrService.shared.resetForWalletBoundary(deleteStoredKey: false)
            NPCService.shared.resetForWalletBoundary()
            CashuRequestStore.shared.resetForWalletBoundary()
            CashuRequestListener.shared.resetForWalletBoundary()
            SettingsStore.shared.clearWalletScopedData()

            try await initializeWalletForCreation(mnemonic: newMnemonic)
            try keychainService.saveMnemonic(newMnemonic)
            mnemonic = newMnemonic
            SettingsManager.shared.resetWalletScopedData(resetRuntimeServices: false)
            try removeWalletFileBackups(fileBackups)
            performICloudBackup()
        } catch {
            SentryService.capture(error)
            resetRuntimeState()
            restoreWalletBoundaryDefaults(defaultsSnapshot)
            CashuRequestStore.shared.reloadFromDefaults()
            try? removeWalletDatabaseFiles()
            try? restoreWalletFileBackups(fileBackups)

            if let previousMnemonic {
                mnemonic = previousMnemonic
                initializeWalletForLaunch(mnemonic: previousMnemonic)
            }

            throw error
        }
    }

    private func initializeWalletForLaunch(mnemonic: String) {
        mintService.loadCachedMints()
        balance = mints.reduce(UInt64(0)) { $0 + $1.balance }
        transactionService.loadCachedState()

        initializeNostrKeypairLocally(mnemonic: mnemonic)
        setupNPCQuoteListener()
        startWalletRepositoryForLaunch(mnemonic: mnemonic)
    }

    private func startWalletRepositoryForLaunch(mnemonic: String) {
        walletRepositoryStartupTask?.cancel()
        walletRepositoryStartupTask = Task { [weak self] in
            guard let self else { return }
            do {
                let repository = try await self.makeWalletRepository(mnemonic: mnemonic)
                guard !Task.isCancelled, self.mnemonic == mnemonic else { return }

                self.installWalletRepository(repository)
                await self.performBestEffortWalletStartupMaintenance()
                await self.refreshBalance()
                await self.loadTransactions()
                await CashuRequestListener.shared.start()
                await self.checkAllPendingTokens()
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, self.mnemonic == mnemonic else { return }
                AppLogger.wallet.error("Wallet repository startup failed: \(String(describing: error), privacy: .public)")
                SentryService.capture(error)
                self.errorMessage = error.userFacingWalletMessage
            }
        }
    }

    /// Rebuilds the wallet repository with the currently selected network
    /// transport (Tor or clearnet). Call after `SettingsManager.torEnabled`
    /// changes. The old repository keeps serving requests until the
    /// replacement is installed; `activeTransport` is nil in between so the
    /// UI can show a "switching" state.
    func applyTransportPreference() {
        guard let mnemonic, isInitialized else { return }

        walletRepositoryStartupTask?.cancel()
        activeTransport = nil
        walletRepositoryStartupTask = Task { [weak self] in
            guard let self else { return }
            do {
                let repository = try await self.makeWalletRepository(mnemonic: mnemonic)
                guard !Task.isCancelled, self.mnemonic == mnemonic else { return }

                self.installWalletRepository(repository)
                await self.refreshBalance()
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled, self.mnemonic == mnemonic else { return }
                AppLogger.wallet.error("Transport switch failed: \(String(describing: error), privacy: .public)")
                SentryService.capture(error)
                self.errorMessage = error.userFacingWalletMessage
            }
        }
    }

    private func initializeWalletForCreation(mnemonic: String) async throws {
        try await initializeWalletRepository(mnemonic: mnemonic)

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

    private func initializeWalletRepository(mnemonic: String) async throws {
        let repository = try await makeWalletRepository(mnemonic: mnemonic)
        installWalletRepository(repository)
    }

    private func makeWalletRepository(
        mnemonic: String
    ) async throws -> (db: WalletSqliteDatabase, repository: WalletRepository, transport: NetworkTransport) {
        let databaseURL = try walletDatabaseURL()
        let transport: NetworkTransport = SettingsManager.shared.torEnabled ? .tor : .clearnet
        let result = try await Task.detached(priority: .userInitiated) {
            try Self.initializeRepositoryWithRecovery(
                mnemonic: mnemonic,
                databaseURL: databaseURL,
                transport: transport
            )
        }.value
        return (result.db, result.repository, transport)
    }

    private func installWalletRepository(
        _ repository: (db: WalletSqliteDatabase, repository: WalletRepository, transport: NetworkTransport)
    ) {
        db = repository.db
        walletRepository = repository.repository
        activeTransport = repository.transport
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
        // Clearnet: this repository is created only to validate the mnemonic
        // and is discarded immediately — no reason to spin up a Tor client.
        _ = try Self.createRepository(mnemonic: mnemonic, databaseURL: temporaryDatabaseURL, transport: .clearnet)
    }

    private func resetRuntimeState() {
        walletRepositoryStartupTask?.cancel()
        walletRepositoryStartupTask = nil

        if let npcQuoteObserver {
            NotificationCenter.default.removeObserver(npcQuoteObserver)
            self.npcQuoteObserver = nil
        }

        walletRepository = nil
        db = nil
        activeTransport = nil
        mnemonic = nil
        balance = 0
        pendingBalance = 0
        activeUnit = "sat"
        errorMessage = nil
        mintQuoteSyncsInFlight.removeAll()
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

    private func removeWalletDatabaseFiles() throws {
        let fileManager = FileManager.default

        for url in try walletDatabaseBoundaryURLs() {
            guard fileManager.fileExists(atPath: url.path) else { continue }
            try fileManager.removeItem(at: url)
        }
    }

    nonisolated private static func initializeRepositoryWithRecovery(
        mnemonic: String,
        databaseURL: URL,
        transport: NetworkTransport
    ) throws -> (db: WalletSqliteDatabase, repository: WalletRepository) {
        do {
            return try Self.createRepository(mnemonic: mnemonic, databaseURL: databaseURL, transport: transport)
        } catch {
            guard Self.shouldAttemptDatabaseRecovery(after: error, databaseURL: databaseURL) else {
                throw error
            }

            let backupURL = try Self.backupCorruptedDatabase(at: databaseURL)
            AppLogger.wallet.info("Wallet DB recovery: moved corrupted database to \(backupURL.path)")
            return try Self.createRepository(mnemonic: mnemonic, databaseURL: databaseURL, transport: transport)
        }
    }

    nonisolated private static func createRepository(
        mnemonic: String,
        databaseURL: URL,
        transport: NetworkTransport
    ) throws -> (db: WalletSqliteDatabase, repository: WalletRepository) {
        let database = try WalletSqliteDatabase(filePath: databaseURL.path)
        let store = customWalletStore(db: database)
        let repository: WalletRepository
        switch transport {
        case .tor:
            repository = try WalletRepository.newWithTor(mnemonic: mnemonic, store: store)
        case .clearnet:
            repository = try WalletRepository(mnemonic: mnemonic, store: store)
        }
        return (database, repository)
    }

    nonisolated private static func shouldAttemptDatabaseRecovery(after error: Error, databaseURL: URL) -> Bool {
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

    nonisolated private static func backupCorruptedDatabase(at databaseURL: URL) throws -> URL {
        let timestamp = Int(Date().timeIntervalSince1970)
        let backupURL = databaseURL.deletingLastPathComponent()
            .appendingPathComponent("wallet.db.corrupt.\(timestamp)")
        
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
            let keysets = try await wallet.keysets(policy: KeysetLoadPolicy.refresh)
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
