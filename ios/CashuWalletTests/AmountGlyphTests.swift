import XCTest
@testable import CashuWallet

/// A typed amount animates glyph by glyph (`AmountLockup`), so what a keystroke
/// looks like is decided by which glyphs keep their names: a name in both
/// strings slides, a name only in the new one comes into focus, and a name only
/// in the old one goes out of it.
final class AmountGlyphTests: XCTestCase {

    private typealias ID = AmountGlyph.ID

    private func ids(_ value: String) -> [ID] {
        AmountGlyph.glyphs(of: value).map(\.id)
    }

    private func change(_ old: String, _ new: String) -> (arriving: Set<ID>, leaving: Set<ID>) {
        let before = Set(ids(old)), after = Set(ids(new))
        return (after.subtracting(before), before.subtracting(after))
    }

    /// The keystroke `.numericText` got wrong: it rolled a 0 in after the 1
    /// and held the trailing 0 still.
    func testTypedDigitArrivesAtTheEndWhenGroupingAppears() {
        let (arriving, leaving) = change("210", "2,100")

        XCTAssertEqual(arriving, [ID(digitsBefore: 1, character: ","), ID(digitsBefore: 3, character: "0")])
        XCTAssertEqual(leaving, [])
        XCTAssertEqual(ids("2,100").last, ID(digitsBefore: 3, character: "0"))
    }

    func testMovingSeparatorLeavesAndArrives() {
        let (arriving, leaving) = change("2,100", "21,000")

        XCTAssertEqual(arriving, [ID(digitsBefore: 2, character: ","), ID(digitsBefore: 4, character: "0")])
        XCTAssertEqual(leaving, [ID(digitsBefore: 1, character: ",")])
    }

    func testBackspaceTakesOnlyTheLastDigit() {
        let (arriving, leaving) = change("2,100", "210")

        XCTAssertEqual(arriving, [])
        XCTAssertEqual(leaving, [ID(digitsBefore: 1, character: ","), ID(digitsBefore: 3, character: "0")])
    }

    func testFirstDigitReplacesTheZeroInPlace() {
        let (arriving, leaving) = change("0", "2")

        XCTAssertEqual(arriving, [ID(digitsBefore: 0, character: "2")])
        XCTAssertEqual(leaving, [ID(digitsBefore: 0, character: "0")])
    }

    func testFractionDigitsAndTheDecimalSeparatorKeepTheirNames() {
        XCTAssertEqual(change("1,234.", "1,234.5").arriving, [ID(digitsBefore: 4, character: "5")])
        // A comma-decimal locale groups with the period instead.
        XCTAssertEqual(change("1.234,5", "1.234,56").arriving, [ID(digitsBefore: 5, character: "6")])
    }

    func testNamesAreUnique() {
        let names = ids("12,345,678.90")

        XCTAssertEqual(Set(names).count, names.count)
    }
}

final class AmountFormatterCachingTests: XCTestCase {
    func testCachedFiatFormattersStayAccurateAcrossInterleavedCurrenciesAndParts() {
        let currencies = ["USD", "EUR", "GBP", "JPY", "CAD", "CHF"]
        var expectedFull: [String: String] = [:]

        for code in currencies {
            let formatted = AmountFormatter.fiat(1_234.56, currencyCode: code)
            expectedFull[code] = formatted
            let parts = AmountFormatter.fiatParts(1_234.56, currencyCode: code)
            let affix = AmountFormatter.currencyAffix(currencyCode: code)

            XCTAssertEqual(parts.affix, affix)
            XCTAssertEqual(parts.value, "1234.56")
            XCTAssertEqual(
                AmountFormatter.fiat(1_234.56, currencyCode: code),
                formatted,
                "fiatParts must not mutate the cached full currency formatter for \(code)"
            )
        }

        // Second pass verifies cached entries do not bleed across currencies.
        for code in currencies.reversed() {
            XCTAssertEqual(AmountFormatter.fiat(1_234.56, currencyCode: code), expectedFull[code])
        }
        XCTAssertEqual(AmountFormatter.fiat(1_234.56, currencyCode: "USD"), "$1234.56")
        XCTAssertEqual(AmountFormatter.fiat(1_234.56, currencyCode: "EUR"), "€1234.56")
    }

    func testCachedFiatFormattersRespectLocaleAndFractionDigits() {
        let posix = Locale(identifier: "en_US_POSIX")
        let german = Locale(identifier: "de_DE")

        let posixEur = AmountFormatter.fiat(1_234.5, currencyCode: "EUR", locale: posix, fractionDigits: 2)
        let germanEur = AmountFormatter.fiat(1_234.5, currencyCode: "EUR", locale: german, fractionDigits: 2)
        let zeroFraction = AmountFormatter.fiat(1_234.5, currencyCode: "JPY", locale: posix, fractionDigits: 0)
        let twoFraction = AmountFormatter.fiat(1_234.5, currencyCode: "JPY", locale: posix, fractionDigits: 2)

        XCTAssertEqual(posixEur, "€1234.50")
        XCTAssertTrue(germanEur.contains("1.234,50"), "German locale should group with '.' and decimal ',' (got \(germanEur))")
        XCTAssertFalse(zeroFraction.contains("."), "0 fraction digits must omit decimal separator (got \(zeroFraction))")
        XCTAssertTrue(twoFraction.contains(".50"), "2 fraction digits must retain decimals (got \(twoFraction))")
        XCTAssertEqual(AmountFormatter.fiat(1_234.5, currencyCode: "USD"), "$1234.50")
    }

    func testCurrencyAmountCachedFormattersStayAccurateAcrossUnits() {
        XCTAssertEqual(CurrencyAmount.sats(12_345).formatted(), "₿12,345")
        XCTAssertEqual(CurrencyAmount.sats(12_345).formatted(showSymbol: false), "12,345")
        XCTAssertEqual(CurrencyAmount.usdCents(123_456).formatted(), "$1,234.56")
        XCTAssertEqual(CurrencyAmount.usdCents(123_456).formatted(showSymbol: false), "1,234.56")
        XCTAssertEqual(CurrencyAmount.eurCents(50).formatted(), "€0.50")
        XCTAssertEqual(
            CurrencyAmount(value: 4_200, currency: GenericCurrency(unit: "pts")).formatted(),
            "4,200 PTS"
        )
        // Re-verify interleaved cached formatter entries preserve their fraction digits.
        XCTAssertEqual(CurrencyAmount.sats(999_999).formatted(), "₿999,999")
        XCTAssertEqual(CurrencyAmount.usdCents(100).formatted(), "$1.00")
    }
}
