import XCTest
@testable import CashuWallet

final class PaymentRequestDecoderTests: XCTestCase {

    // MARK: - iconName

    func testIconNameLightningAddress() {
        XCTAssertEqual(PaymentRequestDecoder.iconName(.lightningAddress("user@example.com")), "at")
    }

    func testIconNameBolt11() {
        XCTAssertEqual(PaymentRequestDecoder.iconName(.bolt11(amountSats: nil, description: nil)), "bolt.fill")
    }

    func testIconNameBolt11WithAmount() {
        XCTAssertEqual(PaymentRequestDecoder.iconName(.bolt11(amountSats: 1000, description: "test")), "bolt.fill")
    }

    func testIconNameBolt12() {
        XCTAssertEqual(PaymentRequestDecoder.iconName(.bolt12(amountSats: nil, description: nil)), "bolt.fill")
    }

    func testIconNameOnchain() {
        XCTAssertEqual(
            PaymentRequestDecoder.iconName(.onchain(address: "1A1zP1eP5", amountSats: nil, label: nil)),
            "bitcoinsign.circle"
        )
    }

    func testIconNameUnrecognized() {
        XCTAssertEqual(PaymentRequestDecoder.iconName(.unrecognized), "questionmark.circle")
    }

    func testIconNameCashuPaymentRequest() {
        let summary = CashuPaymentRequestSummary(
            encoded: "creqAtest",
            amount: 100,
            unit: "sat",
            description: nil,
            mints: []
        )
        XCTAssertEqual(PaymentRequestDecoder.iconName(.cashuPaymentRequest(summary)), "banknote")
    }

    // MARK: - typeLabel

    func testTypeLabelLightningAddress() {
        XCTAssertEqual(PaymentRequestDecoder.typeLabel(.lightningAddress("user@example.com")), "Lightning address")
    }

    func testTypeLabelBolt11() {
        XCTAssertEqual(PaymentRequestDecoder.typeLabel(.bolt11(amountSats: nil, description: nil)), "BOLT11 invoice")
    }

    func testTypeLabelBolt12() {
        XCTAssertEqual(PaymentRequestDecoder.typeLabel(.bolt12(amountSats: nil, description: nil)), "BOLT12 offer")
    }

    func testTypeLabelOnchain() {
        XCTAssertEqual(
            PaymentRequestDecoder.typeLabel(.onchain(address: "1A1z", amountSats: nil, label: nil)),
            "Bitcoin address"
        )
    }

    func testTypeLabelUnrecognized() {
        XCTAssertEqual(PaymentRequestDecoder.typeLabel(.unrecognized), "Unrecognized")
    }

    // MARK: - amountLocked

    func testAmountLockedBolt11WithAmount() {
        XCTAssertTrue(PaymentRequestDecoder.amountLocked(.bolt11(amountSats: 1000, description: nil)))
    }

    func testAmountLockedBolt11WithZeroAmount() {
        XCTAssertTrue(PaymentRequestDecoder.amountLocked(.bolt11(amountSats: 0, description: nil)))
    }

    func testAmountLockedBolt11NoAmount() {
        XCTAssertFalse(PaymentRequestDecoder.amountLocked(.bolt11(amountSats: nil, description: nil)))
    }

    func testAmountLockedBolt12WithAmount() {
        XCTAssertTrue(PaymentRequestDecoder.amountLocked(.bolt12(amountSats: 500, description: nil)))
    }

    func testAmountLockedBolt12NoAmount() {
        XCTAssertFalse(PaymentRequestDecoder.amountLocked(.bolt12(amountSats: nil, description: nil)))
    }

    func testAmountLockedLightningAddressAlwaysFalse() {
        XCTAssertFalse(PaymentRequestDecoder.amountLocked(.lightningAddress("user@example.com")))
    }

    func testAmountLockedOnchainAlwaysFalse() {
        XCTAssertFalse(PaymentRequestDecoder.amountLocked(.onchain(address: "1A1z", amountSats: nil, label: nil)))
    }

    func testAmountLockedUnrecognizedAlwaysFalse() {
        XCTAssertFalse(PaymentRequestDecoder.amountLocked(.unrecognized))
    }

    // MARK: - shortRepresentation

    func testShortRepresentationLightningAddressFullValue() {
        let address = "user@example.com"
        let result = PaymentRequestDecoder.shortRepresentation(address, result: .lightningAddress(address))
        XCTAssertEqual(result, address)
    }

    func testShortRepresentationShortStringNotTruncated() {
        let short = "lnbc123"
        let result = PaymentRequestDecoder.shortRepresentation(short, result: .bolt11(amountSats: nil, description: nil))
        XCTAssertEqual(result, short)
    }

    func testShortRepresentationLongStringTruncated() {
        let long = "lnbc1234567890abcdefghijklmnopqrstuvwxyz0123456789"
        let result = PaymentRequestDecoder.shortRepresentation(long, result: .bolt11(amountSats: nil, description: nil))
        XCTAssertTrue(result.contains("…"), "Long string should be truncated with ellipsis")
        XCTAssertTrue(result.hasPrefix(String(long.prefix(8))), "Should start with first 8 chars")
        XCTAssertTrue(result.hasSuffix(String(long.suffix(6))), "Should end with last 6 chars")
    }

    func testShortRepresentationExactly16CharsNotTruncated() {
        let exact16 = "1234567890123456"
        XCTAssertEqual(exact16.count, 16)
        let result = PaymentRequestDecoder.shortRepresentation(exact16, result: .unrecognized)
        XCTAssertEqual(result, exact16)
    }

    func testShortRepresentationCashuPaymentRequestUsesDescription() {
        let summary = CashuPaymentRequestSummary(
            encoded: "creqAtest",
            amount: nil,
            unit: nil,
            description: "Pay for coffee",
            mints: []
        )
        let result = PaymentRequestDecoder.shortRepresentation("creqAtest", result: .cashuPaymentRequest(summary))
        XCTAssertEqual(result, "Pay for coffee")
    }

    func testShortRepresentationCashuPaymentRequestFallsBackToAmount() {
        let summary = CashuPaymentRequestSummary(
            encoded: "creqAtest",
            amount: 42,
            unit: "sat",
            description: nil,
            mints: []
        )
        let result = PaymentRequestDecoder.shortRepresentation("creqAtest", result: .cashuPaymentRequest(summary))
        XCTAssertEqual(result, "42 sat")
    }

    // MARK: - decode — empty / whitespace

    func testDecodeEmptyStringIsUnrecognized() {
        XCTAssertEqual(PaymentRequestDecoder.decode(""), .unrecognized)
    }

    func testDecodeWhitespaceOnlyIsUnrecognized() {
        XCTAssertEqual(PaymentRequestDecoder.decode("   "), .unrecognized)
    }

    // MARK: - decode — lightning address

    func testDecodeLightningAddress() {
        let result = PaymentRequestDecoder.decode("user@example.com")
        XCTAssertEqual(result, .lightningAddress("user@example.com"))
    }

    func testDecodeLightningAddressWithSubdomain() {
        let result = PaymentRequestDecoder.decode("alice@wallet.example.com")
        XCTAssertEqual(result, .lightningAddress("alice@wallet.example.com"))
    }

    // MARK: - decode — onchain

    func testDecodeP2PKHBitcoinAddress() {
        let result = PaymentRequestDecoder.decode("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")
        if case .onchain = result {
            // pass
        } else {
            XCTFail("Expected .onchain, got \(result)")
        }
    }

    func testDecodeBip21CarriesAmountAndLabel() {
        let result = PaymentRequestDecoder.decode(
            "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?amount=0.00021&label=Coffee%20Fund"
        )
        XCTAssertEqual(
            result,
            .onchain(address: "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", amountSats: 21_000, label: "Coffee Fund")
        )
    }

    func testDecodeBip21WithoutQueryHasNoAmount() {
        let result = PaymentRequestDecoder.decode("bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")
        XCTAssertEqual(
            result,
            .onchain(address: "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa", amountSats: nil, label: nil)
        )
    }

    // MARK: - PaymentRequestParser.parseBitcoinRequest (BIP-21)

    func testParseBitcoinRequestWholeAndFractionalAmount() {
        let payment = PaymentRequestParser.parseBitcoinRequest(
            "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?amount=1.5"
        )
        XCTAssertEqual(payment?.amountSats, 150_000_000)
    }

    func testParseBitcoinRequestSmallestAmount() {
        let payment = PaymentRequestParser.parseBitcoinRequest(
            "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?amount=0.00000001"
        )
        XCTAssertEqual(payment?.amountSats, 1)
    }

    func testParseBitcoinRequestMalformedAmountDegradesToNil() {
        for bad in ["abc", "1.2.3", "-1", "1,5", "0.000000001", ""] {
            let payment = PaymentRequestParser.parseBitcoinRequest(
                "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?amount=\(bad)"
            )
            XCTAssertNotNil(payment, "address must still parse for amount=\(bad)")
            XCTAssertNil(payment?.amountSats, "amount=\(bad) must not produce a figure")
        }
    }

    func testParseBitcoinRequestZeroAmountIsNil() {
        let payment = PaymentRequestParser.parseBitcoinRequest(
            "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?amount=0"
        )
        XCTAssertNil(payment?.amountSats)
    }

    func testParseBitcoinRequestPercentDecodesLabelAndMessage() {
        let payment = PaymentRequestParser.parseBitcoinRequest(
            "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?label=Luke%2DJr&message=Donation%20for%20project"
        )
        XCTAssertEqual(payment?.label, "Luke-Jr")
        XCTAssertEqual(payment?.message, "Donation for project")
    }

    func testParseBitcoinRequestLightningParam() {
        let payment = PaymentRequestParser.parseBitcoinRequest(
            "bitcoin:1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa?lightning=lnbc1invoice"
        )
        XCTAssertEqual(payment?.lightning, "lnbc1invoice")
    }

    func testParseBitcoinRequestInvalidAddressIsNil() {
        XCTAssertNil(PaymentRequestParser.parseBitcoinRequest("bitcoin:notanaddress?amount=1"))
    }

    func testParseBitcoinRequestBareAddress() {
        let payment = PaymentRequestParser.parseBitcoinRequest("1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")
        XCTAssertEqual(payment?.address, "1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa")
        XCTAssertNil(payment?.amountSats)
    }

    // MARK: - amountLabel

    func testAmountLabelWithAmount() {
        let summary = CashuPaymentRequestSummary(
            encoded: "creqAtest",
            amount: 21,
            unit: "sat",
            description: nil,
            mints: []
        )
        XCTAssertEqual(PaymentRequestDecoder.amountLabel(for: summary), "21 sat")
    }

    func testAmountLabelWithAmountNoUnit() {
        let summary = CashuPaymentRequestSummary(
            encoded: "creqAtest",
            amount: 100,
            unit: nil,
            description: nil,
            mints: []
        )
        XCTAssertEqual(PaymentRequestDecoder.amountLabel(for: summary), "100 sat")
    }

    func testAmountLabelNilWhenNoAmount() {
        let summary = CashuPaymentRequestSummary(
            encoded: "creqAtest",
            amount: nil,
            unit: "sat",
            description: nil,
            mints: []
        )
        XCTAssertNil(PaymentRequestDecoder.amountLabel(for: summary))
    }

    // MARK: - suggestedMode

    func testSuggestedModeOnchain() {
        XCTAssertEqual(
            PaymentRequestDecoder.suggestedMode(.onchain(address: "1A1z", amountSats: nil, label: nil)),
            .onchain
        )
    }

    func testSuggestedModeBolt11() {
        XCTAssertEqual(PaymentRequestDecoder.suggestedMode(.bolt11(amountSats: nil, description: nil)), .lightning)
    }

    func testSuggestedModeBolt12() {
        XCTAssertEqual(PaymentRequestDecoder.suggestedMode(.bolt12(amountSats: nil, description: nil)), .lightning)
    }

    func testSuggestedModeLightningAddress() {
        XCTAssertEqual(PaymentRequestDecoder.suggestedMode(.lightningAddress("user@example.com")), .lightning)
    }

    func testSuggestedModeUnrecognizedNil() {
        XCTAssertNil(PaymentRequestDecoder.suggestedMode(.unrecognized))
    }

    // MARK: - Locked receive request (NUT-10 P2PK lock in a NUT-18 request)

    private func bytesContain(_ data: Data, _ needle: String) -> Bool {
        let hay = Array(data)
        let pin = Array(needle.utf8)
        guard !pin.isEmpty, hay.count >= pin.count else { return false }
        for start in 0...(hay.count - pin.count) where Array(hay[start..<start + pin.count]) == pin {
            return true
        }
        return false
    }

    func testLockedReceiveRequestEncodesNut10AndParses() throws {
        let pubkey = "02" + String(repeating: "a", count: 64)   // 66-char compressed P2PK key
        let encoded = try PaymentRequestBuilder.build(
            id: "testid01",
            amount: 21,
            unit: "sat",
            mints: [],
            description: nil,
            nostrPubkeyHex: String(repeating: "b", count: 64),
            relays: ["wss://relay.example.com"],
            p2pkPubkeyHex: pubkey
        )
        XCTAssertTrue(encoded.hasPrefix("creqA"))

        // 1) Our own CBOR carries the lock.
        let myBytes = try XCTUnwrap(Base64URL.decode(String(encoded.dropFirst("creqA".count))))
        XCTAssertTrue(bytesContain(myBytes, "nut10"), "request CBOR should carry a nut10 field")
        XCTAssertTrue(bytesContain(myBytes, "P2PK"), "request CBOR should name the P2PK kind")
        XCTAssertTrue(bytesContain(myBytes, pubkey), "request CBOR should carry the locking pubkey")

        // 2) Adding the lock must not break the Nostr transport: CDK still parses
        // the request and the core fields round-trip.
        let parsed = try PaymentRequestDecoder.parseCashuPaymentRequest(encoded)
        XCTAssertEqual(parsed.amount()?.value, 21)
        XCTAssertFalse(parsed.transports().isEmpty)
    }
}
