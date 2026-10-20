import XCTest
@testable import CashuWallet

@MainActor
final class MintRestoreBatchTests: XCTestCase {
    private enum Failure: Error { case unavailable }

    private func recovered(_ url: String, amount: UInt64 = 10) -> RestoreMintResult {
        RestoreMintResult(mintUrl: url, mintName: "Test mint", spent: 0, unspent: amount, pending: 0)
    }

    func testOneFailedMintDoesNotBlockTheOtherSix() async throws {
        let urls = (1...7).map { "https://mint\($0).example" }
        var attempted: [String] = []
        var phases: [String: MintRestorePhase] = [:]
        try await MintRestoreBatch.run(urls: urls, restore: { url in
            attempted.append(url)
            if url == urls[2] { throw Failure.unavailable }
            return self.recovered(url)
        }, onProgress: { phases[$0] = $1 })

        XCTAssertEqual(attempted, urls)
        XCTAssertEqual(phases.values.compactMap {
            if case .recovered(let result) = $0 { result.unspent } else { nil }
        }.reduce(0, +), 60)
        guard case .failed = phases[urls[2]] else { return XCTFail("The unavailable mint must remain retryable") }
        XCTAssertEqual(phases.count, 7)
    }

    func testProgressIsDeliveredBeforeTheNextMintFinishes() async throws {
        let first = "https://first.example"
        let second = "https://second.example"
        var phases: [String: MintRestorePhase] = [:]
        var observedFirstResult = false
        try await MintRestoreBatch.run(urls: [first, second], restore: { url in
            if url == second {
                guard case .recovered(let result) = phases[first] else {
                    XCTFail("Publish the first recovered amount while the second mint is still running")
                    throw Failure.unavailable
                }
                observedFirstResult = result.unspent == 10
                guard case .restoring = phases[second] else {
                    XCTFail("The active mint must be visible before its network work")
                    throw Failure.unavailable
                }
            } else {
                guard case .pending = phases[second] else {
                    XCTFail("Show the queued mints before the first network request")
                    throw Failure.unavailable
                }
            }
            return self.recovered(url)
        }, onProgress: { phases[$0] = $1 })
        XCTAssertTrue(observedFirstResult)
    }

    func testAllFailuresStillProduceResultsInsteadOfFailingTheWallet() async throws {
        var failed: [String] = []
        try await MintRestoreBatch.run(urls: ["https://offline.example"], restore: { _ in
            throw Failure.unavailable
        }, onProgress: { url, phase in
            if case .failed = phase { failed.append(url) }
        })
        XCTAssertEqual(failed, ["https://offline.example"])
    }

    func testCancellationStopsTheBatchWithoutReportingAFailedMint() async {
        var attempted: [String] = []
        var failedCount = 0
        do {
            try await MintRestoreBatch.run(urls: ["first", "second"], restore: { url in
                attempted.append(url)
                throw CancellationError()
            }, onProgress: { _, phase in
                if case .failed = phase { failedCount += 1 }
            })
            XCTFail("Cancellation must propagate")
        } catch {
            XCTAssertTrue(error is CancellationError)
        }
        XCTAssertEqual(attempted, ["first"])
        XCTAssertEqual(failedCount, 0)
    }

    func testSeedOnlyBackupNeedsNoMintRecovery() async throws {
        try await MintRestoreBatch.run(urls: [], restore: { _ in
            XCTFail("A seed-only backup has no mints")
            throw Failure.unavailable
        }, onProgress: { _, _ in XCTFail("No mint progress expected") })
    }

    func testPartialCompletionKeepsFailedMintInFutureBackupsAcrossRelaunch() throws {
        let suiteName = "PartialRestoreTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        let success = "https://recovered.example"
        let failed = "https://offline.example"
        ICloudRestoreState.setIncomplete(true, defaults: defaults)
        ICloudRestoreState.setPendingMintURLs([success, failed], defaults: defaults)
        ICloudRestoreState.removePendingMint(success, defaults: defaults)

        // Opening the partially restored wallet releases the onboarding gate.
        OnboardingCompletionState.setCompleted(true, defaults: defaults)
        ICloudRestoreState.setIncomplete(false, defaults: defaults)
        let reopened = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        XCTAssertFalse(ICloudRestoreState.isIncomplete(defaults: reopened))
        XCTAssertTrue(OnboardingCompletionState.isCompleted(defaults: reopened))
        XCTAssertEqual(ICloudRestoreState.pendingMintURLs(defaults: reopened), [failed])
        XCTAssertEqual(ICloudRestoreState.mintURLsForBackup(current: [success], defaults: reopened), [success, failed])

        // Retrying just the failed mint resolves its pending entry. It remains
        // backed up through the normal tracked-mint list afterwards.
        ICloudRestoreState.removePendingMint(failed, defaults: reopened)
        XCTAssertTrue(ICloudRestoreState.pendingMintURLs(defaults: reopened).isEmpty)
        XCTAssertEqual(ICloudRestoreState.mintURLsForBackup(current: [success, failed], defaults: reopened), [success, failed])
    }

    func testRecoveryListDeduplicatesEquivalentURLsAndIsWalletScoped() throws {
        let suiteName = "RestoreListTests.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suiteName))
        defer { defaults.removePersistentDomain(forName: suiteName) }
        ICloudRestoreState.setPendingMintURLs([
            " HTTPS://MINT.EXAMPLE:443/ ", "https://mint.example", "https://other.example"
        ], defaults: defaults)
        XCTAssertEqual(ICloudRestoreState.pendingMintURLs(defaults: defaults), ["https://mint.example", "https://other.example"])
        ICloudRestoreState.removePendingMint("https://mint.example/", defaults: defaults)
        XCTAssertEqual(ICloudRestoreState.pendingMintURLs(defaults: defaults), ["https://other.example"])
        XCTAssertTrue(StorageKeys.walletBoundaryKeys.contains(StorageKeys.pendingICloudRestoreMintURLs))
        for key in StorageKeys.walletBoundaryKeys { defaults.removeObject(forKey: key) }
        XCTAssertTrue(ICloudRestoreState.pendingMintURLs(defaults: defaults).isEmpty)
    }

}
