import Foundation
import Cdk

extension WalletManager {
    /// The receipt's Details sheet. Reads the stored quote behind the row from
    /// the CDK database only — no mint or explorer traffic — so it opens
    /// offline and never changes wallet state. Incoming rows carry a mint
    /// quote; outgoing Lightning and on-chain rows a melt quote.
    func transactionTechnicalDetails(for transaction: WalletTransaction) async -> TransactionTechnicalDetails {
        guard let quoteId = transaction.quoteId, transaction.kind != .ecash, let db else {
            return TransactionTechnicalDetails(transaction: transaction)
        }

        if transaction.type == .incoming {
            let quote = (try? await db.getMintQuote(quoteId: quoteId)) ?? nil
            return TransactionTechnicalDetails(
                transaction: transaction,
                mintQuote: quote.map(TransactionTechnicalDetails.MintQuoteSnapshot.init)
            )
        }

        let quote = (try? await db.getMeltQuote(quoteId: quoteId)) ?? nil
        return TransactionTechnicalDetails(
            transaction: transaction,
            meltQuote: quote.map(TransactionTechnicalDetails.MeltQuoteSnapshot.init)
        )
    }
}
