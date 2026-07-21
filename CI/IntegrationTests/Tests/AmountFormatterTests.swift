import XCTest
@testable import CashuWallet

final class AmountFormatterTests: XCTestCase {

    // MARK: - sat unit

    func testSmallAmountSatUnit() {
        XCTAssertEqual(AmountFormatter.sats(1, useBitcoinSymbol: false), "1 sat")
    }

    func testRoundAmountSatUnit() {
        XCTAssertEqual(AmountFormatter.sats(100, useBitcoinSymbol: false), "100 sat")
    }

    func testGroupedThousandsSatUnit() {
        XCTAssertEqual(AmountFormatter.sats(1_000, useBitcoinSymbol: false), "1,000 sat")
    }

    func testMillionSatUnit() {
        XCTAssertEqual(AmountFormatter.sats(1_000_000, useBitcoinSymbol: false), "1,000,000 sat")
    }

    func testZeroSatUnit() {
        XCTAssertEqual(AmountFormatter.sats(0, useBitcoinSymbol: false), "0 sat")
    }

    // MARK: - Fiat conversion

    func testFiatConversionFormats() {
        XCTAssertEqual(AmountFormatter.fiat(sats: 300_000, btcPrice: 20_000, currencyCode: "USD"), "$60.00")
    }

    func testFiatConversionExactlyOneCent() {
        // 50 sats at $20k/BTC = $0.01 — the smallest displayable amount.
        XCTAssertEqual(AmountFormatter.fiat(sats: 50, btcPrice: 20_000, currencyCode: "USD"), "$0.01")
    }

    func testFiatConversionUnderOneCentHidden() {
        // 49 sats at $20k/BTC = $0.0098 — sub-cent conversions are never shown.
        XCTAssertNil(AmountFormatter.fiat(sats: 49, btcPrice: 20_000, currencyCode: "USD"))
    }

    func testFiatConversionMissingPriceHidden() {
        XCTAssertNil(AmountFormatter.fiat(sats: 1_000, btcPrice: nil, currencyCode: "USD"))
        XCTAssertNil(AmountFormatter.fiat(sats: 1_000, btcPrice: 0, currencyCode: "USD"))
    }

    // MARK: - Bitcoin symbol

    func testSmallAmountBitcoinSymbol() {
        XCTAssertEqual(AmountFormatter.sats(1, useBitcoinSymbol: true), "₿1")
    }

    func testGroupedThousandsBitcoinSymbol() {
        XCTAssertEqual(AmountFormatter.sats(1_000, useBitcoinSymbol: true), "₿1,000")
    }

    func testZeroBitcoinSymbol() {
        XCTAssertEqual(AmountFormatter.sats(0, useBitcoinSymbol: true), "₿0")
    }

    // MARK: - includeUnit: false

    func testNoUnitSatMode() {
        XCTAssertEqual(AmountFormatter.sats(1_000, useBitcoinSymbol: false, includeUnit: false), "1,000")
    }

    func testNoUnitBitcoinSymbolMode() {
        // Bitcoin symbol is the "unit", so it still omits the suffix.
        let result = AmountFormatter.sats(500, useBitcoinSymbol: true, includeUnit: false)
        XCTAssertEqual(result, "₿500")
    }

    func testZeroNoUnit() {
        XCTAssertEqual(AmountFormatter.sats(0, useBitcoinSymbol: false, includeUnit: false), "0")
    }

    // MARK: - Large amounts

    func testMaxSupplyAmount() {
        // 21 million BTC in sats
        let maxSats: UInt64 = 2_100_000_000_000_000
        let result = AmountFormatter.sats(maxSats, useBitcoinSymbol: false)
        XCTAssertTrue(result.hasSuffix(" sat"), "Large amount should still end with ' sat'")
        XCTAssertTrue(result.contains(","), "Large amount should have thousands separators")
    }
}
