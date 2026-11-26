import Cdk
import Foundation

/// Resume position and accumulated totals for an interrupted seed restore at one
/// mint. CDK reports the next derivation index per keyset when a scan is
/// interrupted (or completes); passing those back as the starting indices lets
/// the next attempt continue without repeating finished batches.
///
/// Amounts cover only the ranges already scanned by earlier attempts. The
/// resumed scan reports its own range, so the two are summed for display.
struct MintRestoreCheckpoint: Codable, Equatable {
    /// Inclusive next index to scan, keyed by encoded keyset ID.
    var nextKeysetIndices: [String: UInt32]
    var spent: UInt64
    var unspent: UInt64
    var pending: UInt64

    /// Fold in another scanned range. Positions never move backwards, even if a
    /// later report omits a keyset or carries a smaller index.
    func advanced(
        spent addedSpent: UInt64,
        unspent addedUnspent: UInt64,
        pending addedPending: UInt64,
        nextKeysetIndices reported: [String: UInt32]
    ) -> MintRestoreCheckpoint {
        var merged = nextKeysetIndices
        for (keysetID, index) in reported {
            merged[keysetID] = max(merged[keysetID] ?? 0, index)
        }
        return MintRestoreCheckpoint(
            nextKeysetIndices: merged,
            spent: spent &+ addedSpent,
            unspent: unspent &+ addedUnspent,
            pending: pending &+ addedPending
        )
    }
}

/// A restore that stopped mid-scan after recovering part of the wallet. The
/// checkpoint is already persisted, so retrying resumes where it stopped.
struct MintRestoreInterrupted: LocalizedError {
    let checkpoint: MintRestoreCheckpoint
    let reason: String

    var errorDescription: String? {
        "Restore paused before finishing. Your progress is saved. Retry to continue."
    }
}

/// Runs a seed restore that survives mid-scan failures. CDK reports the next
/// derivation index per keyset on success and on an interrupted scan; saving it
/// lets the next attempt start there instead of repeating finished batches.
enum ResumableMintRestore {
    /// Same scan shape as `Wallet.restore()` (CDK's NUT-13 defaults).
    static let batchSize: UInt32 = 100
    static let maxGap: UInt32 = 3

    /// Returns totals across every attempt, including earlier interrupted ones.
    /// Throws `MintRestoreInterrupted` after saving a checkpoint when the scan
    /// stopped part-way; any other failure leaves the saved checkpoint untouched.
    static func run(
        store: WalletStore,
        mintURL: String,
        perform: (Nut13Options) async throws -> Restored
    ) async throws -> (spent: UInt64, unspent: UInt64, pending: UInt64) {
        let saved = store.restoreCheckpoint(url: mintURL)
        let options = Nut13Options(
            batchSize: batchSize,
            maxGap: maxGap,
            start: saved.map { .keysetIndices(indices: $0.nextKeysetIndices) }
        )
        let base = saved ?? MintRestoreCheckpoint(nextKeysetIndices: [:], spent: 0, unspent: 0, pending: 0)

        do {
            let restored = try await perform(options)
            store.saveRestoreCheckpoint(nil, url: mintURL)
            return (
                base.spent &+ restored.spent.value,
                base.unspent &+ restored.unspent.value,
                base.pending &+ restored.pending.value
            )
        } catch let error as FfiError {
            guard case .RestoreInterrupted(let partial, _, let message) = error else { throw error }
            let advanced = base.advanced(
                spent: partial.spent.value,
                unspent: partial.unspent.value,
                pending: partial.pending.value,
                nextKeysetIndices: partial.nextKeysetIndices
            )
            store.saveRestoreCheckpoint(advanced, url: mintURL)
            throw MintRestoreInterrupted(checkpoint: advanced, reason: message)
        }
    }
}
