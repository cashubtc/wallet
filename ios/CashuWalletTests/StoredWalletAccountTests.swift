import Cdk
import XCTest
@testable import CashuWallet

@MainActor
final class StoredWalletAccountTests: XCTestCase {
    private enum Failure: Error { case storage }

    func testFailedAccountPreservesWholeCurrencyTotalAndRetryReplacesIt() async throws {
        let a = StoredWalletAccount(mintURL: "https://a.example", unit: .usd)
        let b = StoredWalletAccount(mintURL: "https://b.example", unit: .usd)
        let sat = StoredWalletAccount(mintURL: a.mintURL, unit: .sat)
        let first = try await StoredBalanceProjection.load(accounts: [a, b, sat], previousTotals: ["usd": 500, "sat": 1]) {
            if $0 == b { throw Failure.storage }
            return $0 == sat ? 30 : 200
        }
        XCTAssertEqual(first.totals, ["usd": 500, "sat": 30])
        let retry = try await StoredBalanceProjection.load(accounts: [a, b, a], previousTotals: first.totals) { _ in 200 }
        XCTAssertEqual(retry.totals, ["usd": 400])
    }

    func testDiscontinuedZeroBalanceCurrencyAndHistorySurviveOfflineRelaunch() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("wallet.sqlite").path
        let mnemonic = try generateMnemonic()
        let mint = MintUrl(url: "https://offline.example")
        let id = String(repeating: "a", count: 64)
        do {
            let db = try LifecycleSafeWalletDatabase(filePath: path)
            // No USD advertisement remains, but the historical account does.
            try await db.addMint(mintUrl: mint, mintInfo: nil)
            try await db.addTransaction(transaction: Cdk.Transaction(
                id: TransactionId(hex: id), mintUrl: mint, direction: .incoming,
                amount: Amount(value: 250), fee: Amount(value: 0), unit: .usd,
                ys: [], timestamp: 1, memo: nil, metadata: [:], quoteId: nil,
                paymentRequest: nil, paymentProof: nil, paymentMethod: nil, sagaId: nil, status: .completed
            ))
        }
        let db = try LifecycleSafeWalletDatabase(filePath: path)
        let repo = try WalletRepository(mnemonic: mnemonic, store: customWalletStore(db: db))
        let wallets = await repo.getWallets()
        XCTAssertFalse(wallets.contains { $0.unit() == .usd })
        let accounts = try await StoredWalletAccount.discover(database: db, repository: repo)
        let usd = StoredWalletAccount(mintURL: mint.url, unit: .usd)
        XCTAssertTrue(accounts.contains(usd))
        let projection = try await StoredBalanceProjection.load(accounts: accounts, previousTotals: [:]) {
            try await db.getBalance(mintUrl: MintUrl(url: $0.mintURL), unit: $0.unit, state: [.unspent])
        }
        XCTAssertEqual(projection.totals["usd"], 0)
        let reader = FailingAccountHistoryDatabase(database: db)
        let service = TransactionService(walletRepository: { repo }, walletDatabase: { reader }, getTrackedMintUrls: { [mint.url] })
        await service.loadTransactions(includeRemoteObservations: false)
        XCTAssertEqual(service.transactions.map(\.unit), ["usd"])
        reader.failAccountReads = true
        await service.loadTransactions(includeRemoteObservations: false)
        XCTAssertEqual(service.transactions.map(\.unit), ["usd"])
        reader.failAccountReads = false
        await service.loadTransactions(includeRemoteObservations: false)
        XCTAssertEqual(service.transactions.count, 1)
    }

    func testDefaultPortAccountsKeepTheirStorageKeysAndBothBalances() async throws {
        try await withHistoryService { db, repo, service, _ in
            let urls = ["https://offline.example:443", "https://offline.example"]
            let keys = ["02", "03"].map { $0 + "79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798" }
            for (index, url) in urls.enumerated() {
                let mint = MintUrl(url: url)
                // CDK derives stored transaction ids from the saga (or ys),
                // rather than the FFI record's id. Distinct sagas prevent the
                // second fixture row from replacing the first one.
                let sagaID = UUID().uuidString
                try await db.addMint(mintUrl: mint, mintInfo: nil)
                try await db.updateProofs(added: [ProofInfo(
                    proof: Proof(amount: Amount(value: UInt64(8 << index)), secret: "stored-account-\(index)",
                        c: keys[index], keysetId: "001234567890abcd", witness: nil, dleq: nil, p2pkE: nil),
                    y: PublicKey(hex: keys[index]), mintUrl: mint, state: .unspent,
                    spendingCondition: nil, unit: .usd, derivationIndex: nil,
                    usedByOperation: nil, createdByOperation: nil
                )], removedYs: [])
                try await db.addTransaction(transaction: Cdk.Transaction(
                    id: TransactionId(hex: String(repeating: index == 0 ? "a" : "b", count: 64)),
                    mintUrl: mint, direction: .incoming, amount: Amount(value: 8), fee: Amount(value: 0),
                    unit: .usd, ys: [], timestamp: 1, memo: nil, metadata: [:], quoteId: nil,
                    paymentRequest: nil, paymentProof: nil, paymentMethod: nil, sagaId: sagaID, status: .completed
                ))
            }
            let storedTransactions = try await db.listTransactions(mintUrl: nil, direction: nil, unit: .usd)
            XCTAssertEqual(Set(storedTransactions.map { $0.id.hex }).count, 2)
            let accounts = try await StoredWalletAccount.discover(database: db, repository: repo)
                .filter { $0.unit == .usd && $0.matches(mintURL: urls[1]) }
            XCTAssertEqual(Set(accounts.map(\.mintURL)), Set(urls))
            let projection = try await StoredBalanceProjection.load(accounts: accounts, previousTotals: [:]) {
                try await db.getBalance(mintUrl: MintUrl(url: $0.mintURL), unit: $0.unit, state: [.unspent])
            }
            XCTAssertEqual(projection.totals["usd"], 24)
            XCTAssertEqual(projection.balance(mintURL: urls[1], unit: .usd), 24)
            let partial = try await StoredBalanceProjection.load(accounts: accounts, previousTotals: ["usd": 24]) {
                if $0.mintURL == urls[0] { throw Failure.storage }
                return 16
            }
            XCTAssertEqual(partial.totals["usd"], 24)
            XCTAssertNil(partial.balance(mintURL: urls[1], unit: .usd))
            await service.loadTransactions(includeRemoteObservations: false)
            XCTAssertEqual(Set(service.transactions.compactMap(\.mintUrl)), Set(urls))
            db.failAccountReads = true
            await service.loadTransactions(includeRemoteObservations: false)
            XCTAssertEqual(service.transactions.count, 2)
        }
    }

    func testAccountAndDiscoveryFailuresDoNotSuppressFreshQuotes() async throws {
        try await withHistoryService { db, _, service, _ in
            try await db.addMintQuote(quote: MintQuote(
                id: "invoice", amount: Amount(value: 8), unit: .sat, request: "invoice-request",
                state: .unpaid, expiry: 1, mintUrl: MintUrl(url: "https://offline.example"),
                amountIssued: Amount(value: 0), amountPaid: Amount(value: 0), updatedAt: 1,
                estimatedBlocks: nil, paymentMethod: .bolt11, secretKey: nil, usedByOperation: nil, version: 0
            ))
            let staleInvoice = WalletTransaction(
                id: "invoice", amount: 8, type: .incoming, kind: .lightning, date: Date(), memo: nil,
                status: .pending, mintUrl: "https://offline.example", quoteId: "invoice"
            )
            let completed = WalletTransaction(
                id: "cdk-transaction", amount: 8, type: .incoming, kind: .lightning, date: Date(), memo: nil,
                status: .completed, mintUrl: "https://offline.example", quoteId: "settled-quote"
            )
            for discoveryFails in [false, true] {
                db.failAccountReads = true
                db.failDiscoveryReads = discoveryFails
                db.failQuoteReads = false
                service.transactions = [staleInvoice, completed]
                await service.loadTransactions(includeRemoteObservations: false)
                XCTAssertEqual(service.transactions.first { $0.id == "invoice" }?.status, .expired)
                XCTAssertEqual(service.transactions.count, 2)
                XCTAssertEqual(service.transactions.first { $0.id == completed.id }?.status, .completed)

                db.failQuoteReads = true
                service.transactions = [staleInvoice, completed]
                await service.loadTransactions(includeRemoteObservations: false)
                XCTAssertEqual(service.transactions.first { $0.id == "invoice" }?.status, .pending)
                XCTAssertEqual(service.transactions.count, 2)
            }
        }
    }

    func testDiscoveryFailureStillRebuildsLocallyParkedTokensAfterRelaunch() async throws {
        try await withHistoryService { db, repo, service, store in
            db.failDiscoveryReads = true
            let pending = PendingReceiveToken(tokenId: "parked", token: "cashuAparked", amount: 8,
                date: Date(), mintUrl: "https://offline.example")
            service.savePendingReceiveToken(pending)
            await service.loadTransactions(includeRemoteObservations: false)
            XCTAssertEqual(service.transactions.map(\.id), [pending.tokenId])
            XCTAssertTrue(service.transactions.first?.isPendingReceiveToken == true)

            let relaunched = TransactionService(walletRepository: { repo }, walletDatabase: { db },
                getTrackedMintUrls: { ["https://offline.example"] }, walletStore: store)
            await relaunched.loadTransactions(includeRemoteObservations: false)
            XCTAssertEqual(relaunched.transactions.map(\.id), [pending.tokenId])
            relaunched.removePendingReceiveToken(tokenId: pending.tokenId)
            await relaunched.loadTransactions(includeRemoteObservations: false)
            XCTAssertTrue(relaunched.transactions.isEmpty)
        }
    }

    func testBolt11RetriesBecomeOneReceiptWithoutDeletingStoredAttempts() async throws {
        try await withHistoryService { db, repo, service, store in
            let mint = MintUrl(url: "https://offline.example")
            let quoteID = "retry-quote"
            for index in 0..<6 {
                try await db.addTransaction(transaction: Cdk.Transaction(
                    id: TransactionId(hex: String(repeating: "a", count: 64)), mintUrl: mint,
                    direction: .incoming, amount: Amount(value: 64), fee: Amount(value: 0),
                    unit: .sat, ys: [], timestamp: UInt64(index + 1), memo: nil, metadata: [:],
                    quoteId: quoteID, paymentRequest: "lnbc-fixture", paymentProof: nil,
                    paymentMethod: .bolt11, sagaId: UUID().uuidString,
                    status: index == 5 ? .completed : .failed
                ))
            }
            let stored = try await db.listTransactions(mintUrl: nil, direction: nil, unit: nil)
            XCTAssertEqual(stored.count, 6)
            let completed = try XCTUnwrap(stored.first { $0.status == .completed })
            for reader in [service, TransactionService(walletRepository: { repo }, walletDatabase: { db },
                getTrackedMintUrls: { [mint.url] }, walletStore: store)] {
                await reader.loadTransactions(includeRemoteObservations: false)
                XCTAssertEqual(reader.transactions.map(\.id), [completed.id.hex])
                XCTAssertEqual(reader.transactions.first?.amount, 64)
                XCTAssertEqual(reader.transactions.first?.status, .completed)
                XCTAssertEqual(reader.transactions.liveDetail(openId: stored[0].id.hex, openQuoteId: quoteID)?.id,
                    completed.id.hex)
            }
            let retained = try await db.listTransactions(mintUrl: nil, direction: nil, unit: nil)
            XCTAssertEqual(retained.count, 6)
            let balance = try await db.getBalance(mintUrl: mint, unit: .sat, state: [.unspent])
            XCTAssertEqual(balance, 0, "History projection must not create proofs")
        }
    }

    func testLiveBolt11RetriesThroughLightningServiceProduceOneReceipt() async throws {
        try XCTSkipUnless(ProcessInfo.processInfo.environment["CASHU_LIVE_MINT_HISTORY"] == "1",
            "Start the local mint and CI/mint-history-retry-proxy.py, then opt in")
        let mintURL = "http://127.0.0.1:3344"
        let mint = MintUrl(url: mintURL)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("wallet.sqlite").path
        let mnemonic = try generateMnemonic()
        let db = try LifecycleSafeWalletDatabase(filePath: path)
        let repo = try WalletRepository(mnemonic: mnemonic, store: customWalletStore(db: db))
        try await repo.createWallet(mintUrl: mint, unit: .sat, targetProofCount: nil)
        let active = MintInfo(url: mintURL, name: "Local test mint", isActive: true, balance: 0)
        let lightning = LightningService(walletRepository: { repo }, walletDatabase: { db }, getActiveMint: { active })
        let history = TransactionService(walletRepository: { repo }, walletDatabase: { db },
            getTrackedMintUrls: { [mintURL] }, walletStore: WalletStore(storage: InMemoryStorage()))
        var quote = try await lightning.createMintQuote(amount: 64)
        _ = try await mintHistoryControl("reject", quote: quote.id)
        for _ in 0..<50 where quote.state != .paid {
            quote = try await lightning.checkMintQuote(quoteId: quote.id)
            if quote.state != .paid { try await Task.sleep(for: .milliseconds(100)) }
        }
        XCTAssertEqual(quote.state, .paid)
        for _ in 0..<5 {
            do {
                _ = try await lightning.mintTokens(quoteId: quote.id)
                XCTFail("The proxy must reject the actual mint request")
            } catch { /* Assert the five exact rejections at the HTTP boundary below. */ }
        }
        let wallet = try await repo.getWallet(mintUrl: mint, unit: .sat)
        let failures = try await db.listTransactions(mintUrl: mint, direction: nil, unit: .sat)
        XCTAssertEqual(failures.count, 5)
        XCTAssertTrue(failures.allSatisfy { $0.status == .failed && $0.quoteId == quote.id })
        let beforeBalance = try await wallet.totalBalance().value
        XCTAssertEqual(beforeBalance, 0)
        _ = try await mintHistoryControl("accept", quote: quote.id)
        let minted = try await lightning.mintTokens(quoteId: quote.id)
        XCTAssertEqual(minted, 64)
        let stored = try await db.listTransactions(mintUrl: mint, direction: nil, unit: .sat)
        XCTAssertEqual(stored.count, 6)
        XCTAssertEqual(Set(stored.map { $0.id.hex }).count, 6)
        XCTAssertTrue(stored.allSatisfy { $0.quoteId == quote.id && $0.paymentRequest == quote.request })
        XCTAssertEqual(stored.filter { $0.status == .completed }.count, 1)
        let completed = try XCTUnwrap(stored.first { $0.status == .completed })
        let balance = try await wallet.totalBalance().value
        XCTAssertEqual(balance, 64)
        let stats = try await mintHistoryControl("stats", quote: quote.id)
        XCTAssertEqual(stats["rejected"] as? Int, 5)
        XCTAssertEqual(stats["successful"] as? Int, 1)
        await history.loadTransactions(includeRemoteObservations: false)
        XCTAssertEqual(history.transactions.map(\.id), [completed.id.hex])
        XCTAssertEqual(history.transactions.first?.status, .completed)
        // A new database handle/repository must project durable real receipts too.
        let reopenedDB = try LifecycleSafeWalletDatabase(filePath: path)
        let reopenedRepo = try WalletRepository(mnemonic: mnemonic, store: customWalletStore(db: reopenedDB))
        let reopenedHistory = TransactionService(walletRepository: { reopenedRepo }, walletDatabase: { reopenedDB },
            getTrackedMintUrls: { [mintURL] }, walletStore: WalletStore(storage: InMemoryStorage()))
        await reopenedHistory.loadTransactions(includeRemoteObservations: false)
        XCTAssertEqual(reopenedHistory.transactions.map(\.id), [completed.id.hex])
        let retained = try await reopenedDB.listTransactions(mintUrl: mint, direction: nil, unit: .sat)
        XCTAssertEqual(retained.count, 6)
        let reopenedBalance = try await reopenedDB.getBalance(mintUrl: mint, unit: .sat, state: [.unspent])
        XCTAssertEqual(reopenedBalance, 64)
    }

    private func mintHistoryControl(_ action: String, quote: String) async throws -> [String: Any] {
        var request = URLRequest(url: URL(string: "http://127.0.0.1:3344/__mint_history/" + action)!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["quote": quote])
        let (data, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    private func withHistoryService(
        _ body: (FailingAccountHistoryDatabase, WalletRepository, TransactionService, CashuWallet.WalletStore) async throws -> Void
    ) async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let database = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("wallet.sqlite").path)
        let reader = FailingAccountHistoryDatabase(database: database)
        let repo = try WalletRepository(mnemonic: generateMnemonic(), store: customWalletStore(db: database))
        let store = WalletStore(storage: InMemoryStorage())
        let service = TransactionService(walletRepository: { repo }, walletDatabase: { reader },
            getTrackedMintUrls: { ["https://offline.example"] }, walletStore: store)
        try await body(reader, repo, service, store)
    }

    func testOnchainHistoryPreservesDirectionsStatesAndLegacyMethodAfterRelaunch() async throws {
        try await withHistoryService { db, repo, service, store in
            let mint = MintUrl(url: "https://offline.example")
            let address = "bc1qhistoryfixture"
            let txid = String(repeating: "b", count: 64)
            for (index, status) in [Cdk.TransactionStatus.pending, .completed, .failed].enumerated() {
                for direction in [Cdk.TransactionDirection.incoming, .outgoing] {
                    try await db.addTransaction(transaction: Cdk.Transaction(
                        id: TransactionId(hex: String(repeating: "a", count: 64)),
                        mintUrl: mint, direction: direction, amount: Amount(value: 2_100),
                        fee: Amount(value: 10), unit: .sat, ys: [], timestamp: UInt64(index + 1),
                        memo: nil, metadata: [:], quoteId: UUID().uuidString,
                        paymentRequest: address, paymentProof: txid,
                        paymentMethod: index == 0 ? .custom(method: "onchain") : .onchain,
                        sagaId: UUID().uuidString, status: status
                    ))
                }
            }
            for reader in [service, TransactionService(walletRepository: { repo }, walletDatabase: { db },
                getTrackedMintUrls: { [mint.url] }, walletStore: store)] {
                await reader.loadTransactions(includeRemoteObservations: false)
                XCTAssertEqual(reader.transactions.count, 6)
                XCTAssertEqual(Set(reader.transactions.map(\.id)).count, 6)
                for row in reader.transactions {
                    XCTAssertEqual(row.kind, .onchain)
                    XCTAssertEqual(row.amount, 2_100)
                    XCTAssertEqual(row.invoice, address)
                    XCTAssertEqual(row.preimage, txid)
                    XCTAssertEqual(row.displayTitle, row.type == .incoming ? "Bitcoin received" : "Bitcoin sent")
                    XCTAssertTrue(HistorySearch.matches(query: "bitcoin", transaction: row))
                }
                for status in [WalletTransaction.TransactionStatus.pending, .completed, .failed] {
                    XCTAssertEqual(reader.transactions.filter { $0.status == status }.count, 2)
                }
            }
        }
    }

    func testFulfilledOnchainPaymentsRemainInHistoryWithEquivalentMintURLs() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("wallet.sqlite").path
        let mint = MintUrl(url: "https://offline.example:443")
        let mnemonic = try generateMnemonic()
        do {
            let db = try LifecycleSafeWalletDatabase(filePath: path)
            for direction in [Cdk.TransactionDirection.incoming, .outgoing] {
                try await db.addTransaction(transaction: Cdk.Transaction(
                    id: TransactionId(hex: String(repeating: "a", count: 64)), mintUrl: mint,
                    direction: direction, amount: Amount(value: 2_100), fee: Amount(value: 10),
                    unit: .sat, ys: [], timestamp: 1, memo: nil, metadata: [:], quoteId: UUID().uuidString,
                    paymentRequest: "bc1qfulfilledfixture", paymentProof: String(repeating: "b", count: 64),
                    paymentMethod: .onchain, sagaId: UUID().uuidString, status: .completed
                ))
            }
        }
        for _ in 0..<2 {
            let db = try LifecycleSafeWalletDatabase(filePath: path)
            let stored = try await db.listTransactions(mintUrl: nil, direction: nil, unit: nil)
            XCTAssertEqual(stored.count, 2)
            XCTAssertTrue(stored.allSatisfy { $0.status == .completed && $0.mintUrl.url == mint.url })
            let pendingQuotes = try await db.getUnissuedMintQuotes()
            XCTAssertTrue(pendingQuotes.isEmpty)
            let repo = try WalletRepository(mnemonic: mnemonic, store: customWalletStore(db: db))
            let reader = TransactionService(walletRepository: { repo }, walletDatabase: { db },
                getTrackedMintUrls: { ["https://offline.example/"] },
                walletStore: WalletStore(storage: InMemoryStorage()))
            await reader.loadTransactions(includeRemoteObservations: false)
            XCTAssertEqual(Set(reader.transactions.map(\.id)), Set(stored.map { $0.id.hex }))
            XCTAssertEqual(reader.transactions.count, 2)
            for row in reader.transactions {
                XCTAssertEqual(row.status, .completed)
                XCTAssertEqual(row.kind, .onchain)
                XCTAssertEqual(row.amount, 2_100)
                XCTAssertEqual(row.preimage, String(repeating: "b", count: 64))
                XCTAssertEqual(row.displayTitle, row.type == .incoming ? "Bitcoin received" : "Bitcoin sent")
                XCTAssertTrue(HistorySearch.matches(query: "bitcoin", transaction: row))
            }
        }
    }

    func testOnchainDepositTransitionsFromPaidQuoteToSingleCompletedReceipt() async throws {
        try await withHistoryService { db, _, service, _ in
            let mint = MintUrl(url: "https://offline.example:443")
            let quote = MintQuote(
                id: "onchain-deposit", amount: nil, unit: .sat, request: "bc1qhistoryfixture",
                state: .paid, expiry: 1, mintUrl: mint,
                amountIssued: Amount(value: 0), amountPaid: Amount(value: 2_100), updatedAt: 1,
                estimatedBlocks: nil, paymentMethod: .onchain, secretKey: nil, usedByOperation: nil, version: 0
            )
            try await db.addMintQuote(quote: quote)
            await service.loadTransactions(includeRemoteObservations: false)
            let pending = try XCTUnwrap(service.transactions.first)
            XCTAssertEqual(service.transactions.count, 1)
            XCTAssertEqual(pending.kind, .onchain)
            XCTAssertEqual(pending.amount, 2_100)
            XCTAssertEqual(pending.status, .pending)
            XCTAssertFalse(pending.isUnpaidInvoice)

            let sagaID = UUID().uuidString
            func transaction(status: Cdk.TransactionStatus) -> Cdk.Transaction { Cdk.Transaction(
                id: TransactionId(hex: String(repeating: "a", count: 64)), mintUrl: mint,
                direction: .incoming, amount: Amount(value: 2_100), fee: Amount(value: 0),
                unit: .sat, ys: [], timestamp: 2, memo: nil, metadata: [:], quoteId: quote.id,
                paymentRequest: quote.request, paymentProof: nil, paymentMethod: .onchain,
                sagaId: sagaID, status: status
            ) }
            try await db.addTransaction(transaction: transaction(status: .pending))
            await service.loadTransactions(includeRemoteObservations: false)
            XCTAssertEqual(service.transactions.count, 1)
            let id = try XCTUnwrap(service.transactions.first?.id)
            XCTAssertNotEqual(id, quote.id)
            XCTAssertEqual(service.transactions.liveDetail(openId: pending.id, openQuoteId: quote.id)?.id, id)

            try await db.addTransaction(transaction: transaction(status: .completed))
            try await db.addMintQuote(quote: MintQuote(
                id: quote.id, amount: nil, unit: .sat, request: quote.request,
                state: .issued, expiry: 1, mintUrl: mint,
                amountIssued: Amount(value: 2_100), amountPaid: Amount(value: 2_100), updatedAt: 2,
                estimatedBlocks: nil, paymentMethod: .onchain, secretKey: nil, usedByOperation: nil, version: quote.version
            ))
            await service.loadTransactions(includeRemoteObservations: false)
            XCTAssertEqual(service.transactions.count, 1)
            XCTAssertEqual(service.transactions.first?.id, id)
            XCTAssertEqual(service.transactions.first?.status, .completed)
        }
    }
}

private final class FailingAccountHistoryDatabase: WalletSqliteDatabase, @unchecked Sendable {
    private var retainedDatabase: WalletSqliteDatabase?
    var failAccountReads = false
    var failDiscoveryReads = false
    var failQuoteReads = false
    required init(unsafeFromHandle handle: UInt64) { super.init(unsafeFromHandle: handle) }
    convenience init(database: WalletSqliteDatabase) {
        self.init(unsafeFromHandle: database.uniffiCloneHandle())
        retainedDatabase = database
    }
    override func listTransactions(mintUrl: MintUrl?, direction: TransactionDirection?, unit: CurrencyUnit?) async throws -> [Cdk.Transaction] {
        if failAccountReads && unit != nil { throw NSError(domain: "TestStorage", code: 1) }
        return try await super.listTransactions(mintUrl: mintUrl, direction: direction, unit: unit)
    }
    override func getMintQuotes() async throws -> [MintQuote] {
        if failDiscoveryReads { throw NSError(domain: "TestStorage", code: 2) }
        return try await super.getMintQuotes()
    }
    override func getUnissuedMintQuotes() async throws -> [MintQuote] {
        if failQuoteReads { throw NSError(domain: "TestStorage", code: 3) }
        return try await super.getUnissuedMintQuotes()
    }
}
