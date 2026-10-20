import Foundation
import Cdk

extension WalletManager {
    /// Read display-only quote snapshots from the local database. Never fetches
    /// mint status, performs a payment, or exposes the NUT-20 signing key.
    func transactionQuoteSnapshot(for transaction: WalletTransaction) async -> TransactionTechnicalDetails.QuoteSnapshot {
        guard let quoteID = transaction.quoteId, transaction.kind != .ecash, let db else { return .init() }
        if transaction.type == .incoming {
            let quote = (try? await db.getMintQuote(quoteId: quoteID)) ?? nil
            return .init(mint: quote.map(TransactionTechnicalDetails.MintQuoteSnapshot.init))
        }
        let quote = (try? await db.getMeltQuote(quoteId: quoteID)) ?? nil
        return .init(melt: quote.map(TransactionTechnicalDetails.MeltQuoteSnapshot.init))
    }

    func transactionTechnicalDetails(for transaction: WalletTransaction) async -> TransactionTechnicalDetails {
        let snapshot = await transactionQuoteSnapshot(for: transaction)
        return snapshot.details(for: transaction)
    }

    func requestQuoteSnapshot(for request: CashuRequest) async -> TransactionTechnicalDetails.MintQuoteSnapshot? {
        guard let quoteID = request.quoteId, let db else { return nil }
        let quote = (try? await db.getMintQuote(quoteId: quoteID)) ?? nil
        return quote.map(TransactionTechnicalDetails.MintQuoteSnapshot.init)
    }
}
