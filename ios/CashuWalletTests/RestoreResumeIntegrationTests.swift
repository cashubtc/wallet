import Cdk
import XCTest
@testable import CashuWallet

/// Long-running seed restore against a fakewallet mint holding a wallet with
/// tens of thousands of derived secrets, fronted by `CI/restore-fault-proxy.py`.
/// Skipped unless the stress fixture is configured (see `CI/README.md`):
///   TEST_RUNNER_RESTORE_STRESS_MINT_URL     proxied mint URL (e.g. http://127.0.0.1:3341)
///   TEST_RUNNER_RESTORE_STRESS_CONTROL_URL  same proxy, used for fault control
///   TEST_RUNNER_RESTORE_STRESS_MNEMONIC     seed that `CI/restore-stress` funded
///   TEST_RUNNER_RESTORE_STRESS_EXPECTED     expected recovered sats
final class RestoreResumeIntegrationTests: XCTestCase {
    private struct Fixture {
        let mintURL: String
        let controlURL: String
        let mnemonic: String
        let expected: UInt64
    }

    private func fixture() throws -> Fixture {
        let env = ProcessInfo.processInfo.environment
        guard let mintURL = env["RESTORE_STRESS_MINT_URL"],
              let controlURL = env["RESTORE_STRESS_CONTROL_URL"],
              let mnemonic = env["RESTORE_STRESS_MNEMONIC"],
              let expected = env["RESTORE_STRESS_EXPECTED"].flatMap(UInt64.init) else {
            throw XCTSkip("Restore stress fixture not configured")
        }
        return Fixture(mintURL: mintURL, controlURL: controlURL, mnemonic: mnemonic, expected: expected)
    }

    private struct ProxyStats: Decodable { let forwarded: Int; let failed: Int }

    private func control(_ fixture: Fixture, _ action: String, _ body: [String: Any] = [:]) async throws -> ProxyStats {
        var request = URLRequest(url: URL(string: "\(fixture.controlURL)/__restore/\(action)")!)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: body)
        let (data, _) = try await URLSession.shared.data(for: request)
        return try JSONDecoder().decode(ProxyStats.self, from: data)
    }

    private func freshWallet(_ fixture: Fixture) async throws -> Wallet {
        let path = NSTemporaryDirectory() + "restore-stress-\(UUID().uuidString).sqlite"
        addTeardownBlock { try? FileManager.default.removeItem(atPath: path) }
        let db = try WalletSqliteDatabase(filePath: path)
        let repo = try WalletRepository(mnemonic: fixture.mnemonic, store: customWalletStore(db: db))
        let url = MintUrl(url: fixture.mintURL)
        try await repo.createWallet(mintUrl: url, unit: .sat, targetProofCount: nil)
        return try await repo.getWallet(mintUrl: url, unit: .sat)
    }

    func testInterruptedRestoreResumesWithoutRepeatingBatchesAndRecoversEverything() async throws {
        let fixture = try fixture()
        _ = try await control(fixture, "heal")

        // Baseline: how many restore requests a clean scan takes.
        let before = try await control(fixture, "stats")
        let baselineWallet = try await freshWallet(fixture)
        let baselineStore = WalletStore(storage: InMemoryStorage())
        let baseline = try await ResumableMintRestore.run(store: baselineStore, mintURL: fixture.mintURL) {
            try await baselineWallet.restoreWithOpts(opts: $0)
        }
        let baselineRequests = try await control(fixture, "stats").forwarded - before.forwarded
        XCTAssertEqual(baseline.unspent, fixture.expected, "uninterrupted restore must recover everything")
        XCTAssertGreaterThan(baselineRequests, 100, "fixture is too small to exercise a long restore")

        // Interrupted: cut the mint off a third of the way through.
        let wallet = try await freshWallet(fixture)
        let store = WalletStore(storage: InMemoryStorage())
        let cutoff = baselineRequests / 3
        let start = try await control(fixture, "stats")
        _ = try await control(fixture, "config", ["fail_after": cutoff, "mode": "drop"])

        do {
            _ = try await ResumableMintRestore.run(store: store, mintURL: fixture.mintURL) {
                try await wallet.restoreWithOpts(opts: $0)
            }
            XCTFail("Restore should have been interrupted")
        } catch let error as MintRestoreInterrupted {
            XCTAssertFalse(error.checkpoint.nextKeysetIndices.isEmpty)
            XCTAssertGreaterThan(error.checkpoint.nextKeysetIndices.values.max() ?? 0, 0)
        }
        let saved = try XCTUnwrap(store.restoreCheckpoint(url: fixture.mintURL))
        let firstLeg = try await control(fixture, "stats").forwarded - start.forwarded
        XCTAssertGreaterThan(saved.unspent, 0, "partial progress must be reported")
        XCTAssertLessThan(saved.unspent, fixture.expected)

        // Resume on the same wallet after the mint comes back.
        _ = try await control(fixture, "heal")
        let resumeStart = try await control(fixture, "stats")
        let resumed = try await ResumableMintRestore.run(store: store, mintURL: fixture.mintURL) {
            try await wallet.restoreWithOpts(opts: $0)
        }
        let secondLeg = try await control(fixture, "stats").forwarded - resumeStart.forwarded

        XCTAssertEqual(resumed.unspent, fixture.expected, "resumed totals must match an uninterrupted restore")
        XCTAssertEqual(resumed.spent, baseline.spent)
        XCTAssertNil(store.restoreCheckpoint(url: fixture.mintURL))
        let balance = try await wallet.totalBalance().value
        XCTAssertEqual(balance, fixture.expected)

        // No batch scanned twice (allow the scan's own trailing gap batches).
        XCTAssertLessThanOrEqual(firstLeg + secondLeg, baselineRequests + 8,
                                 "resume repeated finished batches: \(firstLeg) + \(secondLeg) vs \(baselineRequests)")
    }
}
