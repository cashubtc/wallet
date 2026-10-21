import XCTest
@testable import CashuWallet

/// Android parity: `MintTransferEligibilityTest.kt`.
final class MintTransferEligibilityTests: XCTestCase {

    // MARK: - Capability as reported by the mint

    func testCapabilityRequiresTheBolt11SatPair() {
        let capability = Bolt11SatCapability.reported(
            mint: [
                (method: .bolt11, isSat: false, min: nil, max: nil),
                (method: .bolt12, isSat: true, min: nil, max: nil),
            ],
            mintingDisabled: false,
            melt: [
                (method: .bolt11, isSat: true, min: nil, max: nil),
            ],
            meltingDisabled: false
        )

        // Bolt11 in another unit, or another rail in sat, is not the pair.
        XCTAssertFalse(capability.canMint)
        XCTAssertTrue(capability.canMelt)
    }

    func testDisabledNutTurnsTheDirectionOffEvenWhenListed() {
        let capability = Bolt11SatCapability.reported(
            mint: [(method: .bolt11, isSat: true, min: nil, max: nil)],
            mintingDisabled: true,
            melt: [(method: .bolt11, isSat: true, min: nil, max: nil)],
            meltingDisabled: true
        )

        XCTAssertFalse(capability.canMint)
        XCTAssertFalse(capability.canMelt)
    }

    func testCapabilityKeepsTheSatLimitsNotAnotherUnits() {
        let capability = Bolt11SatCapability.reported(
            mint: [
                (method: .bolt11, isSat: false, min: 100, max: 200),
                (method: .bolt11, isSat: true, min: 1, max: 500_000),
            ],
            mintingDisabled: false,
            melt: [
                (method: .bolt11, isSat: true, min: 10, max: 250_000),
            ],
            meltingDisabled: false
        )

        XCTAssertEqual(capability.mintMin, 1)
        XCTAssertEqual(capability.mintMax, 500_000)
        XCTAssertEqual(capability.meltMin, 10)
        XCTAssertEqual(capability.meltMax, 250_000)
    }

    /// Records written before the capability existed must read as "unknown",
    /// not as a mint that supports nothing.
    func testRecordWithoutCapabilityDecodesAsUnknown() throws {
        let stored = #"{"url":"https://mint.example","name":"Mint","isActive":true,"balance":21}"#
        let mint = try JSONDecoder().decode(MintInfo.self, from: Data(stored.utf8))

        XCTAssertNil(mint.bolt11Sat)
        XCTAssertTrue(MintTransferEligibility.canSend(mint))
        XCTAssertTrue(MintTransferEligibility.canReceive(mint))
    }

    func testCapabilitySurvivesPersistence() throws {
        var mint = self.mint("https://mint.example")
        mint.bolt11Sat = Bolt11SatCapability(
            canMint: true, canMelt: false, mintMin: 1, mintMax: 500_000, meltMin: nil, meltMax: nil
        )

        let decoded = try JSONDecoder().decode(MintInfo.self, from: JSONEncoder().encode(mint))

        XCTAssertEqual(decoded.bolt11Sat, mint.bolt11Sat)
    }

    // MARK: - Eligibility

    func testTwoCapableMintsAreEligible() {
        let source = mint("https://a.example", capability: capable())
        let destination = mint("https://b.example", capability: capable())

        XCTAssertNil(MintTransferEligibility.blocker(source: source, destination: destination))
        XCTAssertTrue(MintTransferEligibility.eligible(source: source, destination: destination))
    }

    func testEquivalentURLsAreTheSameMint() {
        let source = mint("https://Mint.Example:443/", capability: capable())
        let destination = mint("https://mint.example", capability: capable())

        XCTAssertEqual(
            MintTransferEligibility.blocker(source: source, destination: destination),
            .sameMint
        )
    }

    func testSourceThatCannotMeltBlocksOnlyThatDirection() {
        let receiveOnly = mint("https://a.example", capability: capable(canMelt: false))
        let other = mint("https://b.example", capability: capable())

        XCTAssertEqual(
            MintTransferEligibility.blocker(source: receiveOnly, destination: other),
            .sourceCannotSend
        )
        XCTAssertNil(MintTransferEligibility.blocker(source: other, destination: receiveOnly))
    }

    func testDestinationThatCannotMintBlocksOnlyThatDirection() {
        let sendOnly = mint("https://a.example", capability: capable(canMint: false))
        let other = mint("https://b.example", capability: capable())

        XCTAssertEqual(
            MintTransferEligibility.blocker(source: other, destination: sendOnly),
            .destinationCannotReceive
        )
        XCTAssertNil(MintTransferEligibility.blocker(source: sendOnly, destination: other))
    }

    /// An empty source is a valid selection — it has nothing to send, which the
    /// amount check reports, but the pair itself is not blocked.
    func testEmptySourceIsNotABlocker() {
        let source = mint("https://a.example", balance: 0, capability: capable())
        let destination = mint("https://b.example", capability: capable())

        XCTAssertNil(MintTransferEligibility.blocker(source: source, destination: destination))
    }

    func testUnfetchedMintFallsBackToItsMethodLists() {
        var meltless = mint("https://a.example")
        meltless.supportedMeltMethods = [.bolt12]
        var nonSat = mint("https://b.example")
        nonSat.mintUnits = ["usd"]
        var noBolt11Mint = mint("https://c.example")
        noBolt11Mint.supportedMintMethods = [.onchain]

        XCTAssertFalse(MintTransferEligibility.canSend(meltless))
        XCTAssertFalse(MintTransferEligibility.canReceive(nonSat))
        XCTAssertFalse(MintTransferEligibility.canReceive(noBolt11Mint))
    }

    /// Once fetched, the capability wins over the coarser method lists.
    func testFetchedCapabilityOverridesMethodLists() {
        var paused = mint("https://a.example", capability: capable(canMint: false, canMelt: false))
        paused.supportedMintMethods = [.bolt11]
        paused.supportedMeltMethods = [.bolt11]

        XCTAssertFalse(MintTransferEligibility.canSend(paused))
        XCTAssertFalse(MintTransferEligibility.canReceive(paused))
    }

    // MARK: - Amount range

    func testAmountRangeIntersectsDestinationMintAndSourceMeltLimits() {
        let source = mint("https://a.example", capability: capable(meltMin: 10, meltMax: 250_000))
        let destination = mint("https://b.example", capability: capable(mintMin: 100, mintMax: 500_000))

        XCTAssertEqual(
            MintTransferEligibility.amountRange(source: source, destination: destination),
            100...250_000
        )
    }

    /// The other mint's limits are irrelevant: a source's mint limits and a
    /// destination's melt limits do not constrain this direction.
    func testAmountRangeIgnoresTheOppositeDirectionsLimits() {
        let source = mint("https://a.example", capability: capable(mintMin: 5_000, mintMax: 6_000))
        let destination = mint("https://b.example", capability: capable(meltMin: 7_000, meltMax: 8_000))

        XCTAssertEqual(
            MintTransferEligibility.amountRange(source: source, destination: destination),
            1...UInt64.max
        )
    }

    func testAmountRangeIsOpenWhenLimitsAreUnknown() {
        XCTAssertEqual(
            MintTransferEligibility.amountRange(
                source: mint("https://a.example"),
                destination: mint("https://b.example")
            ),
            1...UInt64.max
        )
    }

    func testAmountRangeIsNilWhenLimitsDoNotOverlap() {
        let source = mint("https://a.example", capability: capable(meltMax: 50))
        let destination = mint("https://b.example", capability: capable(mintMin: 100))

        XCTAssertNil(MintTransferEligibility.amountRange(source: source, destination: destination))
    }

    // MARK: - Fixtures

    private func mint(
        _ url: String,
        balance: UInt64 = 1_000,
        capability: Bolt11SatCapability? = nil
    ) -> MintInfo {
        var mint = MintInfo(url: url, name: "Mint", isActive: true, balance: balance)
        mint.bolt11Sat = capability
        return mint
    }

    private func capable(
        canMint: Bool = true,
        canMelt: Bool = true,
        mintMin: UInt64? = nil,
        mintMax: UInt64? = nil,
        meltMin: UInt64? = nil,
        meltMax: UInt64? = nil
    ) -> Bolt11SatCapability {
        Bolt11SatCapability(
            canMint: canMint, canMelt: canMelt,
            mintMin: mintMin, mintMax: mintMax, meltMin: meltMin, meltMax: meltMax
        )
    }
}

/// Android parity: `MintTransferRouteTest.kt`.
final class MintTransferRouteTests: XCTestCase {
    private let a = "https://a.example"
    private let b = "https://b.example"
    private let c = "https://c.example"

    // MARK: - Opening pair

    func testNeedsTwoMints() {
        XCTAssertNil(MintTransferRoute.initial(mints: [mint(a, 100)], activeMintURL: a))
    }

    /// From the Mints list the largest balance moves toward the default mint.
    func testOpensFromTheLargestBalanceTowardTheDefaultMint() {
        let route = MintTransferRoute.initial(
            mints: [mint(a, 10), mint(b, 500), mint(c, 0)],
            activeMintURL: a
        )

        XCTAssertEqual(route, MintTransferRoute(sourceMintURL: b, destinationMintURL: a))
    }

    func testDefaultMintHoldingTheMostSendsToTheNextMintInTheList() {
        let route = MintTransferRoute.initial(
            mints: [mint(a, 500), mint(b, 10), mint(c, 0)],
            activeMintURL: a
        )

        XCTAssertEqual(route, MintTransferRoute(sourceMintURL: a, destinationMintURL: b))
    }

    func testEqualBalancesFallBackToListOrder() {
        let route = MintTransferRoute.initial(
            mints: [mint(a, 100), mint(b, 100), mint(c, 100)],
            activeMintURL: c
        )

        XCTAssertEqual(route, MintTransferRoute(sourceMintURL: a, destinationMintURL: c))
    }

    func testOpenedFromAFundedMintSendsFromIt() {
        let route = MintTransferRoute.initial(
            mints: [mint(a, 500), mint(b, 10), mint(c, 0)],
            activeMintURL: a,
            openedFromMintURL: b
        )

        XCTAssertEqual(route, MintTransferRoute(sourceMintURL: b, destinationMintURL: a))
    }

    /// An empty mint has nothing to send, so opening a transfer on it means
    /// filling it.
    func testOpenedFromAnEmptyMintSendsToIt() {
        let route = MintTransferRoute.initial(
            mints: [mint(a, 500), mint(b, 10), mint(c, 0)],
            activeMintURL: a,
            openedFromMintURL: c
        )

        XCTAssertEqual(route, MintTransferRoute(sourceMintURL: a, destinationMintURL: c))
    }

    func testMintThatCannotSendIsNotTheOpeningSource() {
        var receiveOnly = mint(a, 900)
        receiveOnly.bolt11Sat = Bolt11SatCapability(canMint: true, canMelt: false)

        let route = MintTransferRoute.initial(mints: [receiveOnly, mint(b, 10)], activeMintURL: b)

        XCTAssertEqual(route, MintTransferRoute(sourceMintURL: b, destinationMintURL: a))
    }

    // MARK: - Changing the pair

    func testSwapTradesTheTwoMints() {
        let route = MintTransferRoute(sourceMintURL: a, destinationMintURL: b)

        XCTAssertEqual(route.swapped, MintTransferRoute(sourceMintURL: b, destinationMintURL: a))
    }

    func testChoosingAnotherMintReplacesOnlyThatEnd() {
        let route = MintTransferRoute(sourceMintURL: a, destinationMintURL: b)

        XCTAssertEqual(route.choosing(c, as: .source), MintTransferRoute(sourceMintURL: c, destinationMintURL: b))
        XCTAssertEqual(route.choosing(c, as: .destination), MintTransferRoute(sourceMintURL: a, destinationMintURL: c))
    }

    /// A pick never dead-ends on "already in use".
    func testChoosingTheMintAtTheOtherEndSwaps() {
        let route = MintTransferRoute(sourceMintURL: a, destinationMintURL: b)

        XCTAssertEqual(route.choosing(b, as: .source), route.swapped)
        XCTAssertEqual(route.choosing("https://A.example:443/", as: .destination), route.swapped)
    }

    // MARK: - Entry

    func testEntryIsReadyOnlyForAnAmountTheSourceHolds() {
        let source = mint(a, 100)
        let destination = mint(b, 0)

        XCTAssertEqual(MintTransferEntry.validation(amount: 0, source: source, destination: destination), .empty)
        XCTAssertEqual(MintTransferEntry.validation(amount: 100, source: source, destination: destination), .ready)
        XCTAssertEqual(MintTransferEntry.validation(amount: 101, source: source, destination: destination), .overBalance)
    }

    func testBlockedPairIsReportedBeforeTheAmount() {
        var sendOnly = mint(b, 0)
        sendOnly.bolt11Sat = Bolt11SatCapability(canMint: false, canMelt: true)

        XCTAssertEqual(
            MintTransferEntry.validation(amount: 0, source: mint(a, 100), destination: sendOnly),
            .blocked(.destinationCannotReceive)
        )
    }

    private func mint(_ url: String, _ balance: UInt64) -> MintInfo {
        MintInfo(url: url, name: url, isActive: true, balance: balance)
    }
}
