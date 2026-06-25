import XCTest
@testable import CashuWallet

// MARK: - CurrencyAmount

final class CurrencyAmountTests: XCTestCase {

    // MARK: - displayValue

    func testSatsDisplayValue() {
        XCTAssertEqual(CurrencyAmount.sats(1000).displayValue, 1000.0)
    }

    func testSatsZeroDisplayValue() {
        XCTAssertEqual(CurrencyAmount.sats(0).displayValue, 0.0)
    }

    func testUSDCentsDisplayValue() {
        XCTAssertEqual(CurrencyAmount.usdCents(100).displayValue, 1.0, accuracy: 0.0001)
    }

    func testUSDCentsOddDisplayValue() {
        XCTAssertEqual(CurrencyAmount.usdCents(150).displayValue, 1.5, accuracy: 0.0001)
    }

    func testEurCentsDisplayValue() {
        XCTAssertEqual(CurrencyAmount.eurCents(250).displayValue, 2.5, accuracy: 0.0001)
    }

    // MARK: - formatted

    func testSatsFormattedContainsBitcoinSymbol() {
        XCTAssertTrue(CurrencyAmount.sats(1000).formatted().contains("₿"))
    }

    func testSatsFormattedContainsGroupedNumber() {
        XCTAssertTrue(CurrencyAmount.sats(1000).formatted().contains("1,000"))
    }

    func testUSDCentsFormattedContainsDollarSign() {
        XCTAssertTrue(CurrencyAmount.usdCents(150).formatted().contains("$"))
    }

    func testUSDCentsFormattedTwoDecimalPlaces() {
        let result = CurrencyAmount.usdCents(150).formatted()
        XCTAssertTrue(result.contains("1.50"), "USD should show 2 decimal places: \(result)")
    }

    func testEurCentsFormattedContainsEuroSign() {
        XCTAssertTrue(CurrencyAmount.eurCents(299).formatted().contains("€"))
    }

    func testEurCentsFormattedTwoDecimalPlaces() {
        let result = CurrencyAmount.eurCents(299).formatted()
        XCTAssertTrue(result.contains("2.99"), "EUR should show 2 decimal places: \(result)")
    }

    func testFormattedWithoutSymbolOmitsSymbol() {
        let result = CurrencyAmount.sats(500).formatted(showSymbol: false)
        XCTAssertFalse(result.contains("₿"), "Symbol should be omitted when showSymbol: false")
    }

    func testUSDFormattedWithoutSymbolOmitsDollarSign() {
        let result = CurrencyAmount.usdCents(200).formatted(showSymbol: false)
        XCTAssertFalse(result.contains("$"))
        XCTAssertTrue(result.contains("2.00"))
    }

    // MARK: - Equality

    func testSameAmountSameCurrencyEqual() {
        XCTAssertEqual(CurrencyAmount.sats(100), CurrencyAmount.sats(100))
    }

    func testDifferentValueNotEqual() {
        XCTAssertNotEqual(CurrencyAmount.sats(100), CurrencyAmount.sats(200))
    }

    func testSameValueDifferentCurrencyNotEqual() {
        XCTAssertNotEqual(CurrencyAmount.sats(100), CurrencyAmount.usdCents(100))
    }

    func testUSDEqualsSelf() {
        XCTAssertEqual(CurrencyAmount.usdCents(50), CurrencyAmount.usdCents(50))
    }

    func testUSDNotEqualsEUR() {
        XCTAssertNotEqual(CurrencyAmount.usdCents(100), CurrencyAmount.eurCents(100))
    }

    // MARK: - Currency properties

    func testSatoshiCurrencyHasZeroDecimals() {
        XCTAssertEqual(SatoshiCurrency().decimals, 0)
    }

    func testUSDHasTwoDecimals() {
        XCTAssertEqual(USDCurrency().decimals, 2)
    }

    func testEURHasTwoDecimals() {
        XCTAssertEqual(EURCurrency().decimals, 2)
    }

    func testSatoshiSymbolBeforeAmount() {
        XCTAssertEqual(SatoshiCurrency().symbolPosition, .before)
    }
}

// MARK: - CurrencyRegistry

final class CurrencyRegistryTests: XCTestCase {

    func testLookupSatByMintUnit() {
        let currency = CurrencyRegistry.currency(forMintUnit: "sat")
        XCTAssertNotNil(currency)
        XCTAssertEqual(currency?.code, "SAT")
    }

    func testLookupSatsByMintUnit() {
        XCTAssertEqual(CurrencyRegistry.currency(forMintUnit: "sats")?.code, "SAT")
    }

    func testLookupSatoshiByMintUnit() {
        XCTAssertEqual(CurrencyRegistry.currency(forMintUnit: "satoshi")?.code, "SAT")
    }

    func testLookupUSDByMintUnit() {
        XCTAssertEqual(CurrencyRegistry.currency(forMintUnit: "usd")?.code, "USD")
    }

    func testLookupEURByMintUnit() {
        XCTAssertEqual(CurrencyRegistry.currency(forMintUnit: "eur")?.code, "EUR")
    }

    func testLookupUnknownMintUnitReturnsNil() {
        XCTAssertNil(CurrencyRegistry.currency(forMintUnit: "xyz"))
    }

    func testLookupEmptyMintUnitReturnsNil() {
        XCTAssertNil(CurrencyRegistry.currency(forMintUnit: ""))
    }

    func testLookupSATByCode() {
        XCTAssertEqual(CurrencyRegistry.currency(forCode: "SAT")?.code, "SAT")
    }

    func testLookupUSDByCode() {
        XCTAssertEqual(CurrencyRegistry.currency(forCode: "USD")?.code, "USD")
    }

    func testLookupEURByCode() {
        XCTAssertEqual(CurrencyRegistry.currency(forCode: "EUR")?.code, "EUR")
    }

    func testCodeLookupCaseInsensitive() {
        XCTAssertEqual(CurrencyRegistry.currency(forCode: "eur")?.code, "EUR")
        XCTAssertEqual(CurrencyRegistry.currency(forCode: "Usd")?.code, "USD")
    }

    func testUnknownCodeReturnsNil() {
        XCTAssertNil(CurrencyRegistry.currency(forCode: "GBP"))
    }

    func testSupportedCurrenciesContainsThree() {
        XCTAssertEqual(CurrencyRegistry.supportedCurrencies.count, 3)
    }
}
