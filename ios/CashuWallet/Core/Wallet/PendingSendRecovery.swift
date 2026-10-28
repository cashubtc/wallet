import Foundation
import Cdk

struct CashuRequestDeliveryReturned: LocalizedError {
    var errorDescription: String? {
        "The payment couldn't be delivered. Unclaimed ecash was returned to your wallet. Mint fees may apply."
    }
}

@MainActor
func performRecoverablePaymentRequest(
    context: PendingSendRecovery,
    inspect: ([String]) async throws -> PendingSendRecovery,
    recover: () async throws -> Void,
    prepare: () async throws -> PreparedPaymentRequest,
    reclaim: (String) async throws -> Void
) async throws {
    var deliveryFailed = false
    do {
        try await performRecoverableSend(context: context, inspect: inspect, recover: recover) { remember in
            let prepared = try await prepare()
            let operationID = prepared.operationId()
            remember(operationID)
            do {
                try await prepared.confirm()
            } catch let error as FfiError {
                guard case .PaymentRequestDeliveryFailed(let failedID, _) = error,
                      failedID == operationID else { throw error }
                deliveryFailed = true
                // Revoke only this attempt. The mint arbitrates a concurrent recipient claim.
                // If reclaim fails, the normal recovery snapshot must resolve the outcome.
                try? await reclaim(operationID)
                throw error
            }
        }
    } catch is FfiError where deliveryFailed {
        // performRecoverableSend only rethrows the delivery error after proving
        // no funds remain pending. Claimed/uncertain outcomes use PendingSendRecovery.
        throw CashuRequestDeliveryReturned()
    }
}

struct PendingSend: Identifiable, Equatable {
    let id: String
    let amount: UInt64
    let token: String?
}

struct PendingSendRecovery: Error, LocalizedError, CustomStringConvertible, CustomDebugStringConvertible {
    enum Outcome { case pending, unverified, completed, returned }
    let mintURL: String
    let unit: String
    var operationIDs: [String] = []
    var sends: [PendingSend] = []
    var outcome: Outcome = .unverified

    var description: String { errorDescription ?? "Send recovery required." }
    var debugDescription: String { description }

    var errorDescription: String? {
        switch outcome {
        case .pending: return "Ecash is still pending. Check its status or reclaim it before sending again."
        case .unverified: return "The send outcome couldn't be verified. Check its status before sending again."
        case .completed: return "The ecash was claimed. This payment is complete."
        case .returned: return "No ecash remains pending. Your available balance has been refreshed."
        }
    }
}

@MainActor
func performRecoverableSend<T>(
    context: PendingSendRecovery,
    inspect: ([String]) async throws -> PendingSendRecovery,
    recover: () async throws -> Void,
    send: (_ remember: (String) -> Void) async throws -> T
) async throws -> T {
    let before: PendingSendRecovery
    do { before = try await inspect([]) }
    catch { throw context }
    guard before.outcome == .returned else { throw before }
    var operationIDs: [String] = []
    do {
        return try await send { operationIDs.append($0) }
    } catch {
        let original = error
        do {
            try await recover()
            let resolved = try await inspect(operationIDs)
            if resolved.outcome != .returned { throw resolved }
        } catch let resolved as PendingSendRecovery {
            throw resolved
        } catch {
            var unresolved = context
            unresolved.operationIDs = operationIDs
            throw unresolved
        }
        throw original
    }
}

struct SendRecoverySaga: Decodable {
    struct State: Decodable { let type: String; let state: String }
    struct Payload: Decodable {
        struct Body: Decodable { let token: String? }
        let data: Body
    }
    let id: String
    let kind: String
    let mint_url: String
    let unit: String
    let amount: UInt64
    let state: State
    let data: Payload
}


enum SendRecoveryTransactionStatus { case pending, completed, failed }

func resolvePendingSendRecovery(
    context: PendingSendRecovery, mintURL: String, sagaJSON: [String], pendingIDs: [String],
    transactions: [String: SendRecoveryTransactionStatus]
) throws -> PendingSendRecovery {
    let pending = Set(pendingIDs)
    let allRecords = try sagaJSON.map {
        try JSONDecoder().decode(SendRecoverySaga.self, from: Data($0.utf8))
    }
    let records = allRecords.filter { $0.kind == "send" && $0.mint_url == mintURL && $0.unit == context.unit }
    var result = context
    result.operationIDs = Array(Set(context.operationIDs + records.map(\.id))).sorted()
    result.sends = records.filter {
        $0.state.type == "send" && $0.state.state == "token_created" && pending.contains($0.id)
    }.map { PendingSend(id: $0.id, amount: $0.amount, token: $0.data.data.token) }
    if !pending.isSubset(of: Set(allRecords.map(\.id))) || records.count != result.sends.count {
        result.outcome = .unverified
    } else if !result.sends.isEmpty {
        result.outcome = .pending
    } else {
        let statuses = result.operationIDs.compactMap(SagaTransactionId.transactionIdHex).compactMap { transactions[$0] }
        result.outcome = statuses.contains(.pending) ? .unverified : statuses.contains(.completed) ? .completed : .returned
    }
    return result
}
