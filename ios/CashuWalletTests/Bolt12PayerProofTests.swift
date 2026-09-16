import XCTest
@testable import CashuWallet

final class Bolt12PayerProofTests: XCTestCase {
    private let hex = "0123456789abcdef0123456789abcdef"
    private let lnp1 = "lnp1pgd9xatswphhyapqgf85c4p3xgsxgetkv4kx7urdv4h8gys0we5kucm9deax7urpd3sh57n0"
    private let mixedCase = "LNP1PGD9XATSWPHHYAPQGF85C4P3XGSXGETKV4KX7URDV4H8GYS0WE5KUCM9DEAX7URPD3SH57N0"

    func testSplitKeepsHexAsPreimageAndLnp1AsPayerProof() {
        let hexSplit = Bolt12PayerProof.split(hex)
        XCTAssertEqual(hexSplit.preimage, hex)
        XCTAssertNil(hexSplit.payerProof)

        let proofSplit = Bolt12PayerProof.split(lnp1)
        XCTAssertNil(proofSplit.preimage)
        XCTAssertEqual(proofSplit.payerProof, lnp1)

        XCTAssertEqual(Bolt12PayerProof.split(mixedCase).payerProof, lnp1)
        XCTAssertNil(Bolt12PayerProof.split(nil).preimage)
        XCTAssertNil(Bolt12PayerProof.split("  ").payerProof)
    }

    func testDisplayedProofPrefersSignedPayerProof() {
        XCTAssertEqual(Bolt12PayerProof.displayedProof(payerProof: lnp1, preimage: hex), lnp1)
        XCTAssertEqual(Bolt12PayerProof.displayedProof(payerProof: mixedCase, preimage: hex), lnp1)
        XCTAssertEqual(Bolt12PayerProof.displayedProof(payerProof: nil, preimage: hex), hex)
        XCTAssertNil(Bolt12PayerProof.displayedProof(payerProof: nil, preimage: nil))
        XCTAssertFalse(Bolt12PayerProof.isSigned(hex))
        XCTAssertTrue(Bolt12PayerProof.isSigned(mixedCase))
        XCTAssertFalse(Bolt12PayerProof.isSigned("LNP1abc"))
        XCTAssertFalse(Bolt12PayerProof.isSigned("lnp1://evil.example/x"))
        XCTAssertFalse(Bolt12PayerProof.isSigned("lnp1"))
    }

    func testVerifyURLExistsOnlyForSignedProofs() {
        XCTAssertEqual(
            Bolt12PayerProof.verifyURL(for: mixedCase)?.absoluteString,
            "https://lnproof.space/" + lnp1
        )
        XCTAssertNil(Bolt12PayerProof.verifyURL(for: hex))
        XCTAssertNil(Bolt12PayerProof.verifyURL(for: "  "))
        XCTAssertNil(Bolt12PayerProof.verifyURL(for: "lnp1://evil.example"))
    }
}
