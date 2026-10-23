import XCTest
@testable import CashuWallet

/// Android parity: `WalletMintTransferServiceTest.kt`.
@MainActor
final class MintTransferEngineTests: XCTestCase {
    private let sourceURL = "https://source.example"
    private let destinationURL = "https://destination.example"

    // MARK: - Preparing an exact amount

    func testPrepareQuotesBothMintsAndRecordsADraft() async throws {
        let world = World()
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)

        XCTAssertEqual(plan.mode, .exact)
        XCTAssertEqual(plan.amount, 40)
        XCTAssertEqual(plan.feeReserve, 2)
        XCTAssertEqual(plan.inputFee, 0)
        XCTAssertEqual(plan.total, 42)
        XCTAssertEqual(plan.selection, .automatic)
        XCTAssertEqual(world.quotedAt, [.mint(destinationURL), .melt(sourceURL)])
        XCTAssertEqual(world.records.map(\.state), [.draft])
        XCTAssertEqual(world.records.first?.mintQuoteID, plan.mintQuoteID)
        XCTAssertEqual(world.records.first?.meltQuoteID, plan.meltQuoteID)
    }

    func testFailedMeltQuoteLeavesNoQuoteAndNoRecord() async {
        let world = World()
        world.meltQuoteError = NFCPaymentError.insufficientBalance(required: 102, available: 100)

        await assertThrows(MintTransferError.insufficientBalance(required: 102, available: 100)) {
            _ = try await world.engine.prepare(from: self.sourceURL, to: self.destinationURL, amount: 100)
        }

        XCTAssertTrue(world.records.isEmpty)
        XCTAssertTrue(world.mintQuotes.isEmpty)
    }

    /// A mint that answers with a different amount is not paid.
    func testQuoteForADifferentAmountIsDiscarded() async {
        let world = World()
        world.meltQuoteAmount = 41

        await assertThrows(MintTransferError.quoteMismatch) {
            _ = try await world.engine.prepare(from: self.sourceURL, to: self.destinationURL, amount: 40)
        }

        XCTAssertTrue(world.records.isEmpty)
        XCTAssertTrue(world.mintQuotes.isEmpty)
        XCTAssertTrue(world.meltQuotes.isEmpty)
    }

    func testIneligiblePairIsRejectedBeforeAnyQuote() async {
        let world = World()

        await assertThrows(MintTransferError.notEligible(.sameMint)) {
            _ = try await world.engine.prepare(from: self.sourceURL, to: self.sourceURL + "/", amount: 40)
        }

        XCTAssertTrue(world.quotedAt.isEmpty)
    }

    // MARK: - Preparing the maximum

    func testMaxOnAFeeChargingSourceSpendsEveryProofAndShowsTheInputFee() async throws {
        let world = World()
        world.maxQuote = (amount: 80, feeReserve: 2, inputFee: 18)

        let plan = try await world.engine.prepareMax(from: sourceURL, to: destinationURL)

        XCTAssertEqual(plan.mode, .max)
        XCTAssertEqual(plan.amount, 80)
        XCTAssertEqual(plan.selection, .allUnspentSkippingSwap(expectedInputFee: 18))
        XCTAssertEqual(plan.inputFee, 18)
        XCTAssertEqual(plan.total, 100)
        XCTAssertEqual(world.records.map(\.state), [.draft])
    }

    func testMaxOnAZeroFeeSourceUsesAnOrdinaryMelt() async throws {
        let world = World()
        world.maxQuote = (amount: 98, feeReserve: 2, inputFee: 0)

        let plan = try await world.engine.prepareMax(from: sourceURL, to: destinationURL)

        XCTAssertEqual(plan.selection, .automatic)
        XCTAssertEqual(plan.inputFee, 0)
        XCTAssertEqual(plan.total, 100)
    }

    func testMaxFromAnEmptyMintQuotesNothing() async {
        let world = World(sourceBalance: 0)

        await assertThrows(MintTransferError.nothingToTransfer) {
            _ = try await world.engine.prepareMax(from: self.sourceURL, to: self.destinationURL)
        }

        XCTAssertTrue(world.quotedAt.isEmpty)
    }

    // MARK: - Executing

    func testExecutePaysThenIssuesAndCompletesTheRecord() async throws {
        let world = World()
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)
        var stages: [MintTransferStage] = []

        let outcome = try await world.engine.execute(plan) { stages.append($0) }

        XCTAssertEqual(outcome, .completed(amount: 40, feePaid: 1))
        XCTAssertEqual(stages, [.paying, .issuing])
        XCTAssertEqual(world.melts.map(\.quoteID), [plan.meltQuoteID])
        XCTAssertEqual(world.melts.map(\.mintURL), [sourceURL])
        XCTAssertEqual(world.records.map(\.state), [.completed])
    }

    func testMaxPlanMeltsWithItsPlannedSelection() async throws {
        let world = World()
        world.maxQuote = (amount: 80, feeReserve: 2, inputFee: 18)
        let plan = try await world.engine.prepareMax(from: sourceURL, to: destinationURL)

        _ = try await world.engine.execute(plan)

        XCTAssertEqual(world.melts.map(\.selection), [.allUnspentSkippingSwap(expectedInputFee: 18)])
    }

    /// The payment went out but the destination is slow. The record stays
    /// committed so the sweeps keep checking the quote.
    func testSlowIssuanceHandsOverToTheSweepsStillCommitted() async throws {
        let world = World()
        world.issues = false
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)

        let outcome = try await world.engine.execute(plan)

        XCTAssertEqual(outcome, .settling(.issuance))
        XCTAssertEqual(world.issueAttempts, MintTransferEngine.issuanceAttempts)
        XCTAssertEqual(world.records.map(\.state), [.committed])
        XCTAssertEqual(world.mintQuotes.count, 1)
    }

    func testPendingPaymentDoesNotAttemptIssuance() async throws {
        let world = World()
        world.meltSettlement = .pending
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)

        let outcome = try await world.engine.execute(plan)

        XCTAssertEqual(outcome, .settling(.payment))
        XCTAssertEqual(world.issueAttempts, 0)
        XCTAssertEqual(world.records.map(\.state), [.committed])
    }

    /// The wallet can believe a payment failed that the mint went on to make,
    /// so a melt that ran keeps its destination quote even when it reports the
    /// funds as returned.
    func testReturnedPaymentKeepsTheDestinationQuote() async throws {
        let world = World()
        world.meltError = MeltPaymentRecoveryError.compensated(operationID: "op")
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)

        await assertThrows(MintTransferError.paymentReturned) {
            _ = try await world.engine.execute(plan)
        }

        XCTAssertEqual(world.records.map(\.state), [.failed])
        XCTAssertEqual(Array(world.mintQuotes.keys), [plan.mintQuoteID])
    }

    func testUnknownPaymentOutcomeStaysCommitted() async throws {
        let world = World()
        world.meltError = MeltPaymentRecoveryError.unresolved(quoteID: "q", mintURL: sourceURL, operationID: "op")
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)

        do {
            _ = try await world.engine.execute(plan)
            XCTFail("An unresolved melt must surface")
        } catch let error as MeltPaymentRecoveryError {
            XCTAssertNotNil(error.unresolvedQuote)
        }

        XCTAssertEqual(world.records.map(\.state), [.committed])
        XCTAssertEqual(world.mintQuotes.count, 1)
    }

    /// A melt that never reached the mint leaves nothing behind at all.
    func testMeltThatNeverStartedIsForgottenEntirely() async throws {
        let world = World()
        world.meltError = WalletError.networkError("offline")
        world.meltLeavesEvidence = false
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)

        do {
            _ = try await world.engine.execute(plan)
            XCTFail("The melt error must surface")
        } catch is WalletError {}

        XCTAssertTrue(world.records.isEmpty)
        XCTAssertTrue(world.mintQuotes.isEmpty)
    }

    func testChangedInputFeeReportsAStalePlan() async throws {
        let world = World()
        world.maxQuote = (amount: 80, feeReserve: 2, inputFee: 18)
        world.meltError = MeltInputFeeChanged(expected: 18, actual: 19)
        world.meltLeavesEvidence = false
        let plan = try await world.engine.prepareMax(from: sourceURL, to: destinationURL)

        await assertThrows(MintTransferError.planStale) {
            _ = try await world.engine.execute(plan)
        }

        XCTAssertTrue(world.records.isEmpty)
    }

    func testAPlanCannotBeExecutedTwice() async throws {
        let world = World()
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)
        _ = try await world.engine.execute(plan)

        await assertThrows(MintTransferError.planStale) {
            _ = try await world.engine.execute(plan)
        }

        XCTAssertEqual(world.melts.count, 1)
    }

    func testExpiredPlanIsDiscardedUnpaid() async throws {
        let world = World()
        world.quoteExpiry = 2_000
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)
        world.now = Date(timeIntervalSince1970: 2_000)

        await assertThrows(MintTransferError.planExpired) {
            _ = try await world.engine.execute(plan)
        }

        XCTAssertTrue(world.melts.isEmpty)
        XCTAssertTrue(world.records.isEmpty)
        XCTAssertTrue(world.mintQuotes.isEmpty)
    }

    // MARK: - Discarding

    func testDiscardingADraftRemovesItsQuotes() async throws {
        let world = World()
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)

        await world.engine.discard(plan)

        XCTAssertTrue(world.records.isEmpty)
        XCTAssertTrue(world.mintQuotes.isEmpty)
        XCTAssertTrue(world.meltQuotes.isEmpty)
    }

    func testDiscardingAfterCommitRemovesNothing() async throws {
        let world = World()
        world.issues = false
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)
        _ = try await world.engine.execute(plan)

        await world.engine.discard(plan)

        XCTAssertEqual(world.records.map(\.state), [.committed])
        XCTAssertEqual(world.mintQuotes.count, 1)
    }

    /// If the quotes cannot be removed because the melt shows signs of having
    /// started, the draft is promoted so its destination quote stays tracked.
    func testDraftWithAStartedMeltIsPromotedInsteadOfDiscarded() async throws {
        let world = World()
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)
        world.startedMelts.insert(plan.meltQuoteID)

        await world.engine.discard(plan)

        XCTAssertEqual(world.records.map(\.state), [.committed])
        XCTAssertEqual(world.mintQuotes.count, 1)
    }

    func testOnlyDraftsFromBeforeThisLaunchAreAbandoned() async throws {
        let world = World()
        let old = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)
        world.now = Date(timeIntervalSince1970: 1_500)
        let live = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 21)

        await world.engine.discardAbandonedDrafts(before: Date(timeIntervalSince1970: 1_200))

        XCTAssertEqual(world.records.map(\.id), [live.id])
        XCTAssertEqual(Array(world.mintQuotes.keys), [live.mintQuoteID])
        XCTAssertNotEqual(old.id, live.id)
    }

    // MARK: - Maintenance

    func testCommittedTransferWhoseMeltEndedUnpaidIsFailed() async throws {
        let world = World()
        world.meltSettlement = .pending
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)
        _ = try await world.engine.execute(plan)
        world.endedUnpaid.insert(plan.meltQuoteID)

        await world.engine.failEndedTransfers(excluding: [])

        XCTAssertEqual(world.records.map(\.state), [.failed])
    }

    /// A transfer that is executing has committed but may not have reached its
    /// melt yet, which reads the same as one that ended unpaid.
    func testTransferInFlightIsNotFailed() async throws {
        let world = World()
        world.meltSettlement = .pending
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)
        _ = try await world.engine.execute(plan)
        world.endedUnpaid.insert(plan.meltQuoteID)

        await world.engine.failEndedTransfers(excluding: [plan.id])

        XCTAssertEqual(world.records.map(\.state), [.committed])
    }

    func testLateIssuanceCompletesEvenAFailedTransfer() async throws {
        let world = World()
        world.meltError = MeltPaymentRecoveryError.compensated(operationID: "op")
        let plan = try await world.engine.prepare(from: sourceURL, to: destinationURL, amount: 40)
        _ = try? await world.engine.execute(plan)
        XCTAssertEqual(world.records.map(\.state), [.failed])

        world.engine.noteIssued(quoteID: plan.mintQuoteID)

        XCTAssertEqual(world.records.map(\.state), [.completed])
    }

    // MARK: - Harness

    private func assertThrows(
        _ expected: MintTransferError,
        file: StaticString = #filePath,
        line: UInt = #line,
        _ operation: () async throws -> Void
    ) async {
        do {
            try await operation()
            XCTFail("Expected \(expected)", file: file, line: line)
        } catch let error as MintTransferError {
            XCTAssertEqual(error, expected, file: file, line: line)
        } catch {
            XCTFail("Expected \(expected), got \(error)", file: file, line: line)
        }
    }

    /// Two held mints and the quotes, melts and records the engine makes.
    @MainActor
    private final class World {
        enum Quoted: Equatable { case mint(String), melt(String), max(String, String) }
        struct Melt { let quoteID: String; let mintURL: String; let selection: MeltProofSelection }

        var mints: [MintInfo]
        var records: [MintTransferRecord] = []
        var mintQuotes: [String: MintQuoteInfo] = [:]
        var meltQuotes: [String: MeltQuoteInfo] = [:]
        var quotedAt: [Quoted] = []
        var melts: [Melt] = []
        var startedMelts: Set<String> = []
        var endedUnpaid: Set<String> = []
        var issueAttempts = 0
        var now = Date(timeIntervalSince1970: 1_000)

        var meltQuoteError: Error?
        var meltQuoteAmount: UInt64?
        var quoteExpiry: UInt64?
        var maxQuote: (amount: UInt64, feeReserve: UInt64, inputFee: UInt64) = (98, 2, 0)
        var meltError: Error?
        /// Whether a failing melt got far enough to leave a trace locally.
        var meltLeavesEvidence = true
        var meltSettlement: MeltPaymentResult.Settlement = .settled
        var issues = true

        private var sequence = 0

        init(sourceBalance: UInt64 = 100) {
            mints = [
                MintInfo(url: "https://source.example", name: "Source", isActive: true, balance: sourceBalance),
                MintInfo(url: "https://destination.example", name: "Destination", isActive: false, balance: 0),
            ]
        }

        private func nextID(_ prefix: String) -> String {
            sequence += 1
            return "\(prefix)-\(sequence)"
        }

        private func mintQuote(amount: UInt64, mintURL: String) -> MintQuoteInfo {
            let quote = MintQuoteInfo(
                id: nextID("mint"), request: "lnbc\(amount)", amount: amount, isAmountless: false,
                paymentMethod: .bolt11, state: .pending, expiry: quoteExpiry, createdAt: nil,
                unit: "sat", mintURL: mintURL
            )
            mintQuotes[quote.id] = quote
            return quote
        }

        private func meltQuote(amount: UInt64, feeReserve: UInt64, mintURL: String) -> MeltQuoteInfo {
            let quote = MeltQuoteInfo(
                id: nextID("melt"), mintUrl: mintURL, amount: amount, feeReserve: feeReserve,
                paymentMethod: .bolt11, state: .unpaid, expiry: quoteExpiry
            )
            meltQuotes[quote.id] = quote
            return quote
        }

        var engine: MintTransferEngine {
            MintTransferEngine(
                mints: { self.mints },
                createMintQuote: { amount, mintURL in
                    self.quotedAt.append(.mint(mintURL))
                    return self.mintQuote(amount: amount, mintURL: mintURL)
                },
                createMeltQuote: { request, mintURL in
                    self.quotedAt.append(.melt(mintURL))
                    if let error = self.meltQuoteError { throw error }
                    let invoiced = self.mintQuotes.values.first { $0.request == request }?.amount ?? 0
                    return self.meltQuote(amount: self.meltQuoteAmount ?? invoiced, feeReserve: 2, mintURL: mintURL)
                },
                createMaxQuotes: { sourceMintURL, destinationMintURL in
                    self.quotedAt.append(.max(sourceMintURL, destinationMintURL))
                    return CrossMintQuotes(
                        mintQuote: self.mintQuote(amount: self.maxQuote.amount, mintURL: destinationMintURL),
                        meltQuote: self.meltQuote(
                            amount: self.maxQuote.amount, feeReserve: self.maxQuote.feeReserve, mintURL: sourceMintURL
                        ),
                        inputFee: self.maxQuote.inputFee
                    )
                },
                melt: { quoteID, mintURL, selection in
                    self.melts.append(Melt(quoteID: quoteID, mintURL: mintURL, selection: selection))
                    if let error = self.meltError {
                        if self.meltLeavesEvidence { self.startedMelts.insert(quoteID) }
                        throw error
                    }
                    self.startedMelts.insert(quoteID)
                    return MeltPaymentResult(
                        preimage: nil, amount: self.meltQuotes[quoteID]?.amount ?? 0, feePaid: 1,
                        mintUrl: mintURL, settlement: self.meltSettlement
                    )
                },
                issue: { _ in
                    self.issueAttempts += 1
                    return self.issues
                },
                removeUnusedQuotes: { mintQuoteID, meltQuoteID in
                    if let meltQuoteID, self.startedMelts.contains(meltQuoteID) { return false }
                    self.mintQuotes[mintQuoteID] = nil
                    if let meltQuoteID { self.meltQuotes[meltQuoteID] = nil }
                    return true
                },
                meltEndedUnpaid: { meltQuoteID in
                    meltQuoteID.map { self.endedUnpaid.contains($0) } ?? false
                },
                loadRecords: { self.records },
                saveRecords: { self.records = $0 },
                now: { self.now },
                makeID: { self.nextID("transfer") },
                pause: { _ in }
            )
        }
    }
}

/// Android parity: `MintTransferModelsTest.kt`.
final class MintTransferModelTests: XCTestCase {

    // MARK: - How a max plan melts

    func testZeroFeeSourceNeverNeedsTheSkipSwapMelt() {
        XCTAssertEqual(
            MintTransferPlan.maxMeltSelection(balance: 100, amount: 98, feeReserve: 2, inputFee: 0),
            .automatic
        )
    }

    /// The quote used the whole balance, so only melting exactly the proofs it
    /// counted fits.
    func testBalanceBoundMaxOnAFeeChargingSourceSkipsTheSwap() {
        XCTAssertEqual(
            MintTransferPlan.maxMeltSelection(balance: 100, amount: 80, feeReserve: 2, inputFee: 18),
            .allUnspentSkippingSwap(expectedInputFee: 18)
        )
    }

    /// A mint limit capped the amount well below the balance. There is room for
    /// an ordinary melt's own fees, and no reason to route the whole balance
    /// through one payment.
    func testLimitBoundMaxLeavesRoomForAnOrdinaryMelt() {
        XCTAssertEqual(
            MintTransferPlan.maxMeltSelection(balance: 1_000_000, amount: 500_000, feeReserve: 5_000, inputFee: 20),
            .automatic
        )
    }

    func testSlackSmallerThanASwapAndAMeltStillSkipsTheSwap() {
        XCTAssertEqual(
            MintTransferPlan.maxMeltSelection(balance: 139, amount: 100, feeReserve: 2, inputFee: 18),
            .allUnspentSkippingSwap(expectedInputFee: 18)
        )
        XCTAssertEqual(
            MintTransferPlan.maxMeltSelection(balance: 156, amount: 100, feeReserve: 2, inputFee: 18),
            .automatic
        )
    }

    func testPlanTotalsAddTheFeeUpperBound() {
        let plan = plan(amount: 80, feeReserve: 2, inputFee: 18, expiresAt: Date(timeIntervalSince1970: 100))

        XCTAssertEqual(plan.feeUpperBound, 20)
        XCTAssertEqual(plan.total, 100)
        XCTAssertFalse(plan.isExpired(at: Date(timeIntervalSince1970: 99)))
        XCTAssertTrue(plan.isExpired(at: Date(timeIntervalSince1970: 100)))
    }

    // MARK: - Records

    func testQuoteOwnershipByState() {
        let records = [
            record("draft", state: .draft), record("committed", state: .committed),
            record("completed", state: .completed), record("failed", state: .failed),
        ]

        XCTAssertEqual(records.ownedMintQuoteIDs, ["mint-draft", "mint-committed", "mint-completed", "mint-failed"])
        XCTAssertEqual(records.draftMintQuoteIDs, ["mint-draft"])
        XCTAssertEqual(records.awaitingIssuanceMintQuoteIDs, ["mint-committed"])
    }

    /// Only a transfer that was paid for and has not finished ties a mint down.
    func testOnlyCommittedTransfersBlockMintRemoval() {
        let committed = [record("a", state: .committed)]

        XCTAssertTrue(committed.hasUnfinishedTransfer(referencing: "https://Source.example:443/"))
        XCTAssertTrue(committed.hasUnfinishedTransfer(referencing: "https://destination.example"))
        XCTAssertFalse(committed.hasUnfinishedTransfer(referencing: "https://other.example"))
        for state in [MintTransferRecord.State.draft, .completed, .failed] {
            XCTAssertFalse([record("a", state: state)].hasUnfinishedTransfer(referencing: "https://source.example"))
        }
    }

    func testUpsertingReplacesByIDAndKeepsNewestFirst() {
        let older = record("a", state: .draft, createdAt: 10)
        let newer = record("b", state: .draft, createdAt: 20)
        var updated = older
        updated.state = .committed

        let records = [older].upserting(newer).upserting(updated)

        XCTAssertEqual(records.map(\.id), ["b", "a"])
        XCTAssertEqual(records.last?.state, .committed)
    }

    /// Dropping an unfinished record would orphan a quote that may be paid.
    func testLimitDropsOnlyFinishedRecords() {
        let limit = [MintTransferRecord].limit
        var records: [MintTransferRecord] = (0..<limit).map {
            record("done-\($0)", state: .completed, createdAt: TimeInterval(1_000 + $0))
        }
        records = records.upserting(record("waiting", state: .committed, createdAt: 1))
        records = records.upserting(record("newest", state: .completed, createdAt: 5_000))

        XCTAssertEqual(records.filter { $0.state == .completed }.count, limit)
        XCTAssertTrue(records.contains { $0.id == "waiting" })
        XCTAssertTrue(records.contains { $0.id == "newest" })
        XCTAssertFalse(records.contains { $0.id == "done-0" })
    }

    func testRecordsSurvivePersistenceAndAreWipedWithTheWallet() {
        let store = WalletStore(storage: InMemoryStorage())
        var halfQuoted = record("a", state: .draft)
        halfQuoted.meltQuoteID = nil

        store.saveMintTransfers([halfQuoted, record("b", state: .committed)])

        XCTAssertEqual(store.loadMintTransfers(), [halfQuoted, record("b", state: .committed)])
        store.removeAllWalletData()
        XCTAssertTrue(store.loadMintTransfers().isEmpty)
    }

    // MARK: - Messages

    func testTransferErrorsHaveTheirOwnCopy() {
        XCTAssertEqual(MintTransferError.notEligible(.sameMint).userFacingWalletMessage, "Choose two different mints.")
        XCTAssertEqual(MintTransferError.nothingToTransfer.userFacingWalletMessage, "Nothing left to transfer after fees.")
        XCTAssertEqual(MintTransferError.planExpired.userFacingWalletMessage, "This quote expired. Review the transfer again.")
        XCTAssertEqual(
            MintTransferError.paymentReturned.userFacingWalletMessage,
            "The transfer didn't go through. Your funds were not moved."
        )
        XCTAssertTrue(MintTransferError.insufficientBalance(required: 42, available: 40).isInsufficientBalanceError)
        XCTAssertEqual(
            MintRemovalPolicyError.transferInProgress.userFacingWalletMessage,
            "A transfer involving this mint is still settling. Keep it connected until the transfer finishes."
        )
    }

    // MARK: - Fixtures

    private func plan(amount: UInt64, feeReserve: UInt64, inputFee: UInt64, expiresAt: Date?) -> MintTransferPlan {
        MintTransferPlan(
            id: "plan", mode: .max,
            sourceMintURL: "https://source.example", destinationMintURL: "https://destination.example",
            mintQuoteID: "mint", meltQuoteID: "melt",
            amount: amount, feeReserve: feeReserve, inputFee: inputFee,
            selection: .allUnspentSkippingSwap(expectedInputFee: inputFee), expiresAt: expiresAt
        )
    }

    private func record(
        _ id: String,
        state: MintTransferRecord.State,
        createdAt: TimeInterval = 0
    ) -> MintTransferRecord {
        MintTransferRecord(
            id: id,
            sourceMintURL: "https://source.example", destinationMintURL: "https://destination.example",
            mintQuoteID: "mint-\(id)", meltQuoteID: "melt-\(id)",
            amount: 40, createdAt: Date(timeIntervalSince1970: createdAt), state: state
        )
    }
}
