import Foundation

/// A quoted move of ecash from one held mint to another, ready to show for
/// review and then execute. The amount is what arrives at the destination; the
/// source pays it plus the fees.
///
/// A plan owns a BOLT11 mint quote at the destination and the melt quote at the
/// source that pays it. Both exist at their mints from the moment the plan is
/// made, so a plan that is not executed must be discarded, not dropped.
struct MintTransferPlan: Identifiable, Equatable {
    enum Mode: String, Codable, Equatable {
        /// The user typed the amount.
        case exact
        /// The largest amount the source can move in one payment.
        case max
    }

    let id: String
    let mode: Mode
    let sourceMintURL: String
    let destinationMintURL: String
    let mintQuoteID: String
    let meltQuoteID: String
    /// Amount that arrives at the destination.
    let amount: UInt64
    /// Lightning fee reserve at the source. An upper bound: what the payment
    /// does not use comes back as change.
    let feeReserve: UInt64
    /// Input fee the melt is planned to pay. Non-zero only when the melt spends
    /// every proof directly, which is the one case where it is known up front.
    let inputFee: UInt64
    let selection: MeltProofSelection
    /// When the quotes stop being payable. Nil when neither mint set one.
    let expiresAt: Date?

    var feeUpperBound: UInt64 { feeReserve.addingClamped(inputFee) }
    var total: UInt64 { amount.addingClamped(feeUpperBound) }

    func isExpired(at now: Date = Date()) -> Bool {
        guard let expiresAt else { return false }
        return now >= expiresAt
    }

    /// How a max plan's melt must pick its proofs.
    ///
    /// CDK sizes a max quote on the input fee of every unspent proof. When that
    /// leaves no room to spare, only melting exactly those proofs fits: an
    /// ordinary melt may swap first and pay a second input fee the balance no
    /// longer covers. When the amount was capped by a mint limit instead there
    /// is room for that, and an ordinary melt avoids routing the whole balance
    /// through one payment. Mints without input fees never need the special
    /// path.
    static func maxMeltSelection(
        balance: UInt64,
        amount: UInt64,
        feeReserve: UInt64,
        inputFee: UInt64
    ) -> MeltProofSelection {
        guard inputFee > 0 else { return .automatic }
        let committed = amount.addingClamped(feeReserve).addingClamped(inputFee)
        let slack = balance > committed ? balance - committed : 0
        // A swap and the melt after it can each cost up to the fee of spending
        // every proof.
        return slack < inputFee.addingClamped(inputFee)
            ? .allUnspentSkippingSwap(expectedInputFee: inputFee)
            : .automatic
    }
}

/// The durable link between a transfer's two quotes. CDK records each leg in
/// its own mint's ledger and knows nothing connects them; this record is what
/// lets the wallet treat them as one transfer across relaunches.
struct MintTransferRecord: Codable, Identifiable, Equatable {
    enum State: String, Codable, Equatable {
        /// Quoted and under review. No money has moved.
        case draft
        /// The melt was started. From here the destination quote is the only
        /// handle on the payment and is never discarded.
        case committed
        /// The destination issued the ecash.
        case completed
        /// The melt ended without paying.
        case failed
    }

    let id: String
    let sourceMintURL: String
    let destinationMintURL: String
    let mintQuoteID: String
    /// Nil only in the moment between the two quotes being created.
    var meltQuoteID: String?
    let amount: UInt64
    let createdAt: Date
    var state: State = .draft
}

extension Array where Element == MintTransferRecord {
    /// Records kept. Old finished transfers fall back to reading as the two
    /// Lightning rows CDK holds for them.
    static var limit: Int { 500 }

    /// Destination quotes that belong to a transfer in any state. They are
    /// never an invoice the user is waiting on, and their issuance is not a
    /// payment received.
    var ownedMintQuoteIDs: Set<String> { Set(map(\.mintQuoteID)) }

    /// Destination quotes still under review. Nothing will pay them until the
    /// user confirms, so maintenance leaves them alone.
    var draftMintQuoteIDs: Set<String> {
        Set(filter { $0.state == .draft }.map(\.mintQuoteID))
    }

    /// Destination quotes whose melt was started and has not been seen to end.
    /// A payment can settle after its invoice expired, so these keep being
    /// checked until they issue.
    var awaitingIssuanceMintQuoteIDs: Set<String> {
        Set(filter { $0.state == .committed }.map(\.mintQuoteID))
    }

    func hasUnfinishedTransfer(referencing mintURL: String) -> Bool {
        let identity = MintURLIdentity.normalized(mintURL)
        return contains {
            $0.state == .committed
                && (MintURLIdentity.normalized($0.sourceMintURL) == identity
                    || MintURLIdentity.normalized($0.destinationMintURL) == identity)
        }
    }

    /// Insert or replace by id, newest first, dropping the oldest finished
    /// records past the limit. Unfinished ones are never dropped.
    func upserting(_ record: MintTransferRecord) -> [MintTransferRecord] {
        var records = filter { $0.id != record.id }
        records.insert(record, at: 0)
        records.sort { $0.createdAt > $1.createdAt }
        var kept: [MintTransferRecord] = []
        for candidate in records {
            let finished = candidate.state == .completed || candidate.state == .failed
            if !finished || kept.count < Self.limit { kept.append(candidate) }
        }
        return kept
    }

    func setting(_ state: MintTransferRecord.State, forID id: String) -> [MintTransferRecord] {
        map { record in
            guard record.id == id else { return record }
            var updated = record
            updated.state = state
            return updated
        }
    }
}

/// How an executed transfer ended.
enum MintTransferOutcome: Equatable {
    /// The destination issued `amount`. `feePaid` is what the source actually
    /// paid on top, including any input fee.
    case completed(amount: UInt64, feePaid: UInt64)
    /// Money is in flight and will finish on its own.
    case settling(Leg)

    enum Leg: Equatable {
        /// The source has not confirmed the Lightning payment yet.
        case payment
        /// The payment went out; the destination has not issued the ecash yet.
        case issuance
    }
}

enum MintTransferStage: Equatable {
    case paying
    case issuing
}

enum MintTransferError: Error, Equatable {
    case notEligible(MintTransferEligibility.Blocker)
    /// The source cannot cover the amount plus the fee reserve.
    case insufficientBalance(required: UInt64, available: UInt64)
    /// The source holds too little to move anything once fees are paid.
    case nothingToTransfer
    /// The mints returned quotes that do not describe the requested transfer.
    case quoteMismatch
    /// The quotes expired before the transfer started.
    case planExpired
    /// The wallet changed since the plan was made, so it would not pay what it
    /// promised.
    case planStale
    /// The melt ended without paying. The funds are still at the source.
    case paymentReturned
}

private extension UInt64 {
    func addingClamped(_ other: UInt64) -> UInt64 {
        let sum = addingReportingOverflow(other)
        return sum.overflow ? .max : sum.partialValue
    }
}
