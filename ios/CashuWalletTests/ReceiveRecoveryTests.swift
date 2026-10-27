import Cdk
import XCTest
@testable import CashuWallet

@MainActor
final class ReceiveRecoveryTests: XCTestCase {
    private let mintURL = "http://localhost:3340"

    func testRemovalSurvivesReloadWithoutReconnectingRetainedAccounts() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let db = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("wallet.sqlite").path)
        let repo = try WalletRepository(mnemonic: generateMnemonic(), store: customWalletStore(db: db))
        let url = "https://mint.example/Mint"
        try await repo.createWallet(mintUrl: MintUrl(url: url), unit: .sat, targetProofCount: nil)
        let storage = InMemoryStorage()
        let store = WalletStore(storage: storage)
        let service = MintService(walletRepository: { repo }, walletStore: store)
        service.trackReceivedMintLocally(url: url, unit: .sat)
        try await service.removeMint(XCTUnwrap(service.mints.first))

        let reloadedStore = WalletStore(storage: storage)
        let reloaded = MintService(walletRepository: { nil }, walletStore: reloadedStore)
        reloaded.loadCachedMints()
        reloaded.trackReceivedMintLocally(url: "https://MINT.example/Mint/", unit: .sat)
        reloaded.trackReceivedMintLocally(url: url, unit: .usd)
        XCTAssertTrue(reloadedStore.isMintRemoved(url: url))
        XCTAssertTrue(reloaded.mints.isEmpty)
        XCTAssertNil(reloaded.activeMint)

        // Deliberately reconnecting the mint enables receipt recovery again,
        // even when metadata cannot be fetched yet.
        await reloaded.ensureMintTracked(url: url)
        reloaded.trackReceivedMintLocally(url: url, unit: .usd)
        XCTAssertFalse(reloadedStore.isMintRemoved(url: url))
        XCTAssertEqual(reloaded.mints.count, 1)
        XCTAssertEqual(Set(reloaded.mints[0].units), ["sat", "usd"])
    }

    /// Removal is reversible, so an unfinished transfer no longer blocks it.
    /// The record stays so adding the mint again picks the transfer back up.
    func testRemovalProceedsPastAnUnfinishedTransferAndKeepsItsRecord() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let db = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("wallet.sqlite").path)
        let repo = try WalletRepository(mnemonic: generateMnemonic(), store: customWalletStore(db: db))
        let source = "https://source.example"
        let destination = "https://destination.example"
        try await repo.createWallet(mintUrl: MintUrl(url: source), unit: .sat, targetProofCount: nil)
        let store = WalletStore(storage: InMemoryStorage())
        store.saveMints([
            MintInfo(url: source, name: "Source", isActive: true, balance: 0),
            MintInfo(url: destination, name: "Destination", isActive: false, balance: 0),
        ])
        let transfer = MintTransferRecord(
            id: "transfer", sourceMintURL: source, destinationMintURL: destination,
            mintQuoteID: "mint-quote", meltQuoteID: "melt-quote", amount: 21,
            createdAt: Date(timeIntervalSince1970: 1_700_000_000), state: .committed
        )
        store.saveMintTransfers([transfer])
        let manager = WalletManager(walletStore: store)
        manager.walletRepository = repo
        manager.isRuntimeReady = true
        manager.mintService.loadCachedMints()
        XCTAssertTrue(manager.hasUnfinishedMintTransfer(mintURL: source))

        let removed = await manager.removeMint(try XCTUnwrap(manager.mints.first { $0.url == source }))

        XCTAssertTrue(removed)
        XCTAssertNil(manager.errorMessage)
        XCTAssertEqual(manager.mints.map(\.url), [destination])
        XCTAssertTrue(store.isMintRemoved(url: source))
        XCTAssertEqual(store.loadMintTransfers(), [transfer])
        XCTAssertTrue(manager.hasUnfinishedMintTransfer(mintURL: destination))
    }

    func testRemovalBeforeMetadataCommitSurvivesReloadAndClearsWithTheWallet() {
        let storage = InMemoryStorage()
        let store = WalletStore(storage: storage)
        let url = "https://mint.example/Mint"
        store.saveMints([MintInfo(url: url, name: "Test", isActive: true, balance: 21)])
        store.setMintRemoved(url: "https://MINT.example/Mint/", removed: true)
        let reloaded = WalletStore(storage: storage)
        XCTAssertTrue(reloaded.isMintRemoved(url: url))
        XCTAssertFalse(reloaded.isMintRemoved(url: "https://mint.example/mint"))
        XCTAssertTrue(reloaded.loadMints().isEmpty)
        reloaded.removeAllWalletData()
        XCTAssertFalse(reloaded.isMintRemoved(url: url))
    }

    func testOnlyReceiveSagasSupplyRecoveryCandidates() {
        XCTAssertEqual(ReceiveRecoveryCandidate.fromSaga(#"{"kind":"receive","mint_url":"https://mint.example","unit":"usd"}"#)?.unit, .usd)
        XCTAssertNil(ReceiveRecoveryCandidate.fromSaga(#"{"kind":"send","mint_url":"https://mint.example","unit":"sat"}"#))
        XCTAssertNil(ReceiveRecoveryCandidate.fromSaga("invalid"))
    }

    func testOfflinePlaceholderKeepsEveryRecoveredCurrency() {
        let store = WalletStore(storage: InMemoryStorage())
        let service = MintService(walletRepository: { nil }, walletStore: store)
        service.trackReceivedMintLocally(url: "https://mint.example", unit: .sat)
        service.trackReceivedMintLocally(url: "https://mint.example", unit: .usd)
        service.trackReceivedMintLocally(url: "https://mint.example", unit: .usd)
        XCTAssertEqual(store.loadMints().count, 1)
        XCTAssertEqual(Set(store.loadMints().first?.units ?? []), ["sat", "usd"])
    }

    func testCompletedReceiptBeforeTrackingSurvivesOfflineRelaunch() async throws {
        try await exerciseReceipt(interrupt: false)
    }

    func testAcceptedSwapWithLostResponseRecoversAfterRelaunchExactlyOnce() async throws {
        try await exerciseReceipt(interrupt: true)
    }

    private func exerciseReceipt(interrupt: Bool) async throws {
        try await control("reset")
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let seed = try generateMnemonic()
        let path = directory.appendingPathComponent("receiver.sqlite").path
        let senderDB = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("sender.sqlite").path)
        let sender = try WalletRepository(mnemonic: generateMnemonic(), store: customWalletStore(db: senderDB))
        let mint = MintUrl(url: mintURL)
        try await sender.createWallet(mintUrl: mint, unit: .sat, targetProofCount: nil)
        let wallet = try await sender.getWallet(mintUrl: mint, unit: .sat)
        let quote = try await wallet.mintQuote(paymentMethod: .bolt11, amount: Amount(value: 16), description: nil, extra: nil)
        for _ in 0..<40 {
            if try await wallet.checkMintQuote(quoteId: quote.id).state == .paid { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        _ = try await wallet.mint(quoteId: quote.id, amountSplitTarget: .none, spendingConditions: nil)
        let token = try await TokenService(walletRepository: { sender }, getActiveMint: { nil })
            .sendTokens(amount: 16, mintUrl: mintURL).token
        do {
            let db = try LifecycleSafeWalletDatabase(filePath: path)
            let repo = try WalletRepository(mnemonic: seed, store: customWalletStore(db: db))
            try await repo.createWallet(mintUrl: mint, unit: .sat, targetProofCount: nil)
            let previewCandidates = try await ReceiveRecoveryCandidate.discover(database: db)
            XCTAssertTrue(previewCandidates.isEmpty, "A preview or unapproved token must not track a mint")
            if interrupt { try await control("interrupt-next-swap") }
            do {
                _ = try await TokenService(walletRepository: { repo }, getActiveMint: { nil }).receiveTokens(tokenString: token)
                XCTAssertFalse(interrupt, "Proxy should interrupt the accepted swap")
            } catch {
                if !interrupt { throw error }
            }
        }
        try await control("offline")
        let db = try LifecycleSafeWalletDatabase(filePath: path)
        let repo = try WalletRepository(mnemonic: seed, store: customWalletStore(db: db))
        let candidates = try await ReceiveRecoveryCandidate.discover(database: db)
        XCTAssertEqual(candidates, [ReceiveRecoveryCandidate(mintURL: mintURL, unit: .sat)])
        let store = WalletStore(storage: InMemoryStorage())
        let mints = MintService(walletRepository: { nil }, walletStore: store)
        for candidate in candidates {
            mints.trackReceivedMintLocally(url: candidate.mintURL, unit: candidate.unit)
            mints.trackReceivedMintLocally(url: candidate.mintURL, unit: candidate.unit)
        }
        XCTAssertEqual(store.loadMints().count, 1, "Tracking needs no repository or metadata request")
        if !interrupt {
            let offlineBalance = try await db.getBalance(mintUrl: mint, unit: .sat, state: [.unspent])
            XCTAssertEqual(offlineBalance, 16)
        }
        try await control("reset")
        let recovered = try await repo.getWallet(mintUrl: mint, unit: .sat)
        _ = try await recovered.recoverIncompleteSagas()
        _ = try await recovered.recoverIncompleteSagas()
        let balance = try await recovered.totalBalance().value
        XCTAssertEqual(balance, 16)
        let history = try await recovered.listTransactions(direction: .incoming)
        XCTAssertEqual(history.count, 1)
        XCTAssertEqual(history.first?.status, .completed)
    }

    private func control(_ action: String) async throws {
        var request = URLRequest(url: URL(string: "\(mintURL)/__receipt_test/\(action)")!)
        request.httpMethod = "POST"
        request.timeoutInterval = 5
        let (_, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
    }
}
