import Foundation
import Cdk

/// Pure mint-quote domain rules (Android `MintQuoteDomain.kt` parity) —
/// extracted for unit testing.
enum MintQuoteDomain {
    /// Payer decoders render control characters, including newlines, as replacement glyphs.
    static func normalizedOfferDescription(_ raw: String?) -> String? {
        let printable = (raw ?? "").unicodeScalars.compactMap { scalar -> String? in
            if CharacterSet.whitespacesAndNewlines.contains(scalar) { return " " }
            return scalar.properties.generalCategory == .control ? nil : String(scalar)
        }.joined()
        let normalized = String(printable.split(separator: " ").joined(separator: " ").prefix(640))
        return normalized.isEmpty ? nil : normalized
    }

    /// True when any NUT-04 bolt12 method advertises `description: true`.
    /// Null or false on every bolt12 method (or no bolt12 method) fails closed.
    static func reportsBolt12MintDescription(
        methods: [(method: PaymentMethodKind?, description: Bool?)]
    ) -> Bool {
        methods.contains { $0.method == .bolt12 && $0.description == true }
    }

    /// Exact-match rule for reusable-offer reuse: an amountless BOLT12 quote
    /// at the active mint and requested unit whose locally stored memo equals
    /// the requested description (nil → only the plain, description-less
    /// offer). CDK never returns offer descriptions, so the memo stored locally
    /// by quote id is the only record — and exact matching keeps reuse
    /// unambiguous once several amountless offers exist (offers are immutable,
    /// so a changed description always mints a fresh one).
    static func isReusableAmountlessOffer(
        paymentMethod: PaymentMethodKind?,
        isAmountless: Bool,
        quoteMintUrl: String,
        quoteUnit: String,
        activeMintUrl: String,
        unit: String,
        storedMemo: String?,
        description: String?
    ) -> Bool {
        paymentMethod == .bolt12 &&
            isAmountless &&
            quoteMintUrl == activeMintUrl &&
            quoteUnit.lowercased() == unit.lowercased() &&
            storedMemo == description
    }

    /// The deposit address Receive hands out again: the newest on-chain quote
    /// at this mint that the mint has not credited and that has not expired.
    /// Once money arrives the next Receive gets a fresh address. A deposit
    /// still in the mempool does not block reuse — the sheet then shows it.
    static func reusableOnchainAddress(
        in quotes: [MintQuote],
        mintURL: String,
        now: Date = Date()
    ) -> MintQuote? {
        let mint = MintURLIdentity.normalized(mintURL)
        let nowSeconds = now.timeIntervalSince1970
        func isReusable(_ quote: MintQuote) -> Bool {
            guard PaymentMethodKind.from(quote.paymentMethod) == .onchain,
                  MintURLIdentity.normalized(quote.mintUrl.url) == mint,
                  quote.amountPaid.value == 0,
                  quote.amountIssued.value == 0 else { return false }
            return quote.expiry == 0 || TimeInterval(quote.expiry) > nowSeconds
        }
        func isOlder(_ lhs: MintQuote, _ rhs: MintQuote) -> Bool {
            lhs.updatedAt != rhs.updatedAt ? lhs.updatedAt < rhs.updatedAt : lhs.id < rhs.id
        }
        return quotes.filter(isReusable).max(by: isOlder)
    }
}
