import XCTest
import Cdk
@testable import CashuWallet

@MainActor
final class AbandonedReservationRecoveryTests: XCTestCase {
    func testReturnsHeldFundsOnceAndLeavesCurrentReservationsAlone() async throws {
        let world = World()
        let returned = try await world.recovery.recover(mintURL: World.mint, unit: "sat")
        XCTAssertEqual(returned, ["old"])
        let second = try await world.recovery.recover(mintURL: World.mint, unit: "sat")
        XCTAssertTrue(second.isEmpty)
        XCTAssertEqual(world.released, ["old"])
        XCTAssertEqual(world.proofs.compactMap(\.owner), ["new"])
    }

    func testLiveOperationsAndPaymentsRemainHeld() async throws {
        for blocker in ["operation", "payment", "activity"] {
            let world = World()
            world.blocker = blocker
            let returned = try await world.recovery.recover(mintURL: World.mint, unit: "sat")
            XCTAssertTrue(returned.isEmpty)
            XCTAssertTrue(world.released.isEmpty)
        }
    }

    func testPendingProofsAndChangedOwnershipRemainHeld() async throws {
        let world = World()
        world.unspent = false
        let pending = try await world.recovery.recover(mintURL: World.mint, unit: "sat")
        XCTAssertTrue(pending.isEmpty)
        world.unspent = true
        world.onCheck = { world.proofs = [] }
        let changed = try await world.recovery.recover(mintURL: World.mint, unit: "sat")
        XCTAssertTrue(changed.isEmpty)
        XCTAssertTrue(world.released.isEmpty)
    }

    func testRequiresCompleteExplicitUnspentStates() {
        func check(_ states: String) -> Bool {
            AbandonedReservationRecovery.allUnspent(["a", "b"], response: Data("{\"states\":[\(states)]}".utf8))
        }
        XCTAssertTrue(check(#"{"Y":"b","state":"UNSPENT"},{"Y":"a","state":"UNSPENT"}"#))
        XCTAssertFalse(check(#"{"Y":"a","state":"UNSPENT"}"#))
        XCTAssertFalse(check(#"{"Y":"a","state":"UNSPENT"},{"Y":"b","state":"PENDING"}"#))
        XCTAssertFalse(check(#"{"Y":"a","state":"UNSPENT"},{"Y":"b","state":"SPENT"}"#))
        XCTAssertFalse(check(#"{"Y":"a","state":"UNSPENT"},{"Y":"a","state":"UNSPENT"}"#))
    }

    func testNetworkFailureAndMixedAccountsKeepFundsHeld() async throws {
        let world = World()
        world.offline = true
        do {
            _ = try await world.recovery.recover(mintURL: World.mint, unit: "sat")
            XCTFail("A failed mint check cannot release funds")
        } catch { }
        XCTAssertTrue(world.released.isEmpty)
        world.offline = false
        world.proofs.append(ReservationProof(id: "other", owner: "old", mintURL: World.mint, unit: "usd", reserved: true))
        let mixed = try await world.recovery.recover(mintURL: World.mint, unit: "sat")
        XCTAssertTrue(mixed.isEmpty)
        XCTAssertTrue(world.released.isEmpty)
    }

    private final class World {
        static let mint = "https://mint.example"
        var proofs = [
            ReservationProof(id: "a", owner: "old", mintURL: mint, unit: "sat", reserved: true),
            ReservationProof(id: "b", owner: "new", mintURL: mint, unit: "sat", reserved: true)
        ]
        var blocker = ""
        var unspent = true
        var offline = false
        var onCheck: () -> Void = {}
        var released: [String] = []
        var recovery: AbandonedReservationRecovery {
            AbandonedReservationRecovery(
                eligibleOwners: ["old"], loadProofs: { self.proofs },
                hasOperation: { _ in self.blocker == "operation" },
                hasTransaction: { _ in self.blocker == "payment" },
                hasActivity: { _, _ in self.blocker == "activity" },
                checkUnspent: { _, _ in
                    if self.offline { throw URLError(.timedOut) }
                    self.onCheck()
                    return self.unspent
                },
                release: { owner in self.released.append(owner); self.proofs.removeAll { $0.owner == owner } }
            )
        }
    }
}

/// The bounded settlement wait for async (NUT-05) lightning melts: CDK's
/// `PendingMelt.wait()` does the polling; the app only races it against a
/// fallback cap. These drive the race with injected closures — no CDK runtime
/// needed, since `FinalizedMelt` is a plain struct with a public initializer.
@MainActor
final class MeltSettlementWaitTests: XCTestCase {

    private func makeService() -> LightningService {
        LightningService(
            walletRepository: { nil },
            walletDatabase: { nil },
            getActiveMint: { nil }
        )
    }

    private func finalized(state: QuoteState = .paid) -> FinalizedMelt {
        FinalizedMelt(
            quoteId: "quote",
            state: state,
            preimage: "preimage",
            change: nil,
            amount: Amount(value: 10),
            feePaid: Amount(value: 2)
        )
    }

    func testFastSettlementWinsTheRace() async {
        let outcome = await makeService().awaitMeltSettlementBounded(cap: .seconds(5)) {
            self.finalized()
        }
        guard case .finalized(let value) = outcome else {
            return XCTFail("expected .finalized, got \(outcome)")
        }
        XCTAssertEqual(value.preimage, "preimage")
        XCTAssertEqual(value.feePaid.value, 2)
    }

    func testThrownWaitReportsFailure() async {
        struct WaitError: Error {}
        let outcome = await makeService().awaitMeltSettlementBounded(cap: .seconds(5)) {
            throw WaitError()
        }
        guard case .failed = outcome else {
            return XCTFail("expected .failed, got \(outcome)")
        }
    }

    func testCapExpiryHandsBackTheResidualWatcher() async throws {
        let outcome = await makeService().awaitMeltSettlementBounded(cap: .milliseconds(50)) {
            // Outlives the cap, then settles — the residual watcher must still
            // deliver the finalized melt afterwards.
            try await Task.sleep(for: .milliseconds(300))
            return self.finalized()
        }
        guard case .capExpired(let residual) = outcome else {
            return XCTFail("expected .capExpired, got \(outcome)")
        }
        let settled = try await residual.value
        XCTAssertEqual(settled.state, .paid)
    }

    /// The finish and the cap landing together must resume exactly once —
    /// whichever outcome wins is acceptable; a double resume would crash.
    func testNearSimultaneousFinishAndCapResumesOnce() async {
        let outcome = await makeService().awaitMeltSettlementBounded(cap: .milliseconds(50)) {
            try await Task.sleep(for: .milliseconds(50))
            return self.finalized()
        }
        switch outcome {
        case .finalized, .capExpired:
            break
        case .failed(let error):
            XCTFail("unexpected failure: \(error)")
        }
    }

    func testUncancellableResidualBlocksWalletBoundaryUntilNativeWaitReturns() async throws {
        let service = makeService()
        let (residual, finish) = await holdSettlement(service: service)

        XCTAssertThrowsError(try service.requireNoActiveMeltSettlement())
        service.clearState()
        residual.cancel()
        XCTAssertThrowsError(try service.requireNoActiveMeltSettlement(),
                             "Clearing UI state or cancelling Swift must not hide a live native wait")

        finish.resume(returning: finalized())
        _ = try await residual.value
        XCTAssertNoThrow(try service.requireNoActiveMeltSettlement())
    }

    func testWalletBoundaryWaitsForEveryNativeSettlement() async throws {
        struct WaitError: Error {}
        let service = makeService()
        let (residual, finish) = await holdSettlement(service: service)
        let (otherResidual, finishOther) = await holdSettlement(service: service)
        XCTAssertThrowsError(try service.requireNoActiveMeltSettlement())

        finish.resume(throwing: WaitError())
        _ = await residual.result
        XCTAssertThrowsError(try service.requireNoActiveMeltSettlement(),
                             "A failed wait must not release another live settlement")

        finishOther.resume(returning: finalized())
        _ = try await otherResidual.value
        XCTAssertNoThrow(try service.requireNoActiveMeltSettlement())
    }

    func testCreateAndDeleteRejectActiveSettlementBeforeTouchingWalletState() async throws {
        let manager = WalletManager()
        let (residual, finish) = await holdSettlement(service: manager.lightningService)

        do {
            try await manager.createNewWallet()
            XCTFail("Creation must not replace files while a native settlement is active")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("still settling"))
        }
        do {
            try await manager.deleteWallet()
            XCTFail("Deletion must not remove files while a native settlement is active")
        } catch {
            XCTAssertTrue(error.localizedDescription.contains("still settling"))
        }
        XCTAssertNil(manager.walletRepository)
        XCTAssertFalse(manager.isLoading)

        finish.resume(returning: finalized())
        _ = try await residual.value
    }

    private func holdSettlement(service: LightningService) async -> (
        Task<FinalizedMelt, any Error>, CheckedContinuation<FinalizedMelt, any Error>
    ) {
        let started = expectation(description: "Native settlement started")
        var finish: CheckedContinuation<FinalizedMelt, any Error>?
        let race = Task {
            await service.awaitMeltSettlementBounded(cap: .zero) {
                try await withCheckedThrowingContinuation { continuation in
                    finish = continuation
                    started.fulfill()
                }
            }
        }
        await fulfillment(of: [started], timeout: 2)
        guard case .capExpired(let residual) = await race.value, let finish else {
            preconditionFailure("Held settlement must outlive the cap")
        }
        return (residual, finish)
    }
}

@MainActor
final class MeltPaymentSummaryTests: XCTestCase {
    private let quote = MeltQuoteInfo(
        id: "quote", mintUrl: "https://mint.example", amount: 21, feeReserve: 5,
        paymentMethod: .bolt11, state: .unpaid, expiry: nil
    )

    private func summary(fee: UInt64?, settlement: MeltPaymentResult.Settlement = .settled,
                         amount: UInt64 = 21) -> MeltPaymentSummary {
        MeltPaymentSummary(quote: quote, result: MeltPaymentResult(
            preimage: nil, amount: amount, feePaid: fee, mintUrl: quote.mintUrl, settlement: settlement
        ))
    }

    private func sats(_ amount: UInt64) -> String { "\(amount) sat" }

    func testConfirmationDisclosesAdditionalMintFees() {
        let details = MeltPaymentSummary(quote: quote)
        XCTAssertEqual(details.fee, 5)
        XCTAssertEqual(details.total, 26)
        XCTAssertEqual(details.feeText(format: sats), "Up to 5 sat")
        XCTAssertEqual(details.totalLabel, "Amount + fee reserve")
        XCTAssertEqual(details.totalText(format: sats), "26 sat + mint fees")
    }

    func testSettledReceiptUsesActualFeeAndDebitInsteadOfReserve() {
        let details = summary(fee: 2)
        XCTAssertEqual(details.feeText(format: sats), "2 sat")
        XCTAssertEqual(details.total, 23)
        XCTAssertEqual(details.totalLabel, "Total")
        XCTAssertEqual(details.totalText(format: sats), "23 sat")
        XCTAssertFalse(details.isUpperBound)
    }

    func testPendingReceiptUsesReturnedUpperBoundIncludingInputFees() {
        let details = summary(fee: 7, settlement: .pending)
        XCTAssertEqual(details.feeText(format: sats), "Up to 7 sat")
        XCTAssertEqual(details.totalText(format: sats), "Up to 28 sat")
        XCTAssertEqual(details.totalLabel, "Total")
    }

    func testZeroActualFeeIsNotReplacedByReserve() {
        let details = summary(fee: 0)
        XCTAssertEqual(details.feeText(format: sats), "No fee")
        XCTAssertEqual(details.total, 21)
    }

    func testRecoveredPaymentWithUnknownFeeHasNoInventedFeeOrDebit() {
        let details = summary(fee: nil)
        XCTAssertNil(details.fee)
        XCTAssertNil(details.total)
        XCTAssertEqual(details.feeText(format: sats), "Unavailable")
        XCTAssertEqual(details.totalText(format: sats), "Unavailable")
        XCTAssertFalse(details.isUpperBound)
    }

    func testReceiptUsesReturnedAmount() {
        XCTAssertEqual(summary(fee: 2, amount: 20).total, 22)
    }

    func testSettledFeeIncludesPreparationSwapAndPendingBoundIncludesAllInputFees() {
        let paidFee = MeltFeeAccounting.adding(3, 2)
        XCTAssertEqual(summary(fee: paidFee).totalText(format: sats), "26 sat")
        let pendingFee = MeltFeeAccounting.adding(quote.feeReserve, 4)
        XCTAssertEqual(summary(fee: pendingFee, settlement: .pending).totalText(format: sats), "Up to 30 sat")
        XCTAssertEqual(MeltFeeAccounting.adding(3, 0), 3, "Skip-swap must not add an extra fee")
    }

    func testRecoveryWithUnknownPreparationCostsAndFeeOverflowHasNoInventedDebit() {
        for fee in [MeltFeeAccounting.adding(2, nil), MeltFeeAccounting.adding(nil, 2),
                    MeltFeeAccounting.adding(UInt64.max, 1)] {
            XCTAssertEqual(summary(fee: fee).totalText(format: sats), "Unavailable")
        }
    }

    func testOverflowDoesNotDisplayAnIncorrectTotal() {
        let details = summary(fee: 1, amount: .max)
        XCTAssertNil(details.total)
        XCTAssertEqual(details.totalText(format: sats), "Unavailable")
    }
}
