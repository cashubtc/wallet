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
