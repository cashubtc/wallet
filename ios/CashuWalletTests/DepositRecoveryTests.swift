import Cdk
import XCTest
@testable import CashuWallet

@MainActor
final class DepositRecoveryTests: XCTestCase {
    private func request(url: String = "https://mint.example", id: String = "quote", limit: UInt32 = 1_000) throws -> DepositRecoveryRequest {
        try DepositRecoveryRequest(mintURL: url, quoteID: id, method: .bolt11, unit: "sat", searchLimit: limit)
    }

    func testRequestPreservesOpaqueQuoteIDsAndBoundsTheSearch() throws {
        let value = try request(url: " mint.example/path/ ", id: " AbC-123 ")
        XCTAssertEqual(value.mintURL, "https://mint.example/path")
        XCTAssertEqual(value.quoteID, "AbC-123")
        for limit: UInt32 in [0, 1, 100_000, .max] { XCTAssertThrowsError(try request(limit: limit)) }
        XCTAssertEqual(try request(limit: 10_000).searchLimit, 10_000)
    }

    func testInvalidEndpointsAndQuoteIDsAreRejected() throws {
        for url in ["http://mint.example", "https://name:password@mint.example", "https://mint.example?seed=x", "https://mint.example#x", "https://mint.example:99999"] {
            XCTAssertThrowsError(try request(url: url))
        }
        for id in ["", "  ", "quote id", "quote\nother", String(repeating: "x", count: 1_025)] {
            XCTAssertThrowsError(try request(id: id))
        }
    }

    func testPaidOrAlreadyIssuedCountersDoNotReportNewRecovery() {
        var quote = MintQuoteInfo(id: "quote", request: "invoice", amount: 21, isAmountless: false,
            paymentMethod: .bolt11, state: .paid, expiry: nil, createdAt: nil, unit: "usd", amountPaid: 42, amountIssued: 21)
        XCTAssertEqual(DepositRecoveryResult.from(quote: quote).status, .pending)
        let result = DepositRecoveryResult.from(quote: quote, newlyIssued: 21)
        XCTAssertEqual(result.status, .recovered)
        XCTAssertEqual(result.amount, 21)
        XCTAssertEqual(result.remaining, 21)
        XCTAssertTrue(result.message.contains("0.21"))
        quote.amountIssued = 42
        XCTAssertEqual(DepositRecoveryResult.from(quote: quote).status, .alreadyClaimed)
    }

    func testUnpaidExpiredAndNetworkPendingAreDifferentOutcomes() {
        let quote = MintQuoteInfo(id: "quote", request: "invoice", amount: 21, isAmountless: false,
            paymentMethod: .bolt11, state: .pending, expiry: 1, createdAt: nil)
        XCTAssertEqual(DepositRecoveryResult.from(quote: quote).status, .expired)
        XCTAssertEqual(DepositRecoveryResult.from(quote: quote, retryPending: true).status, .pending)
    }

    /// Exercises the production facade with released FFI bindings and a fresh
    /// database. No seed replacement, keychain writes, or external mint funds.
    func testLostUSDDepositNeedsDeeperSearchAndIsClaimedOnlyOnce() async throws {
        try await withPaidQuote(unit: .usd, keyIndex: 1_000) { seed, id, url, directory, expected in
            let db = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("recovered.db").path)
            let repo = try WalletRepository(mnemonic: seed, store: customWalletStore(db: db))
            let manager = WalletManager(walletStore: WalletStore(storage: InMemoryStorage()))
            manager.db = db
            manager.walletRepository = repo
            manager.isRuntimeReady = true
            let shallow = try DepositRecoveryRequest(mintURL: url, quoteID: id, method: .bolt11, unit: "usd", searchLimit: 1_000)
            let notFound = try await manager.recoverDeposit(shallow)
            XCTAssertEqual(notFound.status, .keyNotFound)
            let unmatched = try await db.getMintQuote(quoteId: id)
            XCTAssertNil(unmatched, "A failed import must not enter automatic issuance")
            let before = try await db.incrementDerivationCounter(namespace: "nut20_quote", count: 0)
            XCTAssertEqual(before, 0)
            let deeper = try DepositRecoveryRequest(mintURL: url, quoteID: id, method: .bolt11, unit: "usd", searchLimit: 10_000)
            let recovered = try await manager.recoverDeposit(deeper)
            XCTAssertEqual(recovered.status, .recovered)
            XCTAssertEqual(recovered.amount, expected)
            XCTAssertEqual(manager.balancesByUnit["usd"], 21)
            let stored = try await db.getMintQuote(quoteId: id)
            XCTAssertNotNil(stored?.secretKey)
            let counter = try await db.incrementDerivationCounter(namespace: "nut20_quote", count: 0)
            XCTAssertGreaterThanOrEqual(counter, 1_001)
            let repeated = try await manager.recoverDeposit(deeper)
            XCTAssertEqual(repeated.status, .alreadyClaimed)
            XCTAssertEqual(manager.balancesByUnit["usd"], 21)
            let wallet = try await repo.getWallet(mintUrl: MintUrl(url: url), unit: .usd)
            let history = try await wallet.listTransactions(direction: .incoming)
            XCTAssertEqual(history.filter { $0.quoteId == id }.count, 1)
        }
    }

    func testWrongSeedCannotClaimOrQueueTheDeposit() async throws {
        try await withPaidQuote(unit: .sat, keyIndex: 0) { _, id, url, directory, _ in
            let db = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("wrong-seed.db").path)
            let repo = try WalletRepository(mnemonic: generateMnemonic(), store: customWalletStore(db: db))
            let manager = WalletManager(walletStore: WalletStore(storage: InMemoryStorage()))
            manager.db = db
            manager.walletRepository = repo
            manager.isRuntimeReady = true
            let result = try await manager.recoverDeposit(self.request(url: url, id: id))
            XCTAssertEqual(result.status, .keyNotFound)
            let stored = try await db.getMintQuote(quoteId: id)
            XCTAssertNil(stored)
            XCTAssertTrue(manager.mints.isEmpty)
            let counter = try await db.incrementDerivationCounter(namespace: "nut20_quote", count: 0)
            XCTAssertEqual(counter, 0)
        }
    }

    func testExistingQuoteIdentityIsCheckedBeforeRecovery() async throws {
        try await withPaidQuote(unit: .sat, keyIndex: 0) { seed, id, url, directory, _ in
            let db = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("identity.db").path)
            let repo = try WalletRepository(mnemonic: seed, store: customWalletStore(db: db))
            try await repo.createWallet(mintUrl: MintUrl(url: url), unit: .sat, targetProofCount: nil)
            let wallet = try await repo.getWallet(mintUrl: MintUrl(url: url), unit: .sat)
            _ = try await wallet.fetchMintQuoteWithKeySearch(quoteId: id, paymentMethod: .bolt11, keySearchLimit: 1_000)
            let original = try await db.getMintQuote(quoteId: id)
            let manager = WalletManager(walletStore: WalletStore(storage: InMemoryStorage()))
            manager.db = db; manager.walletRepository = repo; manager.isRuntimeReady = true
            do {
                _ = try await manager.recoverDeposit(self.request(url: "https://other-mint.example", id: id))
                XCTFail("A colliding quote ID must not be imported through another mint")
            } catch DepositRecoveryError.differentQuote { }
            let retained = try await db.getMintQuote(quoteId: id)
            XCTAssertEqual(original?.secretKey, retained?.secretKey)
            XCTAssertEqual(original?.version, retained?.version)
        }
    }

    func testLostLightningOfferAndOnchainDepositRecoverOnlyOnce() async throws {
        for method in [PaymentMethodKind.bolt12, .onchain] {
            try await withPaidQuote(unit: .sat, keyIndex: 0, method: method) { seed, id, url, directory, expected in
                let db = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("restored.db").path)
                let repo = try WalletRepository(mnemonic: seed, store: customWalletStore(db: db))
                let manager = WalletManager(walletStore: WalletStore(storage: InMemoryStorage()))
                manager.db = db; manager.walletRepository = repo; manager.isRuntimeReady = true
                let request = try DepositRecoveryRequest(mintURL: url, quoteID: id, method: method, unit: "sat", searchLimit: 1_000)
                let result = try await manager.recoverDeposit(request)
                XCTAssertEqual(result.status, .recovered)
                XCTAssertEqual(result.amount, expected)
                let repeated = try await manager.recoverDeposit(request)
                XCTAssertEqual(repeated.status, .alreadyClaimed)
                XCTAssertEqual(manager.balance, expected)
            }
        }
    }

    func testPriorIssuanceIsRestoredBeforeClaimingTheLostDeposit() async throws {
        try await withPaidQuote(unit: .sat, keyIndex: 0, priorIssued: 13) { seed, id, url, directory, expected in
            let db = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("after-loss.db").path)
            let repo = try WalletRepository(mnemonic: seed, store: customWalletStore(db: db))
            let manager = WalletManager(walletStore: WalletStore(storage: InMemoryStorage()))
            manager.db = db; manager.walletRepository = repo; manager.isRuntimeReady = true
            let result = try await manager.recoverDeposit(self.request(url: url, id: id))
            XCTAssertEqual(result.status, .recovered)
            XCTAssertEqual(result.amount, expected)
            XCTAssertEqual(manager.balance, 13 + expected)
            let repeated = try await manager.recoverDeposit(self.request(url: url, id: id))
            XCTAssertEqual(repeated.status, .alreadyClaimed)
            XCTAssertEqual(manager.balance, 13 + expected)
        }
    }

    private func withPaidQuote(unit: Cdk.CurrencyUnit, keyIndex: UInt32, method: PaymentMethodKind = .bolt11, priorIssued: UInt64 = 0,
        exercise: (String, String, String, URL, UInt64) async throws -> Void) async throws {
        let url = ProcessInfo.processInfo.environment["CDK_MINT_URL"] ?? "http://localhost:3339"
        guard let endpoint = URL(string: url + "/v1/info"),
              let (_, response) = try? await URLSession.shared.data(from: endpoint),
              (response as? HTTPURLResponse)?.statusCode == 200 else { throw XCTSkip("Requires the local CDK fake mint") }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let seed = try generateMnemonic()
        let db = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("original.db").path)
        let repo = try WalletRepository(mnemonic: seed, store: customWalletStore(db: db))
        try await repo.createWallet(mintUrl: MintUrl(url: url), unit: unit, targetProofCount: nil)
        let wallet = try await repo.getWallet(mintUrl: MintUrl(url: url), unit: unit)
        if priorIssued > 0 {
            let prior = try await wallet.mintQuote(paymentMethod: .bolt11, amount: Amount(value: priorIssued), description: nil, extra: nil)
            for _ in 0..<40 {
                if try await wallet.checkMintQuoteStatus(quoteId: prior.id).state == .paid { break }
                try await Task.sleep(for: .milliseconds(100))
            }
            _ = try await wallet.mintUnified(quoteId: prior.id, amountSplitTarget: .none, spendingConditions: nil)
        }
        _ = try await db.incrementDerivationCounter(namespace: "nut20_quote", count: keyIndex)
        let created = try await wallet.mintQuote(paymentMethod: method.cdkMethod, amount: Amount(value: 21), description: nil,
            extra: method == .onchain ? "{}" : nil)
        var quote = created
        for _ in 0..<40 {
            quote = try await wallet.checkMintQuoteStatus(quoteId: created.id)
            if quote.state == .paid { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        XCTAssertEqual(quote.state, .paid)
        try await exercise(seed, quote.id, url, directory, quote.amountPaid.value)
    }
}
