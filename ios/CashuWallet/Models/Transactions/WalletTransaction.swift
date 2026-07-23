import Foundation

struct WalletTransaction: Identifiable {
    let id: String
    let amount: UInt64
    let type: TransactionType
    let kind: TransactionKind
    let date: Date
    let memo: String?
    var status: TransactionStatus
    var statusNote: String? = nil
    
    /// Associated mint URL
    var mintUrl: String?
    
    /// Payment proof (preimage for Lightning, txid for on-chain when exposed)
    var preimage: String?
    
    /// Ecash token string (for outgoing pending transactions)
    var token: String?
    
    /// Payment request string (BOLT11 invoice, BOLT12 offer, or on-chain address)
    var invoice: String?
    
    /// Fee paid for the transaction (in sats)
    var fee: UInt64 = 0

    /// Mint account unit for `amount` ("sat", "usd", "eur", or custom).
    var unit: String = "sat"
    
    /// Whether this is from pending storage vs. completed transactions
    var isPendingToken: Bool = false

    /// Incoming ecash the user hasn't claimed yet (a "Receive Later" token or
    /// a NUT-18 payment held for approval). Rows with this flag open the
    /// claim flow instead of a plain receipt.
    var isPendingReceiveToken: Bool = false

    /// Source Cashu Request id when this incoming ecash transaction was
    /// auto-claimed via NUT-18. History uses this to suppress the duplicate
    /// row in favor of the request row.
    var cashuRequestId: String? = nil

    /// Mint-quote id for Lightning / on-chain mints and melts. The join key
    /// used to attach this transaction to the receive-intent backing its quote
    /// (a reusable BOLT12 offer, a BOLT11 invoice, an on-chain address).
    var quoteId: String? = nil

    /// BOLT11 mint quote still awaiting payment — titles the row
    /// "Lightning invoice" until the invoice settles.
    var isUnpaidInvoice: Bool = false

    /// The Quiet Pending treatment (bare, muted amount) covers expired too:
    /// an expired invoice never credited the balance.
    var isUnsettled: Bool {
        status == .pending || status == .expired
    }

    /// Mint-quote id to re-check when opening this row's detail, if any.
    /// Only unsettled incoming Lightning / on-chain mint quotes (not ecash,
    /// not melts). Expired unpaid invoices are included so a late-paid NUT-04
    /// quote can still mint after the invoice timer.
    var mintQuoteIdForStatusRefresh: String? {
        guard type == .incoming else { return nil }
        guard kind == .lightning || kind == .onchain else { return nil }
        guard !isPendingToken, !isPendingReceiveToken else { return nil }
        guard invoice != nil else { return nil }
        guard status == .pending || status == .expired else { return nil }
        return quoteId ?? id
    }

    var displayStatusText: String {
        if status == .pending {
            return statusNote ?? status.displayText
        }

        return status.displayText
    }

    /// Canonical row/detail title — kind-first, capitalized kind, lowercase
    /// verb, parallel across all kinds (e.g. "Ecash received", "Lightning
    /// paid"). Single source of truth for the History/Home rows and the
    /// transaction detail nav title.
    var displayTitle: String {
        if isPendingReceiveToken { return "Ecash to claim" }
        // Nothing has been received while the invoice awaits payment.
        if isUnpaidInvoice { return "Lightning invoice" }
        switch (kind, type) {
        case (.ecash,     .incoming): return "Ecash received"
        case (.ecash,     .outgoing): return "Ecash sent"
        case (.lightning, .incoming): return "Lightning received"
        case (.lightning, .outgoing): return "Lightning paid"
        case (.onchain,   .incoming): return "Bitcoin received"
        case (.onchain,   .outgoing): return "Bitcoin sent"
        }
    }

    enum TransactionType {
        case incoming   // Mint or receive
        case outgoing   // Send or melt
        
        var icon: String {
            switch self {
            case .incoming: return "arrow.down.circle.fill"
            case .outgoing: return "arrow.up.circle.fill"
            }
        }
    }
    
    /// Kind of transaction - distinguishes between Ecash and Lightning
    enum TransactionKind {
        case ecash      // Ecash token send/receive
        case lightning  // Lightning invoice mint/melt
        case onchain    // On-chain address mint/melt
        
        var displayName: String {
            switch self {
            case .ecash: return "Ecash"
            case .lightning: return "Lightning"
            case .onchain: return "On-chain"
            }
        }
    }
    
    enum TransactionStatus {
        case pending
        case completed
        case failed
        case expired

        var displayText: String {
            switch self {
            case .pending: return "Pending"
            case .completed: return "Completed"
            case .failed: return "Failed"
            case .expired: return "Expired"
            }
        }
    }
}

/// Home is a compact settled-ledger view, not an operational queue. Generated
/// receive artifacts and every non-completed state remain available in History.
enum HomeActivity {
    static func recentTransactions(
        from transactions: [WalletTransaction],
        limit: Int
    ) -> [WalletTransaction] {
        transactions
            .filter { $0.status == .completed }
            .sorted { $0.date > $1.date }
            .prefix(max(0, limit))
            .map { $0 }
    }
}

/// Result of a send tokens operation - includes token string and fee paid
