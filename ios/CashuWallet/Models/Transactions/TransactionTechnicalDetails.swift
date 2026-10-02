import Foundation
import Cdk

/// The identifiers behind one transaction — what a developer needs to trace a
/// payment through CDK and the mint (quote ID, request, proof, saga). Built
/// from the row plus local quote snapshots only, so opening it never touches
/// the network. It never carries a quote's NUT-20 secret key or a spendable
/// token, so Copy all is safe to paste into a bug report.
/// Android `TransactionTechnicalDetails` parity: same sections, labels and order.
struct TransactionTechnicalDetails: Equatable {
    struct Row: Equatable, Identifiable {
        let label: String
        /// Display form; opaque references use the decoder's 8…6 short form.
        let value: String
        /// Untruncated value for tap-to-copy and Copy all.
        let fullValue: String
        let isCopyable: Bool

        var id: String { label }
    }

    struct Section: Equatable, Identifiable {
        let title: String
        let rows: [Row]

        var id: String { title }
    }

    /// Display fields of a stored CDK mint quote. Deliberately has no
    /// `secretKey`: the NUT-20 signing key never leaves the database.
    struct MintQuoteSnapshot: Equatable {
        let request: String
        let state: String
        let paymentMethod: PaymentMethodKind?
        let amountPaid: UInt64
        let amountIssued: UInt64
        let expiry: UInt64
        let updatedAt: UInt64
    }

    struct MeltQuoteSnapshot: Equatable {
        let request: String
        let state: String
        let paymentMethod: PaymentMethodKind?
        let amount: UInt64
        let feeReserve: UInt64
        let expiry: UInt64
        let paymentProof: String?
    }

    let sections: [Section]

    /// Plain-text dump for bug reports: each section title, then its
    /// `Label: value` lines, with untruncated values.
    var copyAllText: String {
        sections
            .map { section in
                ([section.title] + section.rows.map { "\($0.label): \($0.fullValue)" })
                    .joined(separator: "\n")
            }
            .joined(separator: "\n\n")
    }

    init(
        transaction: WalletTransaction,
        mintQuote: MintQuoteSnapshot? = nil,
        meltQuote: MeltQuoteSnapshot? = nil
    ) {
        let unit = transaction.unit
        var sections = [Section(title: "Transaction", rows: Self.transactionRows(
            transaction,
            quoteMethod: mintQuote?.paymentMethod ?? meltQuote?.paymentMethod
        ))]

        if let quoteId = transaction.quoteId {
            var rows = [
                Self.reference("Quote ID", quoteId),
                Self.plain("Type", transaction.type == .incoming ? "Mint quote" : "Melt quote"),
            ]
            if let request = (mintQuote?.request ?? meltQuote?.request ?? transaction.invoice)
                .flatMap({ $0.isEmpty ? nil : $0 }) {
                rows.append(Self.reference("Request", request))
            }
            if let mintQuote {
                rows.append(Self.plain("State", mintQuote.state))
                rows.append(Self.plain("Amount paid", Self.nativeAmount(mintQuote.amountPaid, unit: unit)))
                rows.append(Self.plain("Amount issued", Self.nativeAmount(mintQuote.amountIssued, unit: unit)))
                rows.append(Self.expiryRow(mintQuote.expiry))
                if mintQuote.updatedAt > 0 {
                    rows.append(Self.dateRow("Last updated", Date(timeIntervalSince1970: TimeInterval(mintQuote.updatedAt))))
                }
            } else if let meltQuote {
                rows.append(Self.plain("State", meltQuote.state))
                rows.append(Self.plain("Quote amount", Self.nativeAmount(meltQuote.amount, unit: unit)))
                rows.append(Self.plain("Fee reserve", Self.nativeAmount(meltQuote.feeReserve, unit: unit)))
                rows.append(Self.expiryRow(meltQuote.expiry))
            }
            sections.append(Section(title: "Quote", rows: rows))
        }

        let proof = transaction.preimage ?? meltQuote?.paymentProof
        switch transaction.kind {
        case .lightning:
            if let proof {
                sections.append(Section(title: "Payment", rows: [Self.reference("Payment Proof", proof)]))
            }
        case .onchain:
            if let proof {
                sections.append(Section(title: "Payment", rows: [Self.reference("Transaction ID", proof)]))
            }
        case .ecash:
            break
        }

        self.sections = sections
    }

    /// The tap-to-copy toast for a row (Copy all reads "Copied details").
    static func copyConfirmation(for label: String) -> String {
        switch label {
        case "ID": return "Copied ID"
        case "Mint": return "Copied mint URL"
        case "Payment Proof": return "Copied payment proof"
        default: return "Copied \(label.prefix(1).lowercased())\(label.dropFirst())"
        }
    }

    // MARK: - Rows

    private static func transactionRows(
        _ transaction: WalletTransaction,
        quoteMethod: PaymentMethodKind?
    ) -> [Row] {
        var rows: [Row] = []
        // App-synthesized rows (a quote awaiting payment, a held token) have no
        // CDK transaction yet; their id is the quote/token id shown elsewhere.
        if !transaction.isPendingReceiveToken, transaction.id != transaction.quoteId {
            rows.append(reference("ID", transaction.id))
        }
        rows.append(plain("Method", methodLabel(transaction, quoteMethod: quoteMethod)))
        rows.append(plain("Direction", transaction.type == .incoming ? "Incoming" : "Outgoing"))
        rows.append(plain("Status", transaction.status.displayText))
        if transaction.status == .pending, let note = transaction.statusNote, !note.isEmpty {
            rows.append(plain("Status detail", note))
        }
        rows.append(dateRow("Date", transaction.date))
        rows.append(plain("Amount", nativeAmount(transaction.amount, unit: transaction.unit)))
        rows.append(plain("Fee", nativeAmount(transaction.fee, unit: transaction.unit)))
        if let mintUrl = transaction.mintUrl, !mintUrl.isEmpty {
            // A URL stays legible; the row wraps instead of cutting it 8…6.
            rows.append(Row(label: "Mint", value: mintUrl, fullValue: mintUrl, isCopyable: true))
        }
        if let sagaId = transaction.sagaId, !sagaId.isEmpty {
            rows.append(reference("Saga ID", sagaId))
        }
        return rows
    }

    static func methodLabel(_ transaction: WalletTransaction, quoteMethod: PaymentMethodKind? = nil) -> String {
        switch transaction.kind {
        case .ecash:
            return "Ecash"
        case .onchain:
            return "On-chain"
        case .lightning:
            let request = transaction.invoice?.lowercased() ?? ""
            let inferred: PaymentMethodKind? = request.hasPrefix("lno") ? .bolt12
                : request.hasPrefix("ln") ? .bolt11
                : nil
            switch transaction.paymentMethod ?? quoteMethod ?? inferred {
            case .bolt11: return "Lightning (BOLT11)"
            case .bolt12: return "Lightning (BOLT12)"
            case .onchain, nil: return "Lightning"
            }
        }
    }

    private static func plain(_ label: String, _ value: String) -> Row {
        Row(label: label, value: value, fullValue: value, isCopyable: false)
    }

    private static func reference(_ label: String, _ value: String) -> Row {
        Row(
            label: label,
            value: PaymentRequestDecoder.middleTruncated(value),
            fullValue: value,
            isCopyable: true
        )
    }

    /// Localized with seconds on screen; ISO 8601 UTC in Copy all, so a pasted
    /// report lines up with mint and CDK logs regardless of the reader's locale.
    private static func dateRow(_ label: String, _ date: Date) -> Row {
        Row(
            label: label,
            value: date.formatted(date: .abbreviated, time: .standard),
            fullValue: ISO8601DateFormatter().string(from: date),
            isCopyable: false
        )
    }

    /// 0 is CDK's "no expiry"; the app stores reusable BOLT12 offers with a
    /// far-future sentinel (9999-12-31) for the same meaning.
    private static func expiryRow(_ expiry: UInt64) -> Row {
        guard expiry > 0, expiry < 253_402_300_799 else { return plain("Expiry", "Never") }
        return dateRow("Expiry", Date(timeIntervalSince1970: TimeInterval(expiry)))
    }

    private static func nativeAmount(_ amount: UInt64, unit: String) -> String {
        if CurrencyRegistry.isSatoshiUnit(unit) { return "\(amount) sat" }
        return CurrencyAmount(
            value: amount,
            currency: CurrencyRegistry.currency(forMintUnit: unit)
        ).formatted()
    }
}

extension TransactionTechnicalDetails.MintQuoteSnapshot {
    /// Copies display fields only — `quote.secretKey` is intentionally dropped.
    init(_ quote: MintQuote) {
        self.init(
            request: quote.request,
            state: quote.state.technicalLabel,
            paymentMethod: PaymentMethodKind.from(quote.paymentMethod),
            amountPaid: quote.amountPaid.value,
            amountIssued: quote.amountIssued.value,
            expiry: quote.expiry,
            updatedAt: quote.updatedAt
        )
    }
}

extension TransactionTechnicalDetails.MeltQuoteSnapshot {
    init(_ quote: MeltQuote) {
        self.init(
            request: quote.request,
            state: quote.state.technicalLabel,
            paymentMethod: PaymentMethodKind.from(quote.paymentMethod),
            amount: quote.amount.value,
            feeReserve: quote.feeReserve.value,
            expiry: quote.expiry,
            paymentProof: quote.paymentProof
        )
    }
}

private extension QuoteState {
    var technicalLabel: String {
        switch self {
        case .unpaid: return "Unpaid"
        case .paid: return "Paid"
        case .pending: return "Pending"
        case .issued: return "Issued"
        }
    }
}
