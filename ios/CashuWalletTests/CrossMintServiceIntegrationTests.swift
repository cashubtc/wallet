import Cdk
import XCTest
@testable import CashuWallet

/// The app's Lightning service moving ecash between two real local mints.
/// Skipped without the payment fixture, like the CDK-level cases it builds on.
final class CrossMintServiceIntegrationTests: PaymentFixtureTestCase {

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
        XCTAssertEqual(left + amount + confirmation.result.feePaid, 100)
        _ = try await call(root + "/pay/controlled", method: "POST", body: ["invoice": quotes.mintQuote.request])
        _ = try await awaitPaid(destination, id: quotes.mintQuote.id)
        let minted = try await service.mintTokens(quoteId: quotes.mintQuote.id)
        XCTAssertEqual(minted, amount)
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
