import Cdk
import XCTest
@testable import CashuWallet

final class MintQuoteDomainTests: XCTestCase {
    func testStoredReceiveLookupPreservesArtifactAndFallsBackWithoutAQuote() async {
        let quote = MintQuoteInfo(
            id: "saved", request: "lno1saved", amount: 123, isAmountless: false,
            paymentMethod: .bolt12, state: .pending, expiry: nil, createdAt: nil,
            unit: "eur", mintURL: "https://original-mint.example", description: "Coffee"
        )
        var lookups: [String] = []
        func load(_ id: String) async -> MintQuoteInfo? {
            lookups.append(id)
            return id == quote.id ? quote : nil
        }
        let noID = await MintQuoteDomain.resolveStoredReceiveQuote(quoteID: nil, load: load)
        XCTAssertNil(noID)
        XCTAssertTrue(lookups.isEmpty)
        let missing = await MintQuoteDomain.resolveStoredReceiveQuote(quoteID: "missing", load: load)
        XCTAssertNil(missing)
        let reopened = await MintQuoteDomain.resolveStoredReceiveQuote(quoteID: quote.id, load: load)
        XCTAssertEqual(lookups, ["missing", "saved"])
        XCTAssertEqual(reopened?.request, quote.request)
        XCTAssertEqual(reopened?.mintURL, quote.mintURL)
        XCTAssertEqual(reopened?.unit, "eur")
        XCTAssertEqual(reopened?.amount, 123)
        XCTAssertEqual(reopened?.description, "Coffee")
    }

    func testStoredReceiveRechecksSettlementButKeepsReusableOffers() async {
        for method in [PaymentMethodKind.bolt11, .bolt12, .onchain] {
            var quote = MintQuoteInfo(
                id: "selected", request: "saved-request", amount: 21, isAmountless: false,
                paymentMethod: method, state: .pending, expiry: nil, createdAt: nil
            )
            let unpaid = await MintQuoteDomain.resolveStoredReceiveQuote(quoteID: quote.id) { _ in quote }
            XCTAssertNotNil(unpaid)
            // A payment may settle after the History row was selected.
            quote.state = .issued
            quote.amountPaid = 21
            quote.amountIssued = 21
            let settled = await MintQuoteDomain.resolveStoredReceiveQuote(quoteID: quote.id) { _ in quote }
            XCTAssertEqual(settled != nil, method == .bolt12)
        }
    }

    func testNormalizesDescriptionForPayerDisplay() {
        XCTAssertEqual(MintQuoteDomain.normalizedOfferDescription("  Coffee tips\r\n\tThank you ☕  "), "Coffee tips Thank you ☕")
        XCTAssertEqual(MintQuoteDomain.normalizedOfferDescription("Cof\u{0}fee"), "Coffee")
        XCTAssertNil(MintQuoteDomain.normalizedOfferDescription(" \r\n\t "))
        XCTAssertEqual(MintQuoteDomain.normalizedOfferDescription(String(repeating: "a", count: 650)), String(repeating: "a", count: 640))
    }

    func testBolt12MintDescriptionAdvertisementFailsClosed() {
        XCTAssertFalse(MintQuoteDomain.reportsBolt12MintDescription(methods: []))
        XCTAssertFalse(MintQuoteDomain.reportsBolt12MintDescription(methods: [
            (method: .bolt12, description: nil),
        ]))
        XCTAssertFalse(MintQuoteDomain.reportsBolt12MintDescription(methods: [
            (method: .bolt12, description: false),
        ]))
        XCTAssertFalse(MintQuoteDomain.reportsBolt12MintDescription(methods: [
            (method: .bolt11, description: true),
        ]))
        XCTAssertTrue(MintQuoteDomain.reportsBolt12MintDescription(methods: [
            (method: .bolt11, description: false),
            (method: .bolt12, description: true),
        ]))
        // Any bolt12 unit advertising true is enough.
        XCTAssertTrue(MintQuoteDomain.reportsBolt12MintDescription(methods: [
            (method: .bolt12, description: false),
            (method: .bolt12, description: true),
        ]))
    }

    func testReusableOfferReuseMatchesDescriptionExactly() {
        XCTAssertTrue(MintQuoteDomain.isReusableAmountlessOffer(
            paymentMethod: .bolt12, isAmountless: true,
            quoteMintUrl: "https://mint.example", quoteUnit: "sat",
            activeMintUrl: "https://mint.example", unit: "sat",
            storedMemo: nil, description: nil
        ))
        XCTAssertTrue(MintQuoteDomain.isReusableAmountlessOffer(
            paymentMethod: .bolt12, isAmountless: true,
            quoteMintUrl: "https://mint.example", quoteUnit: "sat",
            activeMintUrl: "https://mint.example", unit: "sat",
            storedMemo: "Coffee tips", description: "Coffee tips"
        ))
        XCTAssertFalse(MintQuoteDomain.isReusableAmountlessOffer(
            paymentMethod: .bolt12, isAmountless: true,
            quoteMintUrl: "https://mint.example", quoteUnit: "sat",
            activeMintUrl: "https://mint.example", unit: "sat",
            storedMemo: "Coffee tips", description: "Different memo"
        ))
        XCTAssertFalse(MintQuoteDomain.isReusableAmountlessOffer(
            paymentMethod: .bolt12, isAmountless: true,
            quoteMintUrl: "https://mint.example", quoteUnit: "sat",
            activeMintUrl: "https://mint.example", unit: "sat",
            storedMemo: "Coffee tips", description: nil
        ))
    }

    func testReusableOfferMatchRejectsOtherRailsAndFixedAmounts() {
        XCTAssertFalse(MintQuoteDomain.isReusableAmountlessOffer(
            paymentMethod: .bolt11, isAmountless: true,
            quoteMintUrl: "https://mint.example", quoteUnit: "sat",
            activeMintUrl: "https://mint.example", unit: "sat",
            storedMemo: nil, description: nil
        ))
        XCTAssertFalse(MintQuoteDomain.isReusableAmountlessOffer(
            paymentMethod: .bolt12, isAmountless: false,
            quoteMintUrl: "https://mint.example", quoteUnit: "sat",
            activeMintUrl: "https://mint.example", unit: "sat",
            storedMemo: nil, description: nil
        ))
        XCTAssertFalse(MintQuoteDomain.isReusableAmountlessOffer(
            paymentMethod: nil, isAmountless: true,
            quoteMintUrl: "https://mint.example", quoteUnit: "sat",
            activeMintUrl: "https://mint.example", unit: "sat",
            storedMemo: nil, description: nil
        ))
    }

    func testReusableOfferMatchRejectsWrongMintOrUnit() {
        XCTAssertFalse(MintQuoteDomain.isReusableAmountlessOffer(
            paymentMethod: .bolt12, isAmountless: true,
            quoteMintUrl: "https://other.example", quoteUnit: "sat",
            activeMintUrl: "https://mint.example", unit: "sat",
            storedMemo: nil, description: nil
        ))
        // Unit match is case-insensitive (Android parity).
        XCTAssertTrue(MintQuoteDomain.isReusableAmountlessOffer(
            paymentMethod: .bolt12, isAmountless: true,
            quoteMintUrl: "https://mint.example", quoteUnit: "SAT",
            activeMintUrl: "https://mint.example", unit: "sat",
            storedMemo: nil, description: nil
        ))
        XCTAssertFalse(MintQuoteDomain.isReusableAmountlessOffer(
            paymentMethod: .bolt12, isAmountless: true,
            quoteMintUrl: "https://mint.example", quoteUnit: "usd",
            activeMintUrl: "https://mint.example", unit: "sat",
            storedMemo: nil, description: nil
        ))
    }

    private func onchainQuote(
        _ id: String,
        mint: String = "https://mint.example.com",
        paid: UInt64 = 0,
        issued: UInt64 = 0,
        expiry: UInt64 = 0,
        updatedAt: UInt64,
        method: PaymentMethod = .onchain
    ) -> MintQuote {
        MintQuote(
            id: id, amount: nil, unit: .sat, request: "bc1q\(id)", state: .unpaid, expiry: expiry,
            mintUrl: MintUrl(url: mint), amountIssued: Amount(value: issued), amountPaid: Amount(value: paid),
            updatedAt: updatedAt, estimatedBlocks: nil, paymentMethod: method, secretKey: nil,
            usedByOperation: nil, version: 0
        )
    }

    func testReusableOnchainAddressIsTheNewestUnfundedLiveOne() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let quotes = [
            onchainQuote("oldest", updatedAt: 100),
            onchainQuote("abandoned-then-new", updatedAt: 300),
            onchainQuote("funded", paid: 21, updatedAt: 400),
            onchainQuote("minted", paid: 21, issued: 21, updatedAt: 500),
            onchainQuote("expired", expiry: 999_999, updatedAt: 600),
            onchainQuote("other-mint", mint: "https://other.example", updatedAt: 700),
            onchainQuote("lightning", updatedAt: 800, method: .bolt11),
        ]
        XCTAssertEqual(
            MintQuoteDomain.reusableOnchainAddress(in: quotes, mintURL: "HTTPS://MINT.EXAMPLE.COM/", now: now)?.id,
            "abandoned-then-new"
        )
        XCTAssertEqual(
            MintQuoteDomain.reusableOnchainAddress(
                in: [onchainQuote("live", expiry: 1_000_001, updatedAt: 1)],
                mintURL: "https://mint.example.com",
                now: now
            )?.id,
            "live"
        )
        XCTAssertNil(MintQuoteDomain.reusableOnchainAddress(
            in: [onchainQuote("funded", paid: 1, updatedAt: 1)], mintURL: "https://mint.example.com", now: now
        ))
    }
}

@MainActor
final class LiveBolt12DescriptionTests: XCTestCase {
    func testLiveOffersEncodeDescriptionsAndPreserveAmounts() async throws {
        let url = try XCTUnwrap(ProcessInfo.processInfo.environment["BOLT12_DESCRIPTION_MINT_URL"],
                                "Set BOLT12_DESCRIPTION_MINT_URL to enable this test")
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        weak var releasedDatabase: LifecycleSafeWalletDatabase?
        do {
            let database = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("wallet.sqlite").path)
            releasedDatabase = database
            let repository = try WalletRepository(mnemonic: generateMnemonic(), store: customWalletStore(db: database))
            try await repository.createWallet(mintUrl: MintUrl(url: url), unit: .sat, targetProofCount: nil)
            let mint = MintInfo(url: url, name: "Live test mint", isActive: true, balance: 0)
            let service = LightningService(walletRepository: { repository }, walletDatabase: { database }, getActiveMint: { mint })
            let plain = try await service.createMintQuote(amount: nil, method: .bolt12)
            XCTAssertNil(try decodeInvoice(invoiceStr: plain.request).amountMsat)

            for description in ["Coffee tips\nThank you ☕", "Updated coffee note", String(repeating: "a", count: 640)] {
                let quote = try await service.createMintQuote(amount: nil, method: .bolt12, description: description)
                let decoded = try decodeInvoice(invoiceStr: quote.request)
                XCTAssertEqual(decoded.description, description.replacingOccurrences(of: "\n", with: " "))
                XCTAssertNil(decoded.amountMsat)
                XCTAssertNotEqual(quote.request, plain.request)
            }
            for description in ["Fixed amount coffee", "Edited fixed amount", nil] {
                let quote = try await service.createMintQuote(amount: 21, method: .bolt12, description: description)
                let decoded = try decodeInvoice(invoiceStr: quote.request)
                XCTAssertEqual(decoded.amountMsat, 21_000)
                if let description { XCTAssertEqual(decoded.description, description.replacingOccurrences(of: "\n", with: " ")) }
                else { XCTAssertEqual(decoded.description, try decodeInvoice(invoiceStr: plain.request).description) }
            }
        }
        // Let native writer teardown finish in this test, not in the next one.
        let deadline = Date().addingTimeInterval(3)
        while releasedDatabase != nil, Date() < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }
        XCTAssertNil(releasedDatabase)
    }

    override func setUpWithError() throws {
        try XCTSkipIf(ProcessInfo.processInfo.environment["BOLT12_DESCRIPTION_MINT_URL"] == nil,
                      "Opt-in live mint quote test")
    }
}

/// Paying a BOLT11 invoice that leaves the amount to the payer: the mint must
/// advertise NUT-05 `amountless`, and the entered amount rides CDK's
/// `MeltOptions.amountless`, exactly like an amountless BOLT12 offer.
final class AmountlessBolt11MeltTests: XCTestCase {
    /// This file imports Cdk, which has its own `MintInfo`.
    private typealias MintInfo = CashuWallet.MintInfo

    private func mint(
        _ name: String,
        methods: [PaymentMethodKind] = [.bolt11],
        amountless: Bool?
    ) -> MintInfo {
        var mint = MintInfo(
            url: "https://\(name).example",
            name: name,
            description: nil,
            isActive: true,
            balance: 1_000
        )
        mint.supportedMeltMethods = methods
        mint.supportsAmountlessBolt11Melt = amountless
        return mint
    }

    func testAmountlessBolt11MeltFailsClosedUnlessAdvertisedTrueForSat() {
        XCTAssertFalse(MintInfo.reportsAmountlessBolt11Melt(methods: []))
        XCTAssertFalse(MintInfo.reportsAmountlessBolt11Melt(methods: [
            (method: .bolt11, isSatUnit: true, amountless: nil),
        ]))
        XCTAssertFalse(MintInfo.reportsAmountlessBolt11Melt(methods: [
            (method: .bolt11, isSatUnit: true, amountless: false),
        ]))
        XCTAssertFalse(MintInfo.reportsAmountlessBolt11Melt(methods: [
            (method: .bolt11, isSatUnit: false, amountless: true),
        ]))
        XCTAssertFalse(MintInfo.reportsAmountlessBolt11Melt(methods: [
            (method: .bolt12, isSatUnit: true, amountless: true),
        ]))
        XCTAssertTrue(MintInfo.reportsAmountlessBolt11Melt(methods: [
            (method: .bolt12, isSatUnit: true, amountless: nil),
            (method: .bolt11, isSatUnit: true, amountless: true),
        ]))
    }

    func testCanMeltGatesOnlyAmountlessBolt11OnTheAdvertisement() {
        XCTAssertTrue(mint("yes", amountless: true).canMelt(.bolt11, amountless: true))
        XCTAssertFalse(mint("no", amountless: false).canMelt(.bolt11, amountless: true))
        // Never fetched since this landed: stays eligible and lets the mint decide.
        XCTAssertTrue(mint("unknown", amountless: nil).canMelt(.bolt11, amountless: true))

        // Invoices that carry an amount, and BOLT12 offers, ignore the flag.
        XCTAssertTrue(mint("no", amountless: false).canMelt(.bolt11))
        XCTAssertTrue(mint("offers", methods: [.bolt12], amountless: false).canMelt(.bolt12, amountless: true))

        // The method itself is still required.
        XCTAssertFalse(mint("offers", methods: [.bolt12], amountless: true).canMelt(.bolt11, amountless: true))
    }

    func testRecordsPersistedBeforeTheFlagDecodeAsUnknown() throws {
        let legacy = Data(#"{"url":"https://legacy.example","name":"Legacy","isActive":true,"balance":0}"#.utf8)
        XCTAssertNil(try JSONDecoder().decode(MintInfo.self, from: legacy).supportsAmountlessBolt11Melt)

        let unsupported = mint("no", amountless: false)
        let roundTripped = try JSONDecoder().decode(MintInfo.self, from: JSONEncoder().encode(unsupported))
        XCTAssertEqual(roundTripped.supportsAmountlessBolt11Melt, false)
    }

    func testAmountlessCautionOnlyWhenNoHeldMintCanPay() {
        let amountless = PaymentRequestDecodeResult.bolt11(amountSats: nil, description: nil)

        XCTAssertTrue(amountless.isAmountlessBolt11)
        XCTAssertNil(amountless.amountlessMeltCaution(payableBy: [
            mint("no", amountless: false),
            mint("yes", amountless: true),
        ]))
        XCTAssertEqual(
            amountless.amountlessMeltCaution(payableBy: [mint("no", amountless: false)]),
            "None of your mints can pay invoices without an amount. Ask for one with the amount set."
        )

        let fixed = PaymentRequestDecodeResult.bolt11(amountSats: 21, description: nil)
        XCTAssertFalse(fixed.isAmountlessBolt11)
        XCTAssertNil(fixed.amountlessMeltCaution(payableBy: [mint("no", amountless: false)]))

        let offer = PaymentRequestDecodeResult.bolt12(amountSats: nil, description: nil)
        XCTAssertFalse(offer.isAmountlessBolt11)
        XCTAssertNil(offer.amountlessMeltCaution(payableBy: []))
    }

    func testAmountlessBolt11MeltUsesEnteredAmountInMillisatoshis() throws {
        XCTAssertEqual(
            try meltOptionsForLightningRequest(requestAmountMsat: nil, amountSats: 21),
            .amountless(amountMsat: Amount(value: 21_000))
        )
        XCTAssertNil(try meltOptionsForLightningRequest(requestAmountMsat: 2_500_000, amountSats: 21))
        XCTAssertThrowsError(try meltOptionsForLightningRequest(requestAmountMsat: nil, amountSats: nil))
    }
}
