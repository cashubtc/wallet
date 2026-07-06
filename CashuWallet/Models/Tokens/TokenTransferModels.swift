import Foundation

struct SendTokenResult {
    let token: String
    let fee: UInt64
}

/// Pending token entry - stored when user sends ecash
struct PendingToken: Codable, Identifiable {
    var id: String { tokenId }
    let tokenId: String
    let token: String
    let amount: UInt64
    let fee: UInt64
    let date: Date
    let mintUrl: String
    let memo: String?
}

/// Pending receive token entry — stored when the user chooses "Receive Later",
/// or when a NUT-18 payment is held for approval (auto-claim off / unknown
/// mint). Surfaced in History as a claimable pending row.
struct PendingReceiveToken: Codable, Identifiable, Equatable {
    var id: String { tokenId }
    let tokenId: String
    let token: String
    let amount: UInt64
    let date: Date
    let mintUrl: String
    /// NUT-18 Cashu Request id when this payment arrived over Nostr; claiming
    /// routes through the request-attribution path so History links it to the
    /// originating request. Nil for manually parked tokens.
    let cashuRequestId: String?
    let memo: String?

    init(
        tokenId: String,
        token: String,
        amount: UInt64,
        date: Date,
        mintUrl: String,
        cashuRequestId: String? = nil,
        memo: String? = nil
    ) {
        self.tokenId = tokenId
        self.token = token
        self.amount = amount
        self.date = date
        self.mintUrl = mintUrl
        self.cashuRequestId = cashuRequestId
        self.memo = memo
    }
}

/// Claimed token entry - stored when a sent token is claimed by recipient
struct ClaimedToken: Codable, Identifiable {
    var id: String { tokenId }
    let tokenId: String
    let token: String
    let amount: UInt64
    let fee: UInt64
    let date: Date
    let mintUrl: String
    let memo: String?
    let claimedDate: Date
}

/// Result of restoring proofs from a single mint via NUT-09
