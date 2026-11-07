import Cdk
import XCTest
@testable import CashuWallet

private final class PreparationFailureDatabase: WalletSqliteDatabase, @unchecked Sendable {
    private let lock = NSLock()
    private var rejectMelt = false
    func rejectNextMelt() { lock.withLock { rejectMelt = true } }
    override func addSaga(sagaJson: String) async throws {
        let json = try JSONSerialization.jsonObject(with: Data(sagaJson.utf8)) as? [String: Any]
        let reject = lock.withLock {
            guard rejectMelt, json?["kind"] as? String == "melt" else { return false }
            rejectMelt = false
            return true
        }
        if reject { throw FfiError.Internal(errorMessage: "Test persistence failure") }
        try await super.addSaga(sagaJson: sagaJson)
    }
}

/// The app's Lightning service moving ecash between two real local mints.
/// Skipped without the payment fixture, like the CDK-level cases it builds on.
final class CrossMintServiceIntegrationTests: PaymentFixtureTestCase {

    @MainActor
    func testFailedPreparationReturnsFundsWithoutRestart() async throws {
        let (source, db, repo, _, _) = try await preparationWallet()
        let request = try await invoice(amount: 100000)
        let quote = try await source.meltQuote(method: .bolt11, request: request, options: nil, extra: nil)
        let service = LightningService(walletRepository: { repo }, walletDatabase: { db }, getActiveMint: { nil })
        db.rejectNextMelt()
        do {
            _ = try await service.meltTokens(quoteId: quote.id, mintUrl: source.mintUrl().url)
            XCTFail("Expected the unpaid payment to be returned")
        } catch MeltPaymentRecoveryError.compensated { }
        let available = try await balance(source)
        let held = try await source.totalReservedBalance().value
        XCTAssertEqual(available, 131072)
        XCTAssertEqual(held, 0)
    }

    @MainActor
    private func preparationWallet() async throws -> (Wallet, PreparationFailureDatabase, WalletRepository, String, String) {
        let seed = try generateMnemonic()
        let path = NSTemporaryDirectory() + "held-funds-\(UUID().uuidString).sqlite"
        let db = try PreparationFailureDatabase(filePath: path)
        let repo = try WalletRepository(mnemonic: seed, store: customWalletStore(db: db))
        stores.append((repo, path, seed))
        databaseHandles.append(db)
        let url = MintUrl(url: mintURL("controlled"))
        try await repo.createWallet(mintUrl: url, unit: .sat, targetProofCount: nil)
        let source = try await repo.getWallet(mintUrl: url, unit: .sat)
        walletHandles.append(source)
        try await fund(source, amount: 131072, controlled: "controlled")
        return (source, db, repo, seed, path)
    }

    @MainActor
    func testHeldFundsRecoverAfterReopenAndCanBeRedeemed() async throws {
        let (source, db, _, seed, path) = try await preparationWallet()
        let url = MintUrl(url: mintURL("controlled"))
        let request = try await invoice(amount: 100000)
        let quote = try await source.meltQuote(method: .bolt11, request: request, options: nil, extra: nil)
        db.rejectNextMelt()
        do {
            _ = try await source.prepareMelt(quoteId: quote.id)
            XCTFail("Expected preparation failure")
        } catch { }
        let held = try await source.totalReservedBalance().value
        XCTAssertEqual(held, 101000)

        let reopenedDB = try WalletSqliteDatabase(filePath: path)
        let reopenedRepo = try WalletRepository(mnemonic: seed, store: customWalletStore(db: reopenedDB))
        reopened.append(reopenedRepo)
        databaseHandles.append(reopenedDB)
        let reopenedSource = try await reopenedRepo.getWallet(mintUrl: url, unit: .sat)
        walletHandles.append(reopenedSource)
        let service = LightningService(walletRepository: { reopenedRepo }, walletDatabase: { reopenedDB }, getActiveMint: { nil })
        await service.captureAbandonedReservations(database: reopenedDB)
        _ = try await reopenedSource.recoverIncompleteSagas()
        let recovered = try await service.recoverAbandonedReservations(wallet: reopenedSource)
        XCTAssertTrue(recovered)
        let repeated = try await service.recoverAbandonedReservations(wallet: reopenedSource)
        XCTAssertFalse(repeated)
        let available = try await balance(reopenedSource)
        XCTAssertEqual(available, 131072)
        let receiver = try await makeWallet("controlled")
        let token = try await send(reopenedSource, amount: available).confirm(memo: nil)
        let redeemed = try await receive(receiver, token: token)
        XCTAssertEqual(redeemed, 131072)
        let senderBalance = try await balance(reopenedSource)
        XCTAssertEqual(senderBalance, 0)
    }

    @MainActor
    func testMaxQuoteThenSkipSwapMeltSettlesAndKeepsTheChange() async throws {
        let source = try await makeWallet("fees")
        let destination = try await makeWallet("controlled", inSameRepositoryAs: source)
        try await fund(source, controlled: "fees")
        let service = try service(holding: source)

        let quotes = try await service.createMaxCrossMintQuotes(
            sourceMintURL: mintURL("fees"),
            destinationMintURL: mintURL("controlled")
        )
        let amount = try XCTUnwrap(quotes.mintQuote.amount)
        XCTAssertEqual(quotes.meltQuote.amount, amount)
        XCTAssertEqual(
            MintURLIdentity.normalized(quotes.meltQuote.mintUrl),
            MintURLIdentity.normalized(mintURL("fees"))
        )
        XCTAssertGreaterThan(quotes.inputFee, 0, "1000 ppk charges one unit per input proof")
        XCTAssertEqual(amount + quotes.meltQuote.feeReserve + quotes.inputFee, 100)

        try await arm("/v1/melt/quote/bolt11/", action: "delay", method: "GET")
        let confirmation = try await service.meltTokens(
            quoteId: quotes.meltQuote.id,
            mintUrl: mintURL("fees"),
            selection: .allUnspentSkippingSwap(expectedInputFee: quotes.inputFee)
        )

        XCTAssertEqual(confirmation.result.settlement, .settled)
        XCTAssertEqual(confirmation.result.amount, amount)
        let left = try await balance(source)
        XCTAssertEqual(left + amount + (try XCTUnwrap(confirmation.result.feePaid)), 100)
        _ = try await call(root + "/pay/controlled", method: "POST", body: ["invoice": quotes.mintQuote.request])
        _ = try await awaitPaid(destination, id: quotes.mintQuote.id)
        let minted = try await service.mintTokens(quoteId: quotes.mintQuote.id)
        XCTAssertEqual(minted, amount)
    }

    @MainActor
    func testOrdinaryMeltIncludesPreparatorySwapFeeInActualDebit() async throws {
        let source = try await makeWallet("fees")
        try await fund(source, controlled: "fees")
        let service = try service(holding: source)
        let request = try await invoice(amount: 21)
        let quote = try await source.meltQuote(method: .bolt11, request: request, options: nil, extra: nil)
        // Inspect and cancel a preparation to establish that this case charges
        // a swap fee, rather than accidentally exercising the skip-swap path.
        let prepared = try await source.prepareMelt(quoteId: quote.id)
        let swapFee = prepared.swapFee().value
        XCTAssertGreaterThan(swapFee, 0)
        try await prepared.cancel()
        try await arm("/v1/melt/quote/bolt11/", action: "delay", method: "GET")

        let confirmation = try await service.meltTokens(quoteId: quote.id, mintUrl: mintURL("fees"))
        XCTAssertEqual(confirmation.result.settlement, .settled)
        let fee = try XCTUnwrap(confirmation.result.feePaid)
        let left = try await balance(source)
        XCTAssertEqual(left + confirmation.result.amount + fee, 100)
        let transaction = try await source.listTransactions(direction: .outgoing).last { $0.quoteId == quote.id }
        XCTAssertEqual(fee, try XCTUnwrap(transaction).fee.value + swapFee)
    }

    /// A proof set that no longer costs what the quote was sized for must not
    /// be melted: the amount would not fit. The reservation is released and the
    /// same quote still works once the fee matches.
    @MainActor
    func testChangedInputFeeCancelsBeforeAnythingIsSpent() async throws {
        let source = try await makeWallet("fees")
        _ = try await makeWallet("controlled", inSameRepositoryAs: source)
        try await fund(source, controlled: "fees")
        let service = try service(holding: source)
        let quotes = try await service.createMaxCrossMintQuotes(
            sourceMintURL: mintURL("fees"),
            destinationMintURL: mintURL("controlled")
        )

        do {
            _ = try await service.meltTokens(
                quoteId: quotes.meltQuote.id,
                mintUrl: mintURL("fees"),
                selection: .allUnspentSkippingSwap(expectedInputFee: quotes.inputFee + 1)
            )
            XCTFail("A mismatched input fee must not melt")
        } catch let error as MeltInputFeeChanged {
            XCTAssertEqual(error, MeltInputFeeChanged(expected: quotes.inputFee + 1, actual: quotes.inputFee))
        }
        let spendable = try await balance(source)
        let reserved = try await source.totalReservedBalance()
        XCTAssertEqual(spendable, 100)
        XCTAssertEqual(reserved.value, 0)

        try await arm("/v1/melt/quote/bolt11/", action: "delay", method: "GET")
        let confirmation = try await service.meltTokens(
            quoteId: quotes.meltQuote.id,
            mintUrl: mintURL("fees"),
            selection: .allUnspentSkippingSwap(expectedInputFee: quotes.inputFee)
        )
        XCTAssertEqual(confirmation.result.settlement, .settled)
    }

    @MainActor
    func testUnusedQuotesAreRemovedOnlyBeforeTheMeltStarts() async throws {
        let source = try await makeWallet("fees")
        _ = try await makeWallet("controlled", inSameRepositoryAs: source)
        try await fund(source, controlled: "fees")
        let store = try database(of: source)
        let service = try service(holding: source)

        let abandoned = try await service.createMaxCrossMintQuotes(
            sourceMintURL: mintURL("fees"),
            destinationMintURL: mintURL("controlled")
        )
        let removed = await service.removeUnusedQuotes(
            mintQuoteID: abandoned.mintQuote.id,
            meltQuoteID: abandoned.meltQuote.id
        )
        XCTAssertTrue(removed)
        let afterRemoval = try await store.getUnissuedMintQuotes()
        XCTAssertTrue(afterRemoval.isEmpty)

        let started = try await service.createMaxCrossMintQuotes(
            sourceMintURL: mintURL("fees"),
            destinationMintURL: mintURL("controlled")
        )
        try await arm("/v1/melt/quote/bolt11/", action: "delay", method: "GET")
        _ = try await service.meltTokens(
            quoteId: started.meltQuote.id,
            mintUrl: mintURL("fees"),
            selection: .allUnspentSkippingSwap(expectedInputFee: started.inputFee)
        )
        let removedAfterMelt = await service.removeUnusedQuotes(
            mintQuoteID: started.mintQuote.id,
            meltQuoteID: started.meltQuote.id
        )
        // The destination quote is now the only handle on the paid invoice.
        XCTAssertFalse(removedAfterMelt)
        let kept = try await store.getUnissuedMintQuotes()
        XCTAssertEqual(kept.map(\.id), [started.mintQuote.id])
    }

    @MainActor
    private func service(holding wallet: Wallet) throws -> LightningService {
        let index = try XCTUnwrap(storeByWallet[ObjectIdentifier(wallet)])
        let repository = stores[index].0
        let store = try database(of: wallet)
        return LightningService(
            walletRepository: { repository },
            walletDatabase: { store },
            getActiveMint: { nil }
        )
    }
}
