import Cdk
import XCTest
@testable import CashuWallet

final class RestoreCheckpointTests: XCTestCase {
    private let mint = "https://mint.example"

    func testAdvancingSumsAmountsAndKeepsPositionsMonotonic() {
        let first = MintRestoreCheckpoint(
            nextKeysetIndices: ["a": 300, "b": 100],
            spent: 5, unspent: 10, pending: 1
        )
        let next = first.advanced(
            spent: 2, unspent: 20, pending: 0,
            nextKeysetIndices: ["a": 200, "b": 450, "c": 7]
        )
        XCTAssertEqual(next.nextKeysetIndices, ["a": 300, "b": 450, "c": 7])
        XCTAssertEqual(next.spent, 7)
        XCTAssertEqual(next.unspent, 30)
        XCTAssertEqual(next.pending, 1)
    }

    func testCheckpointRoundTripsThroughWalletStoreScopedByNormalizedMint() {
        let store = WalletStore(storage: InMemoryStorage())
        XCTAssertNil(store.restoreCheckpoint(url: mint))

        let checkpoint = MintRestoreCheckpoint(nextKeysetIndices: ["a": 42], spent: 1, unspent: 2, pending: 3)
        store.saveRestoreCheckpoint(checkpoint, url: mint)
        XCTAssertEqual(store.restoreCheckpoint(url: "https://mint.example/"), checkpoint)
        XCTAssertNil(store.restoreCheckpoint(url: "https://other.example"))

        store.saveRestoreCheckpoint(nil, url: mint)
        XCTAssertNil(store.restoreCheckpoint(url: mint))
    }

    func testClearingOneMintKeepsOthers() {
        let store = WalletStore(storage: InMemoryStorage())
        let first = MintRestoreCheckpoint(nextKeysetIndices: ["a": 1], spent: 0, unspent: 1, pending: 0)
        let second = MintRestoreCheckpoint(nextKeysetIndices: ["b": 2], spent: 0, unspent: 2, pending: 0)
        store.saveRestoreCheckpoint(first, url: mint)
        store.saveRestoreCheckpoint(second, url: "https://other.example")
        store.saveRestoreCheckpoint(nil, url: mint)
        XCTAssertNil(store.restoreCheckpoint(url: mint))
        XCTAssertEqual(store.restoreCheckpoint(url: "https://other.example"), second)
    }

    func testCheckpointsAreWalletScopedAndDroppedWithWalletData() {
        let storage = InMemoryStorage()
        let store = WalletStore(storage: storage)
        store.saveRestoreCheckpoint(
            MintRestoreCheckpoint(nextKeysetIndices: ["a": 1], spent: 0, unspent: 1, pending: 0),
            url: mint
        )
        XCTAssertTrue(StorageKeys.restoreCheckpoints.hasPrefix(StorageKeys.walletDataPrefix))
        store.removeAllWalletData()
        XCTAssertNil(store.restoreCheckpoint(url: mint))
    }

    func testInterruptedRestoreMessageTellsUserProgressIsSaved() {
        let error = MintRestoreInterrupted(
            checkpoint: MintRestoreCheckpoint(nextKeysetIndices: [:], spent: 0, unspent: 0, pending: 0),
            reason: "timeout"
        )
        XCTAssertTrue(error.userFacingWalletMessage.contains("progress is saved"))
    }

    // MARK: - ResumableMintRestore

    private func restored(_ spent: UInt64, _ unspent: UInt64, _ pending: UInt64, _ next: [String: UInt32]) -> Restored {
        Restored(spent: Amount(value: spent), unspent: Amount(value: unspent), pending: Amount(value: pending), nextKeysetIndices: next)
    }

    func testFirstAttemptStartsFromZeroAndClearsCheckpointOnSuccess() async throws {
        let store = WalletStore(storage: InMemoryStorage())
        var seenStart: RestoreStart?? = .none
        let totals = try await ResumableMintRestore.run(store: store, mintURL: mint) { options in
            seenStart = .some(options.start)
            XCTAssertEqual(options.batchSize, 100)
            XCTAssertEqual(options.maxGap, 3)
            return self.restored(1, 2, 3, ["a": 10])
        }
        XCTAssertNil(seenStart!)
        XCTAssertEqual(totals.spent, 1)
        XCTAssertEqual(totals.unspent, 2)
        XCTAssertEqual(totals.pending, 3)
        XCTAssertNil(store.restoreCheckpoint(url: mint))
    }

    func testInterruptedRestoreSavesPositionsAndNextAttemptResumesAndSums() async throws {
        let store = WalletStore(storage: InMemoryStorage())

        do {
            _ = try await ResumableMintRestore.run(store: store, mintURL: mint) { _ in
                throw FfiError.RestoreInterrupted(
                    partial: self.restored(4, 40, 0, ["a": 500]),
                    code: 50000,
                    errorMessage: "connection lost"
                )
            }
            XCTFail("Expected an interrupted restore")
        } catch let error as MintRestoreInterrupted {
            XCTAssertEqual(error.checkpoint.nextKeysetIndices, ["a": 500])
            XCTAssertEqual(error.reason, "connection lost")
        }
        XCTAssertEqual(store.restoreCheckpoint(url: mint)?.unspent, 40)

        var resumedFrom: [String: UInt32]?
        let totals = try await ResumableMintRestore.run(store: store, mintURL: mint) { options in
            if case .keysetIndices(let indices)? = options.start { resumedFrom = indices }
            return self.restored(1, 2, 0, ["a": 700])
        }
        XCTAssertEqual(resumedFrom, ["a": 500])
        XCTAssertEqual(totals.spent, 5)
        XCTAssertEqual(totals.unspent, 42)
        XCTAssertNil(store.restoreCheckpoint(url: mint))
    }

    func testRepeatedInterruptionsAccumulateWithoutRegressing() async throws {
        let store = WalletStore(storage: InMemoryStorage())
        for (next, unspent) in [(300, 30), (900, 60)] as [(UInt32, UInt64)] {
            do {
                _ = try await ResumableMintRestore.run(store: store, mintURL: mint) { _ in
                    throw FfiError.RestoreInterrupted(
                        partial: self.restored(0, unspent, 0, ["a": next]),
                        code: 50000, errorMessage: "timeout"
                    )
                }
                XCTFail("Expected an interrupted restore")
            } catch is MintRestoreInterrupted {}
        }
        let checkpoint = try XCTUnwrap(store.restoreCheckpoint(url: mint))
        XCTAssertEqual(checkpoint.nextKeysetIndices, ["a": 900])
        XCTAssertEqual(checkpoint.unspent, 90)
    }

    func testNonInterruptionFailureKeepsExistingCheckpoint() async throws {
        let store = WalletStore(storage: InMemoryStorage())
        let saved = MintRestoreCheckpoint(nextKeysetIndices: ["a": 200], spent: 0, unspent: 20, pending: 0)
        store.saveRestoreCheckpoint(saved, url: mint)
        do {
            _ = try await ResumableMintRestore.run(store: store, mintURL: mint) { _ in
                throw FfiError.Internal(errorMessage: "offline")
            }
            XCTFail("Expected failure")
        } catch is FfiError {}
        XCTAssertEqual(store.restoreCheckpoint(url: mint), saved)
    }
}
