import Cdk
import XCTest
@testable import CashuWallet

@MainActor
final class CustomPaymentMethodTests: XCTestCase {
    private let branch = PaymentMethodKind(rawValue: "branch")!

    func testIdentitiesRoundTripAsLegacyStringsAndRejectInvalidEndpoints() throws {
        for raw in ["bolt11", "bolt12", "onchain", "branch", "bank_transfer", "test-v2"] {
            let method = try XCTUnwrap(PaymentMethodKind(rawValue: raw))
            XCTAssertEqual(String(decoding: try JSONEncoder().encode(method), as: UTF8.self), "\"\(raw)\"")
            XCTAssertEqual(try JSONDecoder().decode(PaymentMethodKind.self, from: Data("\"\(raw)\"".utf8)), method)
            XCTAssertEqual(PaymentMethodKind.from(method.cdkMethod), method)
        }
        for raw in ["", "../branch", "branch/path", "branch?unit=sat", "Branch", "branch\n", String(repeating: "a", count: 33)] {
            XCTAssertNil(PaymentMethodKind(rawValue: raw), raw)
        }
        XCTAssertEqual(PaymentMethodKind(rawValue: "_")?.displayName, "_")
        XCTAssertEqual(PaymentMethodKind(rawValue: "bank_transfer")?.displayName, "Bank Transfer")
        XCTAssertEqual(PaymentMethodKind(rawValue: "BOLT11"), .bolt11)
    }

    func testCustomUnitsKeepWholeAmountsAndRejectFractionalOrMalformedInput() {
        let bux = CurrencyRegistry.currency(forMintUnit: "bux")
        XCTAssertEqual(bux.decimals, 0)
        XCTAssertEqual(CurrencyAmount(value: 100, currency: bux).formatted(), "100 BUX")
        XCTAssertEqual(AmountFormatter.validatedEntryBaseUnits(raw: "100", decimals: bux.decimals), 100)
        for raw in ["1.5", "1.0", "-1", "1e2", "1,000", " 10", "10\n", "9999999999999"] {
            XCTAssertNil(AmountFormatter.validatedEntryBaseUnits(raw: raw, decimals: bux.decimals), raw)
        }
        XCTAssertEqual(AmountFormatter.validatedEntryBaseUnits(raw: "1.50", decimals: CurrencyRegistry.currency(forMintUnit: "usd").decimals), 150)
    }

    func testCapabilitiesKeepUnitLimitsNamesAndReportedEmptyAcrossPersistence() throws {
        var settings = AdvertisedPaymentMethod(method: branch, unit: "bux", name: "Local cash", minAmount: 10, maxAmount: 100)
        var mint = MintInfo(url: "https://mint.example", name: "Test", isActive: true, balance: 0)
        mint.mintMethodSettings = [settings]
        mint.meltMethodSettings = []
        let restored = try JSONDecoder().decode(CashuWallet.MintInfo.self, from: JSONEncoder().encode(mint))
        XCTAssertEqual(restored.mintMethods(for: "bux"), [branch])
        XCTAssertEqual(restored.mintMethods(for: "sat"), [])
        XCTAssertEqual(restored.meltMethodSettings, [])
        XCTAssertEqual(restored.methodName(branch, unit: "bux"), "Local cash")
        settings.name = "Bad\u{0}name"
        XCTAssertEqual(settings.displayName, "Branch")
        XCTAssertFalse(settings.accepts(0)); XCTAssertFalse(settings.accepts(9))
        XCTAssertTrue(settings.accepts(10)); XCTAssertTrue(settings.accepts(100)); XCTAssertFalse(settings.accepts(101))
        let legacy = try JSONDecoder().decode(CashuWallet.MintInfo.self, from: Data(#"{"url":"https://legacy.example"}"#.utf8))
        XCTAssertEqual(legacy.mintMethods(for: "sat"), [.bolt11])
    }

    func testPartialIssuanceKeepsMonitoringUntilFullAmountEvenAfterExpiry() async {
        let partial = MintQuoteInfo(id: "quote", request: "opaque request", amount: 100, isAmountless: false,
            paymentMethod: branch, state: .issued, expiry: 1, createdAt: nil, unit: "bux", amountPaid: 40, amountIssued: 40)
        XCTAssertFalse(partial.hasSettledPayment)
        XCTAssertTrue(partial.shouldMonitorAfterExpiry)
        XCTAssertFalse(MintQuoteSchedulePolicy.observed(previous: nil, quote: partial, now: Date()).isComplete)
        var full = partial
        full.amountPaid = 100
        full.amountIssued = 100
        var checks = 0
        await FocusedMintQuoteMonitor().monitor(quoteID: "quote", refresh: { _ in
            checks += 1
            return checks == 1 ? partial : full
        }, sleep: { _ in })
        XCTAssertEqual(checks, 2)
        XCTAssertTrue(MintQuoteSchedulePolicy.observed(previous: nil, quote: full, now: Date()).isComplete)

        var transaction = WalletTransaction(id: "quote", amount: 100, type: .incoming, kind: .custom,
            date: Date(), memo: nil, status: .pending, invoice: "opaque request", quoteId: "quote")
        transaction.paymentMethod = branch
        transaction.unit = "bux"
        XCTAssertEqual(transaction.mintQuoteIdForStatusRefresh, "quote")
        XCTAssertTrue(transaction.hasActionablePaymentCode)
        XCTAssertEqual(transaction.displayTitle, "Branch pending")
        transaction.status = .failed
        XCTAssertEqual(transaction.displayTitle, "Branch failed")
    }

    func testExpiryOnlyStopsUnpaidLightningMonitoring() {
        for method in [PaymentMethodKind.bolt11, .onchain, branch] {
            var quote = MintQuoteInfo(id: "quote", request: "request", amount: 100, isAmountless: false,
                paymentMethod: method, state: .pending, expiry: 1, createdAt: nil)
            XCTAssertTrue(quote.isExpired)
            XCTAssertEqual(quote.shouldMonitorAfterExpiry, method != .bolt11)
            quote.amountPaid = 40
            XCTAssertTrue(quote.shouldMonitorAfterExpiry)
        }
    }

    func testCustomInstallmentsKeepOnlyTheRemainingAmountPendingInHistory() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let database = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("wallet.sqlite").path)
        func storedQuote(issued: UInt64, version: UInt32 = 0) -> Cdk.MintQuote { Cdk.MintQuote(
            id: "partial", amount: Amount(value: 100), unit: .custom(unit: "bux"),
            request: "opaque", state: .issued, expiry: 1, mintUrl: MintUrl(url: "https://mint.example"),
            amountIssued: Amount(value: issued), amountPaid: Amount(value: issued), updatedAt: 1, estimatedBlocks: nil,
            paymentMethod: branch.cdkMethod, secretKey: nil, usedByOperation: nil, version: version)
        }
        let quote = storedQuote(issued: 40)
        try await database.addMintQuote(quote: quote)
        let cdkCandidates = try await database.getUnissuedMintQuotes()
        XCTAssertFalse(cdkCandidates.contains { $0.id == quote.id })
        let candidates = try await database.getRecoverableMintQuotes()
        XCTAssertEqual(candidates.map(\.id), [quote.id])
        let repository = try WalletRepository(mnemonic: generateMnemonic(), store: customWalletStore(db: database))
        let service = TransactionService(walletRepository: { repository }, walletDatabase: { database },
            getTrackedMintUrls: { ["https://mint.example"] }, walletStore: WalletStore(storage: InMemoryStorage()))
        var issued: UInt64 = 0
        for (index, installment) in [UInt64(40), 30, 30].enumerated() {
            let sagaID = UUID().uuidString
            func receipt(_ status: Cdk.TransactionStatus) -> Cdk.Transaction { Cdk.Transaction(
                id: TransactionId(hex: String(repeating: String(index + 1), count: 64)), mintUrl: quote.mintUrl,
                direction: .incoming, amount: Amount(value: installment), fee: Amount(value: 0),
                unit: quote.unit, ys: [], timestamp: UInt64(index + 2), memo: nil, metadata: [:],
                quoteId: quote.id, paymentRequest: quote.request, paymentProof: nil,
                paymentMethod: branch.cdkMethod, sagaId: sagaID, status: status
            ) }
            try await database.addTransaction(transaction: receipt(.pending))
            await service.loadTransactions(includeRemoteObservations: false)
            XCTAssertFalse(service.transactions.contains { $0.id == quote.id }, "The in-flight mint already monitors this quote")

            issued += installment
            try await database.addTransaction(transaction: receipt(.completed))
            let stored = try await database.getMintQuote(quoteId: quote.id)
            let version = try XCTUnwrap(stored).version
            try await database.addMintQuote(quote: storedQuote(issued: issued, version: version))
            await service.loadTransactions(includeRemoteObservations: false)
            let pending = service.transactions.first { $0.id == quote.id }
            XCTAssertEqual(service.transactions.filter { $0.status == .completed }.reduce(0) { $0 + $1.amount }, issued)
            XCTAssertEqual(service.transactions.reduce(0) { $0 + $1.amount }, 100)
            if issued < 100 {
                let pending = try XCTUnwrap(pending)
                XCTAssertEqual(pending.amount, 100 - issued)
                XCTAssertEqual(pending.kind, .custom)
                XCTAssertEqual(pending.status, .pending)
                XCTAssertEqual(pending.unit, "bux")
                XCTAssertTrue(pending.monitorsMintQuoteWhileOpen)
            } else {
                XCTAssertNil(pending)
            }
        }
    }

    func testCustomReceiptDetailsKeepMethodUnitAndProof() {
        var transaction = WalletTransaction(id: "payment", amount: 21, type: .outgoing, kind: .custom,
            date: Date(), status: .completed, preimage: "payment-proof", quoteId: "quote")
        transaction.paymentMethod = branch
        transaction.unit = "bux"
        let details = TransactionTechnicalDetails(transaction: transaction)
        let rows = details.sections.flatMap(\.rows)
        XCTAssertEqual(rows.first { $0.label == "Method" }?.value, "Branch")
        XCTAssertEqual(rows.first { $0.label == "Amount" }?.value, "21 BUX")
        XCTAssertEqual(rows.first { $0.label == "Payment Proof" }?.fullValue, "payment-proof")
    }

    /// Opt in with a loopback CDK 0.18 fakewallet mint advertising branch/bux.
    func testLiveCustomDepositRestartAndWithdrawalStayInTheirUnit() async throws {
        guard let url = ProcessInfo.processInfo.environment["CUSTOM_METHOD_MINT_URL"] else {
            throw XCTSkip("Set CUSTOM_METHOD_MINT_URL for the local custom-method mint")
        }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let path = directory.appendingPathComponent("wallet.sqlite").path
        let mnemonic = try generateMnemonic()
        var database: LifecycleSafeWalletDatabase? = try LifecycleSafeWalletDatabase(filePath: path)
        var repository: WalletRepository? = try WalletRepository(mnemonic: mnemonic, store: customWalletStore(db: database!))
        let mints = MintService(walletRepository: { repository }, walletStore: WalletStore(storage: InMemoryStorage()))
        let mint = try await mints.addMint(url: url)
        XCTAssertTrue(mint.mintMethods(for: "bux").contains(branch))
        XCTAssertFalse(mint.mintMethods(for: "sat").contains(branch))
        let service = LightningService(walletRepository: { repository }, walletDatabase: { database },
            getActiveMint: { mint }, getMints: { [mint] })
        let quote = try await service.createMintQuote(amount: 100, method: branch, unit: .custom(unit: "bux"))
        let stored = try await database!.getMintQuote(quoteId: quote.id)
        XCTAssertNotNil(stored?.secretKey, "CDK must persist the NUT-20 signing key")
        for _ in 0..<50 {
            if try await service.checkMintQuote(quoteId: quote.id).amountPaid == 100 { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        repository = nil
        database = nil
        database = try LifecycleSafeWalletDatabase(filePath: path)
        repository = try WalletRepository(mnemonic: mnemonic, store: customWalletStore(db: database!))
        let recoverable = try await database!.getRecoverableMintQuotes()
        XCTAssertTrue(recoverable.contains { $0.id == quote.id })
        let issued = try await service.mintTokens(quoteId: quote.id)
        XCTAssertEqual(issued, 100)
        let verified = try await service.checkMintQuote(quoteId: quote.id)
        XCTAssertTrue(verified.hasSettledPayment)
        XCTAssertEqual(verified.unit, "bux")
        let melt = try await service.createCustomMeltQuote(method: branch, request: "test payout", amount: 21, mintURL: url, unit: "bux")
        XCTAssertEqual(melt.amount, 21)
        XCTAssertEqual(melt.unit, "bux")
        XCTAssertEqual(melt.request, "test payout")
        _ = try await service.meltTokens(quoteId: melt.id, mintUrl: url)
        // Asynchronous acceptance is normal. Restart before recovering the saga.
        repository = nil
        database = nil
        database = try LifecycleSafeWalletDatabase(filePath: path)
        repository = try WalletRepository(mnemonic: mnemonic, store: customWalletStore(db: database!))
        let wallet = try await repository!.getWallet(mintUrl: MintUrl(url: url), unit: .custom(unit: "bux"))
        for _ in 0..<50 {
            _ = try await wallet.recoverIncompleteSagas()
            if try await database!.getMeltQuote(quoteId: melt.id)?.state == .paid { break }
            try await Task.sleep(for: .milliseconds(100))
        }
        let settled = try await database!.getMeltQuote(quoteId: melt.id)
        XCTAssertEqual(settled?.state, .paid)
        let transactions = try await wallet.listTransactions(direction: .outgoing)
        let payment = try XCTUnwrap(transactions.first { $0.quoteId == melt.id })
        let remaining = try await database!.getBalance(mintUrl: MintUrl(url: url), unit: .custom(unit: "bux"), state: [.unspent])
        let sats = try await database!.getBalance(mintUrl: MintUrl(url: url), unit: .sat, state: [.unspent])
        XCTAssertEqual(remaining + payment.amount.value + payment.fee.value, 100)
        XCTAssertEqual(sats, 0)
        repository = nil
        database = nil
    }
}
