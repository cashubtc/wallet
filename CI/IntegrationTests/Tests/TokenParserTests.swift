import XCTest
@testable import CashuWallet

final class TokenParserTests: XCTestCase {

    // MARK: - isCashuDeepLinkToken

    func testCashuAPrefix() {
        XCTAssertTrue(TokenParser.isCashuDeepLinkToken("cashuAabc123"))
    }

    func testCashuBPrefix() {
        XCTAssertTrue(TokenParser.isCashuDeepLinkToken("cashuBabc123"))
    }

    func testCashuAPrefixUppercase() {
        XCTAssertTrue(TokenParser.isCashuDeepLinkToken("CASHUAabc123"))
    }

    func testCashuBPrefixMixedCase() {
        XCTAssertTrue(TokenParser.isCashuDeepLinkToken("CashuBtoken"))
    }

    func testBitcoinAddressRejected() {
        XCTAssertFalse(TokenParser.isCashuDeepLinkToken("1A1zP1eP5QGefi2DMPTfTL5SLmv7Divf"))
    }

    func testLightningInvoiceRejected() {
        XCTAssertFalse(TokenParser.isCashuDeepLinkToken("lnbc1pvjluezsp5"))
    }

    func testEmptyStringRejected() {
        XCTAssertFalse(TokenParser.isCashuDeepLinkToken(""))
    }

    func testRandomStringRejected() {
        XCTAssertFalse(TokenParser.isCashuDeepLinkToken("hello world"))
    }

    // MARK: - normalizedToken — scheme stripping

    func testStripsDoubleSlashScheme() {
        let result = TokenParser.normalizedToken(from: "cashu://cashuAtoken123")
        XCTAssertEqual(result, "cashuAtoken123")
    }

    func testStripsSingleColonScheme() {
        let result = TokenParser.normalizedToken(from: "cashu:cashuAtoken123")
        XCTAssertEqual(result, "cashuAtoken123")
    }

    func testNoSchemePassthrough() {
        let result = TokenParser.normalizedToken(from: "cashuAtoken123")
        XCTAssertEqual(result, "cashuAtoken123")
    }

    func testCashuBNoScheme() {
        let result = TokenParser.normalizedToken(from: "cashuBtoken456")
        XCTAssertEqual(result, "cashuBtoken456")
    }

    func testWhitespaceIsTrimmed() {
        let result = TokenParser.normalizedToken(from: "  cashuAtoken123  ")
        XCTAssertEqual(result, "cashuAtoken123")
    }

    func testLightningInvoiceReturnsNil() {
        let result = TokenParser.normalizedToken(from: "lnbc1pvjluez")
        XCTAssertNil(result)
    }

    func testBitcoinAddressReturnsNil() {
        let result = TokenParser.normalizedToken(from: "1A1zP1eP5QGefi2DMPTfTL5SLmv7Divf")
        XCTAssertNil(result)
    }

    func testEmptyStringReturnsNil() {
        let result = TokenParser.normalizedToken(from: "")
        XCTAssertNil(result)
    }

    func testSchemeWithWhitespaceAndCashuB() {
        let result = TokenParser.normalizedToken(from: "  cashu://cashuBtoken789  ")
        XCTAssertEqual(result, "cashuBtoken789")
    }

    // MARK: - isCashuToken

    func testIsCashuTokenBareToken() {
        XCTAssertTrue(TokenParser.isCashuToken("cashuAtoken123"))
    }

    func testIsCashuTokenWithScheme() {
        XCTAssertTrue(TokenParser.isCashuToken("cashu://cashuAtoken123"))
    }

    func testIsCashuTokenWithColonScheme() {
        XCTAssertTrue(TokenParser.isCashuToken("cashu:cashuBtoken456"))
    }

    func testIsCashuTokenLightningFalse() {
        XCTAssertFalse(TokenParser.isCashuToken("lnbc1pvjluez"))
    }

    func testIsCashuTokenEmptyFalse() {
        XCTAssertFalse(TokenParser.isCashuToken(""))
    }
}
