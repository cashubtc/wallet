import Cdk
import XCTest
import SQLite3
@testable import CashuWallet

@MainActor
final class WalletAuditRegressionTests: XCTestCase {
    func testRestoreIgnoresOtherMintsButPreservesSameMintReservations() {
        let mint = "https://mint.example"
        let otherSend = #"{"kind":"send","mint_url":"https://other.example"}"#
        let issue = #"{"kind":"mint","mint_url":"https://mint.example"}"#
        XCTAssertTrue(MintRestoreSafety.allows(sagas: [otherSend, issue], mintURL: mint, hasProtectedProofs: false))
        for kind in ["send", "melt", "swap", "receive", "unknown"] {
            let saga = "{\"kind\":\"\(kind)\",\"mint_url\":\"https://MINT.example/\"}"
            XCTAssertFalse(MintRestoreSafety.allows(sagas: [saga], mintURL: mint, hasProtectedProofs: false))
        }
        XCTAssertFalse(MintRestoreSafety.allows(sagas: [issue], mintURL: mint, hasProtectedProofs: true))
        XCTAssertFalse(MintRestoreSafety.allows(sagas: ["invalid"], mintURL: mint, hasProtectedProofs: false))
    }

    private enum Failure: Error { case offline }

    func testSharedBech32FramingAcceptsBothChecksumsAndRejectsCorruption() throws {
        let classic = try Bech32.decodeWords("A12UEL5L")
        XCTAssertEqual(classic.hrp, "a")
        XCTAssertEqual(classic.checksum, 1)
        XCTAssertTrue(classic.words.isEmpty)
        let modern = try Bech32.decodeWords("A1LQFN3A")
        XCTAssertEqual(modern.checksum, 0x2bc830a3)
        for invalid in ["a12UEL5L", "A12UEL5Q", "1UEL5L", " a12uel5l", "a1!uel5l"] {
            XCTAssertThrowsError(try Bech32.decodeWords(invalid), invalid)
        }
        let bytes = Data((0..<32).map(UInt8.init))
        let npub = try Bech32.encode(hrp: "npub", data: bytes)
        XCTAssertEqual(try Bech32.decode(hrp: "npub", bech32: npub), Array(bytes))
        XCTAssertEqual(try Bech32.decode(hrp: "npub", bech32: npub.uppercased()), Array(bytes))
        XCTAssertThrowsError(try Bech32.decode(hrp: "nsec", bech32: npub))
        XCTAssertThrowsError(try Bech32.decode(hrp: "a", bech32: "A1LQFN3A"))
    }


    func testSharedHexCodecRoundTripsAllBytesAndRejectsMalformedInput() {
        let bytes = Data((0...255).map(UInt8.init))
        XCTAssertEqual(Data(hexString: bytes.hexEncodedString), bytes)
        XCTAssertEqual(Data(hexString: bytes.hexEncodedString.uppercased()), bytes)
        XCTAssertEqual(Data(hexString: ""), Data())
        for malformed in ["0", "001", "gg", " 00", "00\n", "é0", "０0"] {
            XCTAssertNil(Data(hexString: malformed), malformed)
        }
    }

    func testP2PKComparisonSharesCompressedAndBareKeyIdentity() {
        let xOnly = String(repeating: "ab", count: 32)
        XCTAssertEqual(P2PKPublicKey.normalizedForComparison(" 02" + xOnly.uppercased() + "\n"), xOnly)
        XCTAssertEqual(P2PKPublicKey.normalizedForComparison("03" + xOnly), xOnly)
        XCTAssertEqual(P2PKPublicKey.normalizedForComparison(xOnly), xOnly)
        // Only full compressed keys lose their prefix.
        XCTAssertEqual(P2PKPublicKey.normalizedForComparison("02ab"), "02ab")
    }


    /// Run in a fresh test simulator: never replace an existing wallet fixture.
    func testSameSeedRestoreKeepsNativeQuoteKeyCounterAndRepository() async throws {
        let manager = WalletManager()
        guard try manager.keychainService.loadMnemonic() == nil else {
            throw XCTSkip("This integration regression requires an empty test wallet container.")
        }
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        try await manager.initializeRestoredWallet(mnemonic: phrase)
        let database = try XCTUnwrap(manager.db)
        let signingKey = SecretKey(hex: String(repeating: "0", count: 63) + "1")
        let quote = MintQuote(
            id: "same-seed-quote", amount: nil, unit: .sat, request: "test-offer",
            state: .paid, expiry: 0, mintUrl: MintUrl(url: "https://mint.example"),
            amountIssued: Amount(value: 0), amountPaid: Amount(value: 12), updatedAt: 0,
            estimatedBlocks: nil, paymentMethod: .bolt12, secretKey: signingKey.hex,
            usedByOperation: nil, version: 0
        )
        try await database.addMintQuote(quote: quote)
        let beforeCounter = try await database.incrementDerivationCounter(namespace: "nut20_quote", count: 17)
        try await manager.initializeRestoredWallet(mnemonic: phrase.uppercased())
        XCTAssertTrue(manager.db === database)
        let retained = try await database.getMintQuote(quoteId: quote.id)
        XCTAssertEqual(retained?.secretKey, signingKey.hex)
        XCTAssertEqual(retained?.amountPaid.value, 12)
        let afterCounter = try await database.incrementDerivationCounter(namespace: "nut20_quote", count: 0)
        XCTAssertEqual(afterCounter, beforeCounter)
        // Finish the isolated fixture explicitly so no staged journal leaks into
        // the other tests; there was no previous wallet to discard.
        let completed = await manager.completeRestore()
        XCTAssertTrue(completed)
        try await manager.deleteWallet()
    }

    func testRestoreIdentityPreservesSameSeedAndIsolatesDifferentSeed() throws {
        let phrase = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        let other = "legal winner thank year wave sausage worth useful legal winner thank yellow"
        let entropy = Data(try Cdk.mnemonicToEntropy(mnemonic: phrase))
        XCTAssertTrue(WalletRestorePolicy.reusesExistingWallet(previousEntropy: entropy, restoredEntropy: entropy))
        XCTAssertFalse(WalletRestorePolicy.reusesExistingWallet(previousEntropy: nil, restoredEntropy: entropy))
        XCTAssertFalse(WalletRestorePolicy.reusesExistingWallet(previousEntropy: entropy, restoredEntropy: Data(try Cdk.mnemonicToEntropy(mnemonic: other))))
    }

    func testStagedRestoreRetainsQuoteKeysAndRecoveredFundsAcrossRelaunch() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let db = directory.appendingPathComponent("wallet.db")
        try Data("old quote keys and counters".utf8).write(to: db)
        let storage = ReplacementJournalStorage()
        let journal = DurableWalletReplacement(storage: storage, urls: [db])
        try journal.begin(state: Data("old state".utf8))
        try Data("newly recovered funds".utf8).write(to: db)
        try journal.stageRestore()
        for _ in 0..<3 {
            try DurableWalletReplacement(storage: storage, urls: [db]).recover { _ in XCTFail("Do not roll back staged funds") }
        }
        XCTAssertEqual(try Data(contentsOf: db), Data("newly recovered funds".utf8))
        let backup = URL(fileURLWithPath: db.path + ".replacement-backup-v1")
        XCTAssertEqual(try Data(contentsOf: backup), Data("old quote keys and counters".utf8))
        XCTAssertThrowsError(try journal.requireNoStagedRestore())
        try journal.finishRestore()
        try journal.finishRestore()
        try journal.recover { _ in XCTFail("Finished restore must not roll back") }
        try journal.finishRestore()
        XCTAssertEqual(try Data(contentsOf: db), Data("newly recovered funds".utf8))
        XCTAssertFalse(FileManager.default.fileExists(atPath: backup.path))
        XCTAssertFalse(storage.hasSecret(forKey: DurableWalletReplacement.key))
    }

    func testFailedRestoreCompletionRetainsBackupsAndRetries() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let db = directory.appendingPathComponent("wallet.db")
        try Data("old".utf8).write(to: db)
        let storage = ReplacementJournalStorage()
        let journal = DurableWalletReplacement(storage: storage, urls: [db])
        try journal.begin(state: Data())
        try Data("recovered".utf8).write(to: db)
        try journal.stageRestore()
        storage.failWrite = 4 // preparing, installing, staging, then failed commit
        XCTAssertThrowsError(try journal.finishRestore())
        try journal.recover { _ in XCTFail("Staged wallet must remain active") }
        XCTAssertEqual(try Data(contentsOf: URL(fileURLWithPath: db.path + ".replacement-backup-v1")), Data("old".utf8))
        try journal.finishRestore()
        try journal.recover { _ in XCTFail("Committed restore must not roll back") }
        XCTAssertEqual(try Data(contentsOf: db), Data("recovered".utf8))
    }

    func testMissingStagedBackupDoesNotDiscardRecoveredWallet() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let db = directory.appendingPathComponent("wallet.db")
        try Data("old".utf8).write(to: db)
        let storage = ReplacementJournalStorage()
        let journal = DurableWalletReplacement(storage: storage, urls: [db])
        try journal.begin(state: Data())
        try Data("recovered".utf8).write(to: db)
        try journal.stageRestore()
        try FileManager.default.removeItem(at: URL(fileURLWithPath: db.path + ".replacement-backup-v1"))
        XCTAssertThrowsError(try journal.finishRestore())
        XCTAssertThrowsError(try journal.recover { _ in XCTFail("Do not roll back recovered funds") })
        XCTAssertEqual(try Data(contentsOf: db), Data("recovered".utf8))
        XCTAssertTrue(storage.hasSecret(forKey: DurableWalletReplacement.key))
    }

    func testReplacementCheckpointPreservesSqliteWalContents() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let database = directory.appendingPathComponent("wallet.db")
        var connection: OpaquePointer?
        XCTAssertEqual(sqlite3_open(database.path, &connection), SQLITE_OK)
        defer { sqlite3_close(connection) }
        XCTAssertEqual(sqlite3_exec(connection, "PRAGMA journal_mode=WAL; CREATE TABLE recovery_test(value TEXT); INSERT INTO recovery_test VALUES ('proofs before replacement');", nil, nil, nil), SQLITE_OK)
        try await WalletReplacementCheckpoint.flushDatabases(in: [directory])
        let copy = directory.appendingPathComponent("snapshot.db")
        try FileManager.default.copyItem(at: database, to: copy)
        var reopened: OpaquePointer?
        XCTAssertEqual(sqlite3_open(copy.path, &reopened), SQLITE_OK)
        defer { sqlite3_close(reopened) }
        var statement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(reopened, "SELECT value FROM recovery_test", -1, &statement, nil), SQLITE_OK)
        defer { sqlite3_finalize(statement) }
        XCTAssertEqual(sqlite3_step(statement), SQLITE_ROW)
        XCTAssertEqual(String(cString: sqlite3_column_text(statement, 0)), "proofs before replacement")
    }

    func testReplacementCheckpointWaitsForTransientWriterAndSchemaLocks() async throws {
        // WAL blocks the checkpoint; DELETE's exclusive lock blocks statement
        // preparation. Both can occur while the native database is closing.
        for journalMode in ["WAL", "DELETE"] {
            let (directory, writer) = try makeCheckpointDatabase(journalMode: journalMode)
            defer {
                sqlite3_close(writer)
                try? FileManager.default.removeItem(at: directory)
            }
            XCTAssertEqual(sqlite3_exec(writer, "BEGIN EXCLUSIVE; INSERT INTO recovery_test VALUES(2)", nil, nil, nil), SQLITE_OK)
            let started = expectation(description: "Checkpoint started for \(journalMode)")
            let checkpoint = Task.detached(priority: .userInitiated) {
                started.fulfill()
                try await WalletReplacementCheckpoint.flushDatabases(in: [directory])
            }
            await fulfillment(of: [started], timeout: 5)
            try await Task.sleep(for: .milliseconds(100))
            XCTAssertEqual(sqlite3_exec(writer, "COMMIT", nil, nil, nil), SQLITE_OK)
            try await checkpoint.value

            // The replacement copies the main file, so the committed value
            // must survive without depending on the original WAL sidecar.
            let snapshot = directory.appendingPathComponent("snapshot.db")
            try FileManager.default.copyItem(at: directory.appendingPathComponent("wallet.db"), to: snapshot)
            XCTAssertEqual(try checkpointValues(at: snapshot), [1, 2])
        }
    }

    func testReplacementCheckpointRejectsPersistentLockWithoutLosingData() async throws {
        let (directory, writer) = try makeCheckpointDatabase(journalMode: "WAL")
        defer {
            sqlite3_close(writer)
            try? FileManager.default.removeItem(at: directory)
        }
        XCTAssertEqual(sqlite3_exec(writer, "BEGIN IMMEDIATE; INSERT INTO recovery_test VALUES(2)", nil, nil, nil), SQLITE_OK)
        let clock = ContinuousClock()
        let started = clock.now
        do {
            try await WalletReplacementCheckpoint.flushDatabases(in: [directory], retryTimeout: .milliseconds(80))
            XCTFail("A writer that stays active must prevent replacement")
        } catch {
            XCTAssertEqual(error as? WalletCheckpointError, .busy)
        }
        XCTAssertGreaterThanOrEqual(started.duration(to: clock.now), .milliseconds(80))
        XCTAssertLessThan(started.duration(to: clock.now), .seconds(5))

        XCTAssertEqual(sqlite3_exec(writer, "ROLLBACK", nil, nil, nil), SQLITE_OK)
        try await WalletReplacementCheckpoint.flushDatabases(in: [directory])
        XCTAssertEqual(try checkpointValues(at: directory.appendingPathComponent("wallet.db")), [1])
    }

    func testReplacementCheckpointWaitsForNativeDatabaseTeardown() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        for index in 0..<10 {
            let databaseURL = directory.appendingPathComponent("wallet-\(index).db")
            try autoreleasepool {
                let database = try LifecycleSafeWalletDatabase(filePath: databaseURL.path)
                let repository = try WalletRepository(mnemonic: generateMnemonic(), store: customWalletStore(db: database))
                withExtendedLifetime(repository) {}
            }
            try await WalletReplacementCheckpoint.flushDatabases(in: [databaseURL])
        }
    }

    private func makeCheckpointDatabase(journalMode: String) throws -> (URL, OpaquePointer) {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        var connection: OpaquePointer?
        let url = directory.appendingPathComponent("wallet.db")
        XCTAssertEqual(sqlite3_open(url.path, &connection), SQLITE_OK)
        let database = try XCTUnwrap(connection)
        XCTAssertEqual(sqlite3_exec(database, "PRAGMA journal_mode=\(journalMode); CREATE TABLE recovery_test(value INTEGER); INSERT INTO recovery_test VALUES(1)", nil, nil, nil), SQLITE_OK)
        return (directory, database)
    }

    private func checkpointValues(at url: URL) throws -> [Int32] {
        var connection: OpaquePointer?
        XCTAssertEqual(sqlite3_open(url.path, &connection), SQLITE_OK)
        defer { sqlite3_close(connection) }
        var statement: OpaquePointer?
        XCTAssertEqual(sqlite3_prepare_v2(connection, "SELECT value FROM recovery_test ORDER BY value", -1, &statement, nil), SQLITE_OK)
        defer { sqlite3_finalize(statement) }
        var values: [Int32] = []
        var result = sqlite3_step(statement)
        while result == SQLITE_ROW {
            values.append(sqlite3_column_int(statement, 0))
            result = sqlite3_step(statement)
        }
        XCTAssertEqual(result, SQLITE_DONE)
        return values
    }

    func testReplacementRelaunchRestoresSeedDefaultsAndDatabase() throws {
        let storage = ReplacementJournalStorage()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let db = directory.appendingPathComponent("wallet.db")
        try Data("old proofs".utf8).write(to: db)
        let state = Data("old seed and preferences".utf8)
        try DurableWalletReplacement(storage: storage, urls: [db]).begin(state: state)
        try Data("new proofs".utf8).write(to: db)
        var restored: Data?
        try DurableWalletReplacement(storage: storage, urls: [db]).recover { restored = $0 }
        XCTAssertEqual(restored, state)
        XCTAssertEqual(try Data(contentsOf: db), Data("old proofs".utf8))
        XCTAssertFalse(storage.hasSecret(forKey: DurableWalletReplacement.key))
    }

    func testReplacementRollbackCanBeInterruptedAndReplayed() throws {
        let storage = ReplacementJournalStorage()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let db = directory.appendingPathComponent("wallet.db")
        try Data("old".utf8).write(to: db)
        try DurableWalletReplacement(storage: storage, urls: [db]).begin(state: Data())
        XCTAssertThrowsError(try DurableWalletReplacement(storage: storage, urls: [db]).recover { _ in throw Failure.offline })
        try Data("interrupted rollback".utf8).write(to: db)
        try DurableWalletReplacement(storage: storage, urls: [db]).recover { _ in }
        XCTAssertEqual(try Data(contentsOf: db), Data("old".utf8))
    }

    func testCommittedReplacementNeverRestoresPreviousSeed() throws {
        let storage = ReplacementJournalStorage()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let db = directory.appendingPathComponent("wallet.db")
        try Data("old".utf8).write(to: db)
        let journal = DurableWalletReplacement(storage: storage, urls: [db])
        try journal.begin(state: Data())
        try Data("new".utf8).write(to: db)
        try journal.commit()
        try DurableWalletReplacement(storage: storage, urls: [db]).recover { _ in XCTFail("Committed replacement must not roll back") }
        XCTAssertEqual(try Data(contentsOf: db), Data("new".utf8))
    }

    func testPreparingReplacementNeverTouchesOriginalFiles() throws {
        let storage = ReplacementJournalStorage()
        storage.failWrite = 2
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let db = directory.appendingPathComponent("wallet.db")
        try Data("old".utf8).write(to: db)
        XCTAssertThrowsError(try DurableWalletReplacement(storage: storage, urls: [db]).begin(state: Data()))
        try DurableWalletReplacement(storage: storage, urls: [db]).recover { _ in XCTFail("Preferences are still untouched") }
        XCTAssertEqual(try Data(contentsOf: db), Data("old".utf8))
    }

    func testMissingReplacementBackupBlocksRecoveryBeforeSeedChanges() throws {
        let storage = ReplacementJournalStorage()
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let db = directory.appendingPathComponent("wallet.db")
        try Data("old".utf8).write(to: db)
        try DurableWalletReplacement(storage: storage, urls: [db]).begin(state: Data())
        try FileManager.default.removeItem(atPath: db.path + ".replacement-backup-v1")
        try Data("new".utf8).write(to: db)
        XCTAssertThrowsError(try DurableWalletReplacement(storage: storage, urls: [db]).recover { _ in XCTFail("Must not mismatch the seed") })
        XCTAssertEqual(try Data(contentsOf: db), Data("new".utf8))
        XCTAssertTrue(storage.hasSecret(forKey: DurableWalletReplacement.key))
    }

    func testManualRestoreBackupBarrierSurvivesDefaultsReload() throws {
        let suite = "WalletRestoreBarrierTests-" + UUID().uuidString
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        defer { defaults.removePersistentDomain(forName: suite) }
        ICloudRestoreState.setIncomplete(true, defaults: defaults)
        XCTAssertTrue(defaults.synchronize())
        let reopened = try XCTUnwrap(UserDefaults(suiteName: suite))
        XCTAssertFalse(ICloudRestorePolicy.shouldPerformBackup(restoreIncomplete: ICloudRestoreState.isIncomplete(defaults: reopened)))
        XCTAssertTrue(StorageKeys.walletBoundaryKeys.contains(ICloudRestoreState.incompleteKey))
        ICloudRestoreState.setIncomplete(false, defaults: reopened)
        XCTAssertTrue(ICloudRestorePolicy.shouldPerformBackup(restoreIncomplete: ICloudRestoreState.isIncomplete(defaults: reopened)))
    }

    func testMeltAmountRejectsOverflowingFee() throws {
        XCTAssertEqual(try LightningService.requiredMeltAmount(amount: 10, feeReserve: 2), 12)
        XCTAssertThrowsError(try LightningService.requiredMeltAmount(amount: .max, feeReserve: 1))
        let quote = MeltQuoteInfo(id: "quote", mintUrl: "https://mint.example", amount: .max, feeReserve: 1, paymentMethod: .bolt11, state: .pending, expiry: nil)
        XCTAssertEqual(quote.totalAmount, .max)
    }

    func testSeedOnlyAccountScansHistoricalOutputsBeforeNewIssuance() async throws {
        let mintURL = ProcessInfo.processInfo.environment["NUTSHELL_MINT_URL"] ?? "http://localhost:3338"
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let seed = try generateMnemonic()
        let originalDB = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("original.sqlite").path)
        let original = try WalletRepository(mnemonic: seed, store: customWalletStore(db: originalDB))
        let url = MintUrl(url: mintURL)
        let active = MintInfo(url: mintURL, name: "Test mint", isActive: true, balance: 0)
        try await original.createWallet(mintUrl: url, unit: .sat, targetProofCount: nil)
        let firstService = LightningService(walletRepository: { original }, walletDatabase: { originalDB }, getActiveMint: { active })
        var first = try await firstService.createMintQuote(amount: 16, targetMintURL: mintURL)
        for _ in 0..<50 where first.state == .pending {
            try await Task.sleep(for: .milliseconds(100))
            first = try await firstService.checkMintQuote(quoteId: first.id)
        }
        _ = try await firstService.mintTokens(quoteId: first.id)

        // Same seed, fresh ledger: counters and proofs have not been restored.
        let restoredDB = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("restored.sqlite").path)
        let restored = try WalletRepository(mnemonic: seed, store: customWalletStore(db: restoredDB))
        try await restored.createWallet(mintUrl: url, unit: .sat, targetProofCount: nil)
        let service = LightningService(walletRepository: { restored }, walletDatabase: { restoredDB }, getActiveMint: { active })
        var next = try await service.createMintQuote(amount: 100, targetMintURL: mintURL)
        for _ in 0..<50 where next.state == .pending {
            try await Task.sleep(for: .milliseconds(100))
            next = try await service.checkMintQuote(quoteId: next.id)
        }
        let received = try await service.mintTokens(quoteId: next.id)
        XCTAssertEqual(received, 100)
        let wallet = try await restored.getWallet(mintUrl: url, unit: .sat)
        let balance = try await wallet.totalBalance().value
        XCTAssertEqual(balance, 116, "Historical proofs and the new payment must both survive")
    }

    func testLightningServiceMintsIntoTheQuotesWalletAfterActiveMintChanges() async throws {
        let mintURL = ProcessInfo.processInfo.environment["NUTSHELL_MINT_URL"] ?? "http://localhost:3338"
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        weak var releasedDatabase: LifecycleSafeWalletDatabase?
        do {
            let database = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("wallet.sqlite").path)
            releasedDatabase = database
            let repository = try WalletRepository(mnemonic: generateMnemonic(), store: customWalletStore(db: database))
            try await repository.createWallet(mintUrl: MintUrl(url: mintURL), unit: .sat, targetProofCount: nil)
            var active = MintInfo(url: mintURL, name: "Test mint", isActive: true, balance: 0)
            let service = LightningService(walletRepository: { repository }, walletDatabase: { database }, getActiveMint: { active })
            var quote = try await service.createMintQuote(amount: 16)
            active = MintInfo(url: "https://other.example", name: "Other mint", isActive: true, balance: 0)
            for _ in 0..<50 where quote.state == .pending {
                try await Task.sleep(for: .milliseconds(100))
                quote = try await service.checkMintQuote(quoteId: quote.id)
            }
            XCTAssertEqual(quote.state, .paid)
            let received = try await service.mintTokens(quoteId: quote.id)
            XCTAssertEqual(received, 16)
            let wallet = try await repository.getWallet(mintUrl: MintUrl(url: mintURL), unit: .sat)
            let balance = try await wallet.totalBalance().value
            XCTAssertEqual(balance, 16)
        }
        // The native writer may be the last owner. Give teardown time to run
        // inside this test so a regression is attributed to the wallet lifecycle.
        let deadline = Date().addingTimeInterval(3)
        while releasedDatabase != nil, Date() < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertNil(releasedDatabase)
    }

    func testPaymentSurfacesDistinguishPayloadsWithIdenticalPrefixes() {
        let prefix = String(repeating: "a", count: 80)
        XCTAssertNotEqual(FlowCover.receiveToken(prefix + "1").id, FlowCover.receiveToken(prefix + "2").id)
        let sheet = { (invoice: String) in
            WalletSheet.sendAmount(.melt(request: invoice, mode: .lightning, decoded: .bolt11(amountSats: 21, description: nil)))
        }
        XCTAssertNotEqual(sheet(prefix + "1").id, sheet(prefix + "2").id)
    }

    func testMintIdentitiesPreserveSchemePathAndHostBoundaries() {
        XCTAssertNotEqual(MintURLIdentity.normalized("https://mint.example/a"), MintURLIdentity.normalized("https://mint.examplea"))
        XCTAssertNotEqual(MintURLIdentity.normalized("https://mint.example/A"), MintURLIdentity.normalized("https://mint.example/a"))
        XCTAssertNotEqual(MintURLIdentity.normalized("http://mint.example"), MintURLIdentity.normalized("https://mint.example"))
        XCTAssertEqual(MintURLIdentity.normalized(" HTTPS://MINT.EXAMPLE:443/a/ "), "https://mint.example/a")
    }

    func testReceiveFeeRoundsOnceAcrossKeysetsAndCachesLookups() async throws {
        var lookedUp: [String] = []
        let fee = try await TokenService.receiveFee(keysetIDs: ["a", "b", "a"]) { key in
            lookedUp.append(key)
            return key == "a" ? 100 : 800
        }
        XCTAssertEqual(fee, 1)
        XCTAssertEqual(lookedUp, ["a", "b"])
        let rounded = try await TokenService.receiveFee(keysetIDs: ["a"]) { _ in 1001 }
        XCTAssertEqual(rounded, 2)
    }

    func testUnknownReceiveFeePropagatesFailureInsteadOfReportingZero() async {
        do {
            _ = try await TokenService.receiveFee(keysetIDs: ["missing"]) { _ in throw Failure.offline }
            XCTFail("Unknown fees must not be presented as free")
        } catch { XCTAssertTrue(error is Failure) }
    }

    func testExcessiveReceiveFeeCannotOverflow() async throws {
        let single = try await TokenService.receiveFee(keysetIDs: ["a"]) { _ in UInt64.max }
        XCTAssertEqual(single, UInt64.max / 1000 + 1)
        do {
            _ = try await TokenService.receiveFee(keysetIDs: ["a", "a"]) { _ in UInt64.max }
            XCTFail("An invalid fee must fail without trapping")
        } catch { }
    }

    func testUnresolvedMintRecoveryBlocksAnotherMintAttempt() async {
        let original = quote(reservation: "operation")
        var didRecover = false
        do {
            _ = try await MintQuoteRecovery.reconcile(
                quote: original,
                recover: { didRecover = true },
                reload: { original }
            )
            XCTFail("A reserved quote must remain blocked")
        } catch { }
        XCTAssertTrue(didRecover)
    }

    func testRecoveredMintReturnsOnlyNewlyIssuedAmount() async throws {
        let recovered = try await MintQuoteRecovery.reconcile(
            quote: quote(reservation: "operation", issued: 5),
            recover: {},
            reload: { self.quote(reservation: nil, issued: 12) }
        )
        XCTAssertEqual(recovered, 7)
    }

    func testRecoveryReadFailureDoesNotPermitRetry() async {
        do {
            _ = try await MintQuoteRecovery.reconcile(
                quote: quote(reservation: "operation"), recover: {},
                reload: { throw Failure.offline }
            )
            XCTFail("Failure to inspect the reservation must fail closed")
        } catch { XCTAssertTrue(error is Failure) }
    }

    func testNPCImportRetainsReservedQuote() async throws {
        let database = try WalletSqliteDatabase.newInMemory()
        let original = quote(reservation: nil)
        try await database.addMintQuote(quote: original)
        let operationID = UUID().uuidString
        try await database.reserveMintQuote(quoteId: original.id, operationId: operationID)
        let before = try await database.getMintQuote(quoteId: original.id)
        try await WalletManager.replaceStoredNPCMintQuote(original, in: database)
        let after = try await database.getMintQuote(quoteId: original.id)
        XCTAssertEqual(after, before)
        XCTAssertEqual(after?.usedByOperation, operationID.lowercased())
    }

    func testStaleQuoteUpdatePreservesStoredReservation() async throws {
        let database = try WalletSqliteDatabase.newInMemory()
        let original = quote(reservation: nil)
        try await database.addMintQuote(quote: original)
        let operationID = UUID().uuidString
        try await database.reserveMintQuote(quoteId: original.id, operationId: operationID)
        let service = LightningService(walletRepository: { nil }, walletDatabase: { database }, getActiveMint: { nil })
        // The stale version must never be installed by deleting the newer row.
        do { try await service.replaceStoredMintQuote(original, in: database) } catch { }
        let stored = try await database.getMintQuote(quoteId: original.id)
        XCTAssertEqual(stored?.usedByOperation, operationID.lowercased())
        XCTAssertEqual(stored?.request, original.request)
    }

    func testPaidOnchainStatusReloadAllowsRepeatedMetadataWrites() async throws {
        let database = try WalletSqliteDatabase.newInMemory()
        let original = onchainQuote()
        try await database.addMintQuote(quote: original)
        let service = LightningService(walletRepository: { nil }, walletDatabase: { database }, getActiveMint: { nil })

        for _ in 0..<3 {
            let before = try await database.getMintQuote(quoteId: original.id)!
            let refreshed = try await MintQuoteRecovery.refresh(
                check: {
                    // CDK 0.18 persists the update, then returns its pre-write version.
                    try await database.addMintQuote(quote: before)
                    return before
                },
                reload: { try await database.getMintQuote(quoteId: original.id) }
            )
            XCTAssertGreaterThan(refreshed.version, before.version)
            XCTAssertEqual(refreshed.amountPaid.value, 1_000)
            XCTAssertEqual(refreshed.amountIssued.value, 0)
            // The original status result is rejected by this same guard.
            // Using the reloaded result lets reconciliation proceed to issuance.
            try await service.replaceStoredMintQuote(refreshed, in: database)
        }
    }

    func testEmptyRestoredAccountCannotIssueIfCounterScanFails() async throws {
        let database = try WalletSqliteDatabase.newInMemory()
        let original = onchainQuote()
        try await database.addMintQuote(quote: original)
        let wallet = try OnchainStatusWallet(database: database)
        wallet.failRestore = true
        let repository = try OnchainStatusRepository(wallet: wallet, database: database)
        let service = LightningService(walletRepository: { repository }, walletDatabase: { database }, getActiveMint: { nil })
        do {
            _ = try await service.mintTokens(quoteId: original.id)
            XCTFail("Must scan before allocating outputs")
        } catch { }
        XCTAssertEqual(wallet.restoreCalls, 1)
        XCTAssertEqual(wallet.mintCalls, 0)
        let retained = try await database.getMintQuote(quoteId: original.id)
        XCTAssertEqual(retained?.amountPaid.value, 1_000)
        XCTAssertEqual(retained?.amountIssued.value, 0)
    }

    func testConfirmedOnchainReceiveReachesMintingAndIssuedState() async throws {
        let database = try WalletSqliteDatabase.newInMemory()
        let original = onchainQuote()
        try await database.addMintQuote(quote: original)
        let wallet = try OnchainStatusWallet(database: database)
        let repository = try OnchainStatusRepository(wallet: wallet, database: database)
        let service = LightningService(walletRepository: { repository }, walletDatabase: { database }, getActiveMint: { nil })

        for _ in 0..<3 {
            let checked = try await service.checkMintQuote(quoteId: original.id)
            XCTAssertEqual(checked.state, .paid)
            XCTAssertEqual(checked.mintableAmount, 1_000)
        }
        let received = try await service.mintTokens(quoteId: original.id)
        XCTAssertEqual(received, 1_000)
        XCTAssertEqual(wallet.mintCalls, 1)
        XCTAssertEqual(wallet.restoreCalls, 1)
        let settled = try await service.checkMintQuote(quoteId: original.id)
        XCTAssertEqual(settled.state, .issued)
        XCTAssertEqual(settled.mintableAmount, 0)
    }

    func testOnchainStatusReloadKeepsCdkRecoveryReservationChanges() async throws {
        let database = try WalletSqliteDatabase.newInMemory()
        let original = onchainQuote()
        try await database.addMintQuote(quote: original)
        let operationID = UUID().uuidString.lowercased()
        try await database.reserveMintQuote(quoteId: original.id, operationId: operationID)
        let reserved = try await database.getMintQuote(quoteId: original.id)!
        let refreshed = try await MintQuoteRecovery.refresh(
            check: {
                try await database.releaseMintQuote(operationId: operationID)
                return reserved
            },
            reload: { try await database.getMintQuote(quoteId: original.id) }
        )
        XCTAssertNil(refreshed.usedByOperation)
        XCTAssertEqual(refreshed.secretKey, original.secretKey)
        XCTAssertEqual(refreshed.amountPaid.value, 1_000)
    }

    func testOnchainStatusReloadFailsClosedWhenStoredQuoteIsMissing() async {
        do {
            _ = try await MintQuoteRecovery.refresh(check: { self.onchainQuote() }, reload: { nil })
            XCTFail("Must not fall back to the stale status result")
        } catch { }
    }

    func testOnchainStatusReloadPropagatesDatabaseFailure() async {
        do {
            _ = try await MintQuoteRecovery.refresh(
                check: { self.onchainQuote() }, reload: { throw Failure.offline }
            )
            XCTFail("Must not continue with stale quote ownership")
        } catch { XCTAssertTrue(error is Failure) }
    }

    private func onchainQuote() -> MintQuote {
        MintQuote(
            id: "onchain-quote", amount: nil, unit: .sat, request: "bc1qexample", state: .paid,
            expiry: 0, mintUrl: MintUrl(url: "https://mint.example"),
            amountIssued: Amount(value: 0), amountPaid: Amount(value: 1_000), updatedAt: 1,
            estimatedBlocks: nil, paymentMethod: .onchain, secretKey: nil,
            usedByOperation: nil, version: 0
        )
    }

    func testOnchainStatusCheckAdoptsCdkStoredQuote() async throws {
        let database = try WalletSqliteDatabase.newInMemory()
        let address = "bc1qexampledepositaddress"
        try await database.addMintQuote(quote: onchainQuote(request: address, paid: 0, version: 0))
        let beforeCheckRow = try await database.getMintQuote(quoteId: "onchain-quote")
        let beforeCheck = try XCTUnwrap(beforeCheckRow)
        // CDK's status check stores the mint's counters, then returns its
        // pre-write copy, which is one version behind the stored row.
        let checked = onchainQuote(request: address, paid: 300_000, version: beforeCheck.version)
        try await database.addMintQuote(quote: checked)
        let service = LightningService(walletRepository: { nil }, walletDatabase: { database }, getActiveMint: { nil })

        do {
            try await service.replaceStoredMintQuote(checked, in: database)
            XCTFail("The stale copy must still be rejected by the version guard")
        } catch { }

        let refreshed = try await service.storedMintQuoteAfterStatusCheck(
            quoteId: checked.id, paymentMethod: .onchain, fallbackAmount: nil
        )
        XCTAssertEqual(refreshed.amountPaid.value, 300_000)
        XCTAssertEqual(refreshed.amountIssued.value, 0)
        XCTAssertEqual(refreshed.amount?.value, 300_000)
        XCTAssertEqual(refreshed.request, address)
        let storedRow = try await database.getMintQuote(quoteId: "onchain-quote")
        let stored = try XCTUnwrap(storedRow)
        XCTAssertEqual(stored.amountPaid.value, 300_000)
        XCTAssertEqual(stored.amount?.value, 300_000)
        XCTAssertGreaterThan(stored.version, checked.version)
    }

    func testStatusCheckWithoutLocalNormalizationLeavesCdkRowUntouched() async throws {
        let database = try WalletSqliteDatabase.newInMemory()
        try await database.addMintQuote(quote: onchainQuote(request: "bc1qexample", paid: 0, version: 0))
        let beforeCheckRow = try await database.getMintQuote(quoteId: "onchain-quote")
        let beforeCheck = try XCTUnwrap(beforeCheckRow)
        let service = LightningService(walletRepository: { nil }, walletDatabase: { database }, getActiveMint: { nil })

        let refreshed = try await service.storedMintQuoteAfterStatusCheck(
            quoteId: beforeCheck.id, paymentMethod: .onchain, fallbackAmount: nil
        )
        XCTAssertNil(refreshed.amount)
        let storedRow = try await database.getMintQuote(quoteId: "onchain-quote")
        let stored = try XCTUnwrap(storedRow)
        XCTAssertEqual(stored.version, beforeCheck.version)
    }

    func testLightningStatusCheckDoesNotRewriteCurrentBolt11Quote() async throws {
        let database = try WalletSqliteDatabase.newInMemory()
        let checked = MintQuote(
            id: "bolt11-status", amount: Amount(value: 21), unit: .sat, request: "invoice",
            state: .paid, expiry: 1_900_000_000, mintUrl: MintUrl(url: "https://mint.example"),
            amountIssued: Amount(value: 0), amountPaid: Amount(value: 21), updatedAt: 1,
            estimatedBlocks: nil, paymentMethod: .bolt11, secretKey: nil,
            usedByOperation: nil, version: 0
        )
        try await database.addMintQuote(quote: checked)
        try await database.addMintQuote(quote: checked)
        let before = try await database.getMintQuote(quoteId: checked.id)!
        let service = LightningService(walletRepository: { nil }, walletDatabase: { database }, getActiveMint: { nil })
        let refreshed = try await service.storedMintQuoteAfterStatusCheck(
            quoteId: checked.id, paymentMethod: .bolt11, fallbackAmount: 21
        )
        let after = try await database.getMintQuote(quoteId: checked.id)!
        XCTAssertEqual(refreshed.version, before.version)
        XCTAssertEqual(after.version, before.version)
        XCTAssertEqual(refreshed.amountPaid.value, 21)
    }

    func testLightningStatusCheckNormalizesBolt12ExpiryOnlyOnce() async throws {
        let database = try WalletSqliteDatabase.newInMemory()
        let checked = quote(reservation: nil)
        try await database.addMintQuote(quote: checked)
        try await database.addMintQuote(quote: checked)
        let before = try await database.getMintQuote(quoteId: checked.id)!
        let service = LightningService(walletRepository: { nil }, walletDatabase: { database }, getActiveMint: { nil })
        let refreshed = try await service.storedMintQuoteAfterStatusCheck(
            quoteId: checked.id, paymentMethod: .bolt12, fallbackAmount: nil
        )
        XCTAssertEqual(refreshed.expiry, 253_402_300_799)
        XCTAssertEqual(refreshed.version, before.version + 1)
        let repeated = try await service.storedMintQuoteAfterStatusCheck(
            quoteId: checked.id, paymentMethod: .bolt12, fallbackAmount: nil
        )
        XCTAssertEqual(repeated.version, refreshed.version)
        XCTAssertEqual(repeated.amountPaid, refreshed.amountPaid)
        XCTAssertEqual(repeated.usedByOperation, refreshed.usedByOperation)
    }

    func testLightningStatusCheckRejectsMissingStoredQuote() async throws {
        let database = try WalletSqliteDatabase.newInMemory()
        let service = LightningService(walletRepository: { nil }, walletDatabase: { database }, getActiveMint: { nil })
        do {
            _ = try await service.storedMintQuoteAfterStatusCheck(
                quoteId: "missing", paymentMethod: .bolt11, fallbackAmount: 21
            )
            XCTFail("Must not continue with a stale status result")
        } catch { }
    }

    func testNWCRejectsLimitThatWouldOverflowMillisatoshis() throws {
        XCTAssertNil(try NWCManager.paymentLimitMsat(nil))
        XCTAssertEqual(try NWCManager.paymentLimitMsat(100), 100_000)
        XCTAssertThrowsError(try NWCManager.paymentLimitMsat(UInt64.max))
    }

    func testDisablingNWCDuringStartupDiscardsLateFailure() async {
        let settings = SettingsStore(storage: InMemoryStorage())
        settings.nwcEnabled = true
        settings.nwcSelectedMint = "https://mint.example"
        let manager = NWCManager(settingsStore: settings)
        let started = expectation(description: "Wallet lookup started")
        var continuation: CheckedContinuation<Wallet, Error>?
        manager.configure(walletProvider: { _ in
            try await withCheckedThrowingContinuation {
                continuation = $0
                started.fulfill()
            }
        }, seedProvider: { Data(repeating: 1, count: 64) })
        let startup = Task { await manager.start() }
        await fulfillment(of: [started], timeout: 3)
        manager.isEnabled = false
        continuation?.resume(throwing: Failure.offline)
        await startup.value
        await manager.stop()
        XCTAssertFalse(manager.isRunning)
        XCTAssertFalse(manager.isBusy)
        XCTAssertNil(manager.connectionUri)
        XCTAssertNil(manager.errorMessage)
    }

    func testWalletBoundaryCancelsQueuedOldRepositoryWork() async throws {
        let coordinator = WalletOperationCoordinator(watchdogThreshold: 0)
        let entered = expectation(description: "Boundary holds the lane")
        var release: CheckedContinuation<Void, Never>?
        let boundary = Task {
            try await coordinator.perform(kind: .recovery) {
                await withCheckedContinuation {
                    release = $0
                    entered.fulfill()
                }
                await coordinator.cancelPendingOperations()
            }
        }
        await fulfillment(of: [entered], timeout: 3)
        let pending = Task {
            try await coordinator.perform(kind: .send) { XCTFail("Old repository work ran after replacement") }
        }
        let deadline = Date().addingTimeInterval(3)
        while await coordinator.snapshot().waitingCount == 0, Date() < deadline { await Task.yield() }
        let queued = await coordinator.snapshot().waitingCount
        XCTAssertEqual(queued, 1)
        release?.resume()
        try await boundary.value
        do {
            try await pending.value
            XCTFail("Expected cancellation at the wallet boundary")
        } catch { XCTAssertTrue(error is CancellationError) }
        try await coordinator.perform(kind: .balance) {}
    }

    private func quote(reservation: String?, issued: UInt64 = 0) -> MintQuote {
        MintQuote(
            id: "quote", amount: nil, unit: .sat, request: "receive-request", state: .paid,
            expiry: 0, mintUrl: MintUrl(url: "https://mint.example"),
            amountIssued: Amount(value: issued), amountPaid: Amount(value: 12), updatedAt: 0,
            estimatedBlocks: nil, paymentMethod: .bolt12, secretKey: nil,
            usedByOperation: reservation, version: 0
        )
    }
    private func onchainQuote(request: String, paid: UInt64, version: UInt32) -> MintQuote {
        MintQuote(
            id: "onchain-quote", amount: nil, unit: .sat, request: request, state: .unpaid,
            expiry: 0, mintUrl: MintUrl(url: "https://mint.example"),
            amountIssued: Amount(value: 0), amountPaid: Amount(value: paid), updatedAt: 0,
            estimatedBlocks: nil, paymentMethod: .onchain, secretKey: nil,
            usedByOperation: nil, version: version
        )
    }
}

/// Emulates CDK 0.18's status contract over its real SQLite backend: save the
/// updated quote, then return the object with the pre-write version.
private final class OnchainStatusWallet: Wallet, @unchecked Sendable {
    private let database: WalletSqliteDatabase
    private(set) var mintCalls = 0
    private(set) var restoreCalls = 0
    var failRestore = false

    init(database: WalletSqliteDatabase) throws {
        self.database = database
        // CDK 0.18's NoHandle initializer still frees handle zero on teardown.
        let native = try Wallet(
            mintUrl: "https://mint.example", unit: .sat, mnemonic: generateMnemonic(),
            store: customWalletStore(db: database), config: WalletConfig(targetProofCount: nil)
        )
        super.init(unsafeFromHandle: native.uniffiCloneHandle())
    }

    required init(unsafeFromHandle handle: UInt64) { fatalError("Test double only") }

    override func restore() async throws -> Restored {
        restoreCalls += 1
        if failRestore { throw URLError(.notConnectedToInternet) }
        return Restored(spent: Amount(value: 0), unspent: Amount(value: 0), pending: Amount(value: 0))
    }

    override func checkMintQuoteStatus(quoteId: String) async throws -> MintQuote {
        let current = try await database.getMintQuote(quoteId: quoteId)!
        let checked = MintQuote(
            id: current.id, amount: current.amount, unit: current.unit, request: current.request,
            state: mintCalls == 0 ? .paid : .issued, expiry: current.expiry, mintUrl: current.mintUrl,
            amountIssued: mintCalls == 0 ? current.amountIssued : current.amountPaid,
            amountPaid: current.amountPaid, updatedAt: current.updatedAt,
            estimatedBlocks: current.estimatedBlocks, paymentMethod: current.paymentMethod,
            secretKey: current.secretKey, usedByOperation: current.usedByOperation, version: current.version
        )
        try await database.addMintQuote(quote: checked)
        return checked
    }

    override func mintUnified(
        quoteId: String, amountSplitTarget: SplitTarget, spendingConditions: SpendingConditions?
    ) async throws -> [Proof] {
        mintCalls += 1
        return [Proof(amount: Amount(value: 1_000), secret: "test-secret",
            c: "0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
            keysetId: "001234567890abcd", witness: nil, dleq: nil, p2pkE: nil)]
    }
}

private final class OnchainStatusRepository: WalletRepository, @unchecked Sendable {
    private let wallet: Wallet

    init(wallet: Wallet, database: WalletSqliteDatabase) throws {
        self.wallet = wallet
        let native = try WalletRepository(mnemonic: generateMnemonic(), store: customWalletStore(db: database))
        super.init(unsafeFromHandle: native.uniffiCloneHandle())
    }

    required init(unsafeFromHandle handle: UInt64) { fatalError("Test double only") }

    override func getWallet(mintUrl: MintUrl, unit: Cdk.CurrencyUnit) async throws -> Wallet { wallet }
}

private final class ReplacementJournalStorage: SecureStorageProtocol {
    private var values: [String: String] = [:]
    var failWrite = 0
    private var writes = 0
    func saveSecret(_ secret: String, forKey key: String) throws {
        writes += 1
        if writes == failWrite { throw CocoaError(.fileWriteUnknown) }
        values[key] = secret
    }
    func loadSecret(forKey key: String) throws -> String? { values[key] }
    func deleteSecret(forKey key: String) throws { values.removeValue(forKey: key) }
    func hasSecret(forKey key: String) -> Bool { values[key] != nil }
}

/// Fee-aware Max for Lightning and on-chain sends. Mirrors Android's
/// `MeltMaxAmountTest`.
final class SendMaxMeltAmountTests: XCTestCase {
    private func largest(
        balance: UInt64,
        reserve: @escaping (UInt64) -> UInt64,
        quotes: inout Int
    ) async throws -> UInt64? {
        var count = 0
        let result = try await largestPayableMeltAmount(balance: balance) { amount in
            count += 1
            return amount + reserve(amount)
        }
        quotes = count
        return result
    }

    func testFlatReserveSettlesOnTheSecondQuote() async throws {
        var quotes = 0
        let payable = try await largest(balance: 1_314, reserve: { _ in 13 }, quotes: &quotes)
        XCTAssertEqual(payable, 1_301)
        XCTAssertEqual(quotes, 2)
    }

    func testPercentageReserveWithFloorFitsTheBalance() async throws {
        var quotes = 0
        // 1% rounded up, at least 2 sat — a common Lightning backend shape.
        let reserve: (UInt64) -> UInt64 = { max(2, ($0 + 99) / 100) }
        let payable = try await largest(balance: 1_314, reserve: reserve, quotes: &quotes)
        XCTAssertEqual(payable, 1_300)
        XCTAssertLessThanOrEqual(1_300 + reserve(1_300), 1_314)
        XCTAssertGreaterThan(1_301 + reserve(1_301), 1_314, "1,300 is the largest amount that fits")
    }

    func testNoReserveKeepsTheWholeBalanceWithOneQuote() async throws {
        var quotes = 0
        let payable = try await largest(balance: 500, reserve: { _ in 0 }, quotes: &quotes)
        XCTAssertEqual(payable, 500)
        XCTAssertEqual(quotes, 1)
    }

    func testBalanceBelowTheReserveFloorPaysNothing() async throws {
        var quotes = 0
        let payable = try await largest(balance: 1, reserve: { _ in 2 }, quotes: &quotes)
        XCTAssertNil(payable)
    }

    func testInconclusiveQuotesDoNotReportAnEmptyBalance() async {
        do {
            _ = try await largestPayableMeltAmount(balance: 100, rounds: 1) { $0 + 2 }
            XCTFail("Expected an inconclusive calculation error")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("Try again or enter an amount"))
        }
    }

    func testQuoteFailuresReachTheCaller() async {
        struct MintDown: Error {}
        do {
            _ = try await largestPayableMeltAmount(balance: 100) { _ in throw MintDown() }
            XCTFail("expected the quote error")
        } catch {
            XCTAssertTrue(error is MintDown)
        }
    }
}
