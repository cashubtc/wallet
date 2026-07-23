import Foundation

extension Array where Element == WalletTransaction {
    /// Resolve the live transaction for a detail screen opened with `openId`
    /// (and optional `openQuoteId` from the open-time snapshot).
    ///
    /// Pending mint-quote rows use `id == quoteId`. After mint, CDK replaces
    /// them with a different transaction id that still carries `quoteId`.
    /// Matching only on id fails to flip Pending → Completed without reopening.
    func resolveForDetail(openId: String, openQuoteId: String? = nil) -> WalletTransaction? {
        if let exact = first(where: { $0.id == openId }) {
            return exact
        }

        var keys = Set<String>([openId])
        if let openQuoteId, !openQuoteId.isEmpty {
            keys.insert(openQuoteId)
        }
        let matches = filter { tx in
            keys.contains(tx.id) || (tx.quoteId.map(keys.contains) ?? false)
        }
        guard !matches.isEmpty else { return nil }

        // Prefer a completed settlement for this quote, then the newest row.
        return matches.max { lhs, rhs in
            let leftCompleted = lhs.status == .completed
            let rightCompleted = rhs.status == .completed
            if leftCompleted != rightCompleted {
                return !leftCompleted && rightCompleted
            }
            return lhs.date < rhs.date
        }
    }
}
