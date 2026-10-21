import XCTest
@testable import CashuWallet

/// The home scanner's payload table — must stay in lockstep with Android's
/// `routeScannedPayload` so the two scanners route identically.
final class ScanRouterTests: XCTestCase {

    private let amountlessBolt12Offer =
        "lno1pgqpvggr25nht4nyqrgtnhxltctkdsfrf3myhj008f6fyulf4tplmarx8hxq"

    private func route(_ content: String) -> ScannedPayloadRoute {
        ScanRouter.route(content) { summary, _ in .payWithEcash(summary) }
    }

    /// Scans open Send's own payment steps (Android `onSend` parity), never a
    /// separate payment screen.
    private func sendRoute(
        _ route: ScannedPayloadRoute
    ) -> (destination: SendAmountDestination, request: String, mode: MeltMode, explanation: CashuRequestRouteExplanation?)? {
        guard case .send(let destination, let explanation) = route,
              case .melt(let request, let mode, _) = destination else { return nil }
        return (destination, request, mode, explanation)
    }

    func testBearerTokenRoutesToClaimPage() {
        guard case .receiveToken(let token) = route("  cashuAexampletoken  ") else {
            return XCTFail("expected .receiveToken")
        }
        XCTAssertEqual(token, "cashuAexampletoken")
    }

    func testCashuSchemeTokenRoutesToClaimPage() {
        guard case .receiveToken = route("cashu:cashuBexample") else {
            return XCTFail("expected .receiveToken")
        }
    }

    func testLightningAddressOpensSendAmountEntry() {
        guard let send = sendRoute(route("user@example.com")) else {
            return XCTFail("expected .send")
        }
        XCTAssertEqual(send.request, "user@example.com")
        XCTAssertEqual(send.mode, .lightning)
        XCTAssertFalse(send.destination.carriesAmount, "an address carries no amount — amount entry first")
        XCTAssertNil(send.explanation)
    }

    func testAmountlessBolt12OfferRoutesToAmountEntry() {
        guard let send = sendRoute(route(amountlessBolt12Offer)) else {
            return XCTFail("expected .send")
        }

        XCTAssertEqual(send.request, amountlessBolt12Offer)
        XCTAssertEqual(send.mode, .lightning)
        XCTAssertFalse(send.destination.carriesAmount, "an amountless offer needs sender amount entry before quoting")
        XCTAssertNil(send.explanation)
    }

    func testOnchainAddressRoutesToOnchainMelt() {
        guard let send = sendRoute(route("bc1qw508d6qejxtdg4y5r3zarvary0c5xw7kv8f3t4")) else {
            return XCTFail("expected .send")
        }
        XCTAssertEqual(send.mode, .onchain)
        XCTAssertFalse(send.destination.carriesAmount)
    }

    func testMintLikeURLIsCopiedNotRouted() {
        guard case .mintURL(let url) = route("https://mint.example.com") else {
            return XCTFail("expected .mintURL")
        }
        XCTAssertEqual(url, "https://mint.example.com")
    }

    func testJunkIsUnrecognized() {
        guard case .unrecognized = route("hello world") else {
            return XCTFail("expected .unrecognized")
        }
    }

    func testAmountlessBolt11InvoiceRoutesToAmountEntry() {
        // BOLT #11 spec example: a donation invoice that leaves the amount to the payer.
        let invoice = "lnbc1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdpl2pkx2ctnv5sxxmmwwd5kgetjypeh2ursdae8g6twvus8g6rfwvs8qun0dfjkxaq9qrsgq357wnc5r2ueh7ck6q93dj32dlqnls087fxdwk8qakdyafkq3yap9us6v52vjjsrvywa6rt52cm9r9zqt8r2t7mlcwspyetp5h2tztugp9lfyql"
        XCTAssertTrue(PaymentRequestDecoder.decode(invoice).isAmountlessBolt11)

        guard let send = sendRoute(route("lightning:\(invoice)")) else {
            return XCTFail("expected .send")
        }

        XCTAssertEqual(send.request, invoice)
        XCTAssertEqual(send.mode, .lightning)
        XCTAssertFalse(send.destination.carriesAmount, "an amountless invoice needs sender amount entry before quoting")
        XCTAssertNil(send.explanation)
    }

    func testCashuRequestBolt11FallbackRoutesToMeltWithExplanation() throws {
        // A NUT-18 creq the wallet can't pay with held ecash falls back to its
        // bundled bolt11 — the injected policy stands in for that decision.
        let creq = try PaymentRequestBuilder.build(
            id: "scanner-fallback", amount: 21, unit: "sat", mints: [],
            description: nil, nostrPubkeyHex: String(repeating: "b", count: 64),
            relays: ["wss://relay.example.com"]
        )
        guard case .cashuPaymentRequest = PaymentRequestDecoder.decode(
            creq, includeCashuPaymentRequests: true, preferCashuPaymentRequests: true
        ) else {
            return XCTFail("Generated Cashu request must decode before testing its fallback route")
        }
        let routed = ScanRouter.route(creq) { _, _ in .payBolt11Fallback("lnbcfallback") }
        guard let send = sendRoute(routed) else {
            return XCTFail("expected .send fallback")
        }
        XCTAssertEqual(send.request, "lnbcfallback")
        XCTAssertEqual(send.mode, .lightning)
        XCTAssertEqual(send.explanation, CashuRequestRouteExplanation(state: .lightningFallback))
    }
}

/// How a locked destination reads in Send's "To" row: a fixed 8…6 cut, with
/// Lightning addresses whole. Mirrors Android's `PaymentRequestDecoderTest`
/// display cases.
final class PaymentRequestDisplayTests: XCTestCase {
    func testCaseInsensitiveRequestsFromUppercaseQRCodesShowLowercase() {
        XCTAssertEqual(
            PaymentRequestDecoder.displayRequest(" LNBC1P4T40C2PP5ARHNG9C7QPU02S3T ", result: .bolt11(amountSats: nil, description: nil)),
            "lnbc1p4t…u02s3t"
        )
        XCTAssertEqual(
            PaymentRequestDecoder.displayRequest("LNO1PGQPVGGR", result: .bolt12(amountSats: nil, description: nil)),
            "lno1pgqpvggr"
        )
        XCTAssertEqual(
            PaymentRequestDecoder.displayRequest("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", result: .onchain("BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4")),
            "bc1qw508…v8f3t4"
        )
    }

    func testCaseSensitiveRequestsKeepTheirCase() {
        let base58 = "1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2"
        XCTAssertEqual(PaymentRequestDecoder.displayRequest(base58, result: .onchain(base58)), "1BvBMSEY…JaNVN2")
        XCTAssertEqual(
            PaymentRequestDecoder.displayRequest("creqApAyloADebbdfhZHRyYW5zcG9ydHOB", result: .unrecognized),
            "creqApAy…9ydHOB"
        )
    }

    func testLightningAddressesStayWhole() {
        let address = "Satoshi.Nakamoto@lightning.example.com"
        XCTAssertEqual(PaymentRequestDecoder.displayRequest(address, result: .lightningAddress(address)), address)
    }
}
