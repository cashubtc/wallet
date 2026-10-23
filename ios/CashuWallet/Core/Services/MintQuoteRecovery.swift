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

/// NUT-09 rewrites proof state. Only an issue saga (which owns no input
/// proofs) may coexist with a scan of the same mint. Other mints are isolated.
@MainActor
enum MintRestoreSafety {
    static func allows(sagas: [String], mintURL: String, hasProtectedProofs: Bool) -> Bool {
        guard !hasProtectedProofs else { return false }
        struct Identity: Decodable { let kind: String; let mint_url: String }
        return sagas.allSatisfy { json in
            guard let data = json.data(using: .utf8),
                  let saga = try? JSONDecoder().decode(Identity.self, from: data) else { return false }
            return !MintRemovalPolicy.matches(saga.mint_url, mintURL) || saga.kind == "mint"
        }
    }

    /// An imported seed can reach a paid quote before its mint scan succeeds.
    /// Do not allocate deterministic outputs from an empty account ledger.
    static func prepareEmptyAccount(database: WalletSqliteDatabase, wallet: Wallet, mintURL: String) async throws {
        let proofs = try await database.getProofs(mintUrl: nil, unit: nil, state: nil, spendingConditions: nil)
        guard !proofs.contains(where: { MintRemovalPolicy.matches($0.mintUrl.url, mintURL) }) else { return }
        try await requireSafe(database: database, mintURL: mintURL)
        _ = try await wallet.restore()
    }

    static func requireSafe(database: WalletSqliteDatabase, mintURL: String) async throws {
        // restore() scans every keyset, including other units at this mint.
        let proofs = try await database.getProofs(mintUrl: nil, unit: nil, state: nil, spendingConditions: nil)
        let protected = proofs.contains {
            MintRemovalPolicy.matches($0.mintUrl.url, mintURL) &&
                ($0.usedByOperation != nil || $0.state == .reserved || $0.state == .pending || $0.state == .pendingSpent)
        }
        guard allows(sagas: try await database.getIncompleteSagas(), mintURL: mintURL, hasProtectedProofs: protected) else {
            throw WalletError.networkError("This mint has an interrupted send or reserved funds. Recover that payment before restoring this mint. Other mints can still be restored.")
        }
    }
}
