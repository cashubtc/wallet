import XCTest
@testable import CashuWallet

final class ReceiveTokenMemoReviewTests: XCTestCase {
    func testPresentMemoIsTrimmedAndReviewedBeforeClaim() {
        let presentation = ReceiveTokenReviewPresentation(
            rawMemo: "  Thanks for dinner.\n"
        )

        XCTAssertEqual(presentation.memo?.text, "Thanks for dinner.")
        XCTAssertEqual(
            presentation.confirmationElements,
            [.memo("Thanks for dinner."), .claimAction]
        )
    }

    func testNilAndBlankMemosAreOmitted() {
        XCTAssertNil(ReceiveTokenReviewPresentation(rawMemo: nil).memo)
        XCTAssertNil(ReceiveTokenReviewPresentation(rawMemo: " \n\t ").memo)
        XCTAssertEqual(
            ReceiveTokenReviewPresentation(rawMemo: nil).confirmationElements,
            [.claimAction]
        )
    }

    func testMemoExposesFullAccessibilityCopyAndStableIdentifier() {
        let memo = ReceiveTokenReviewPresentation(rawMemo: "Coffee beans").memo

        XCTAssertEqual(memo?.accessibilityLabel, "Memo")
        XCTAssertEqual(memo?.accessibilityValue, "Coffee beans")
        XCTAssertEqual(memo?.accessibilityIdentifier, "receive-token-review-memo")
    }
}
