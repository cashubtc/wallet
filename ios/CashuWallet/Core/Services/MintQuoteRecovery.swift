import Cdk
import Foundation

struct ReservationProof: Hashable {
    let id: String
    let owner: String?
    let mintURL: String
    let unit: String
    let reserved: Bool
}

/// Runs while the host holds its repository operation lease.
struct AbandonedReservationRecovery {
    var eligibleOwners: Set<String>
    var loadProofs: () async throws -> [ReservationProof]
    var hasOperation: (String) async throws -> Bool
    var hasTransaction: (String) async throws -> Bool
    var hasActivity: (String, String) async throws -> Bool
    var checkUnspent: (String, [String]) async throws -> Bool
    var release: (String) async throws -> Void

    func recover(mintURL: String, unit: String) async throws -> Set<String> {
        var recovered = Set<String>()
        let snapshot = try await loadProofs()
        for owner in eligibleOwners.sorted() {
            let proofs = snapshot.filter { $0.owner == owner }
            guard !proofs.isEmpty, proofs.allSatisfy({
                $0.reserved && $0.unit == unit && MintURLIdentity.normalized($0.mintURL) == MintURLIdentity.normalized(mintURL)
            }) else { continue }
            let ids = proofs.map(\.id)
            guard Set(ids).count == ids.count else { continue }
            guard try await !hasOperation(owner), try await !hasTransaction(owner), try await !hasActivity(mintURL, unit) else { continue }
            guard try await checkUnspent(mintURL, ids) else { continue }
            try Task.checkCancellation()
            let current = try await loadProofs().filter { $0.owner == owner }
            guard current.count == proofs.count, Set(current) == Set(proofs) else { continue }
            guard try await !hasOperation(owner), try await !hasTransaction(owner), try await !hasActivity(mintURL, unit) else { continue }
            try Task.checkCancellation()
            try await release(owner)
            recovered.insert(owner)
        }
        return recovered
    }

    static func allUnspent(_ ys: [String], response: Data) -> Bool {
        struct Response: Decodable {
            struct State: Decodable { let Y: String; let state: String }
            let states: [State]
        }
        guard !ys.isEmpty, Set(ys).count == ys.count,
              let result = try? JSONDecoder().decode(Response.self, from: response) else { return false }
        return result.states.count == ys.count
            && Set(result.states.map { $0.Y.lowercased() }) == Set(ys.map { $0.lowercased() })
            && result.states.allSatisfy { $0.state == "UNSPENT" }
    }

    static func checkMint(_ mintURL: String, ys: [String]) async throws -> Bool {
        guard let url = URL(string: mintURL)?.appendingPathComponent("v1/checkstate") else { return false }
        var request = URLRequest(url: url, cachePolicy: .reloadIgnoringLocalCacheData, timeoutInterval: 10)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONSerialization.data(withJSONObject: ["Ys": ys])
        let (data, response) = try await URLSession.shared.data(for: request)
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { return false }
        return allUnspent(ys, response: data)
    }
}

extension LightningService {
    func captureAbandonedReservations(database: WalletSqliteDatabase) async {
        do {
            let proofs = try await database.getProofs(mintUrl: nil, unit: nil, state: [.reserved], spendingConditions: nil)
            var owners = Set<String>()
            for owner in Set(proofs.compactMap(\.usedByOperation)) where UUID(uuidString: owner) != nil {
                if try await database.getSaga(id: owner) == nil { owners.insert(owner) }
            }
            abandonedReservationOwners = owners
        } catch {
            abandonedReservationOwners = []
            AppLogger.wallet.error("Unable to inspect held funds at wallet startup")
        }
    }

    func recoverAbandonedReservations(wallet: Wallet) async throws -> Bool {
        guard !abandonedReservationOwners.isEmpty, let database = walletDatabaseForReservationRecovery else { return false }
        try requireNoActiveMeltSettlement()
        let recovery = AbandonedReservationRecovery(
            eligibleOwners: abandonedReservationOwners,
            loadProofs: {
                try await database.getProofs(mintUrl: nil, unit: nil, state: nil, spendingConditions: nil).map {
                    ReservationProof(id: $0.y.hex, owner: $0.usedByOperation, mintURL: $0.mintUrl.url,
                                     unit: PaymentRequestDecoder.unitDescription($0.unit), reserved: $0.state == .reserved)
                }
            },
            hasOperation: { try await database.getSaga(id: $0) != nil },
            hasTransaction: { owner in
                try await database.listTransactions(mintUrl: nil, direction: nil, unit: nil).contains { $0.sagaId == owner }
            },
            hasActivity: { mintURL, unit in
                struct Identity: Decodable { let mint_url: String; let unit: String }
                for json in try await database.getIncompleteSagas() {
                    guard let data = json.data(using: .utf8), let identity = try? JSONDecoder().decode(Identity.self, from: data) else { return true }
                    if MintURLIdentity.normalized(identity.mint_url) == MintURLIdentity.normalized(mintURL), identity.unit == unit { return true }
                }
                return false
            },
            checkUnspent: { try await AbandonedReservationRecovery.checkMint($0, ys: $1) },
            release: { try await database.releaseProofs(operationId: $0) }
        )
        let returned = try await recovery.recover(mintURL: wallet.mintUrl().url, unit: PaymentRequestDecoder.unitDescription(wallet.unit()))
        abandonedReservationOwners.subtract(returned)
        return !returned.isEmpty
    }
}

enum MintQuoteRecovery {
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
