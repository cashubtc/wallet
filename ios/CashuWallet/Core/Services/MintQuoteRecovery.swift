import Cdk
import Foundation

enum MintQuoteRecovery {
    /// CDK persists status/recovery updates but returns the pre-write version.
    /// Reload before any app-side write, and keep CDK's current reservation.
    static func refresh(
        check: () async throws -> MintQuote,
        reload: () async throws -> MintQuote?
    ) async throws -> MintQuote {
        _ = try await check()
        guard let stored = try await reload() else {
            throw WalletError.networkError("This receive request is no longer available.")
        }
        return stored
    }

    /// Only CDK may resolve an interrupted mint. Keeping its saga intact lets
    /// recovery retrieve the original outputs after a lost mint response.
    static func reconcile(
        quote: MintQuote,
        recover: () async throws -> Void,
        reload: () async throws -> MintQuote?
    ) async throws -> UInt64 {
        try await recover()
        guard let refreshed = try await reload(),
              refreshed.usedByOperation == nil else {
            throw WalletError.networkError(
                "This receive is still being recovered. Check History again before retrying."
            )
        }
        return refreshed.amountIssued.value - min(quote.amountIssued.value, refreshed.amountIssued.value)
    }
}
