import Foundation

/// Runs independent mint recoveries, publishing each result before starting the
/// next mint. A failed mint must not discard successful recoveries or stop the
/// rest of the batch. Cancellation still stops the operation.
@MainActor
enum MintRestoreBatch {
    static func run(
        urls: [String],
        restore: (String) async throws -> RestoreMintResult,
        onProgress: (String, MintRestorePhase) -> Void
    ) async throws {
        for url in urls { onProgress(url, .pending) }
        for url in urls {
            try Task.checkCancellation()
            onProgress(url, .restoring)
            do {
                let result = try await restore(url)
                onProgress(url, .recovered(result))
            } catch {
                if error is CancellationError || Task.isCancelled {
                    throw CancellationError()
                }
                onProgress(url, .failed(error.userFacingWalletMessage))
            }
        }
        try Task.checkCancellation()
    }
}
