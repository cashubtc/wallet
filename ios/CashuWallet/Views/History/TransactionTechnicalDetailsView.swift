import SwiftUI

/// The identifiers behind a receipt, for support and debugging. Opened from the
/// receipt's last row as a nested `.large` sheet — the Description "Read more"
/// precedent — so the content-fit receipt never pushes a page it would clip.
struct TransactionTechnicalDetailsView: View {
    let transaction: WalletTransaction
    @EnvironmentObject private var walletManager: WalletManager
    @State private var quote = TransactionTechnicalDetails.QuoteSnapshot()
    @State private var loadedQuoteID: String?

    private var current: WalletTransaction {
        walletManager.transactions.first { $0.id == transaction.id } ?? transaction
    }

    var body: some View {
        TechnicalDetailsContent(details: (loadedQuoteID == current.quoteId ? quote : .init()).details(for: current))
            .task(id: TransactionTechnicalDetails(transaction: current)) {
                let snapshot = await walletManager.transactionQuoteSnapshot(for: current)
                guard !Task.isCancelled else { return }
                quote = snapshot
                loadedQuoteID = current.quoteId
            }
    }
}

struct RequestTechnicalDetailsView: View {
    let request: CashuRequest
    @EnvironmentObject private var walletManager: WalletManager
    @ObservedObject private var store = CashuRequestStore.shared
    @State private var quote: TransactionTechnicalDetails.MintQuoteSnapshot?
    @State private var loadedQuoteID: String?

    private var current: CashuRequest { store.request(withId: request.id) ?? request }
    private var payments: [TechnicalDetailsContent.Payment] {
        current.receivedPayments.enumerated().compactMap { index, payment in
            walletManager.transactions.first { $0.id == payment.transactionId }.map {
                TechnicalDetailsContent.Payment(number: index + 1, transaction: $0)
            }
        }
    }

    var body: some View {
        TechnicalDetailsContent(details: TransactionTechnicalDetails(request: current, mintQuote: loadedQuoteID == current.quoteId ? quote : nil), payments: payments)
            .task(id: current) {
                let snapshot = await walletManager.requestQuoteSnapshot(for: current)
                guard !Task.isCancelled else { return }
                quote = snapshot
                loadedQuoteID = current.quoteId
            }
    }
}

private struct TechnicalDetailsContent: View {
    let details: TransactionTechnicalDetails
    struct Payment: Identifiable {
        let number: Int
        let transaction: WalletTransaction
        var id: String { transaction.id }
    }
    var payments: [Payment] = []
    @Environment(\.dismiss) private var dismiss
    @State private var selectedPayment: WalletTransaction?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 24) {
                    ForEach(details.sections) { section in
                        VStack(alignment: .leading, spacing: 0) {
                            SectionHeader(title: section.title)
                                .padding(.horizontal, 4)
                                .padding(.bottom, 8)
                                .accessibilityAddTraits(.isHeader)
                            ForEach(section.rows) { row in
                                TechnicalDetailRow(row: row)
                            }
                            if section.id == details.sections.last?.id, let explorerURL = details.explorerURL {
                                explorerLinkRow(url: explorerURL)
                            }
                        }
                    }

                    if !payments.isEmpty {
                        VStack(spacing: 0) {
                            SectionHeader(title: "Payment details")
                                .padding(.horizontal, 4)
                                .padding(.bottom, 8)
                                .accessibilityAddTraits(.isHeader)
                            ForEach(payments) { payment in
                                Button { selectedPayment = payment.transaction } label: {
                                    HStack {
                                        Text("Payment \(payment.number)").foregroundStyle(.secondary)
                                        Spacer()
                                        Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                                    }
                                    .paymentDetailRow(layout: .history, isInteractive: true)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityIdentifier("cashu.history.details.payment.\(payment.id)")
                            }
                        }
                    }

                    Button("Copy all") {
                        UIPasteboard.general.string = details.copyAllText
                        HapticFeedback.notification(.success)
                        ConfirmationToast.show("Copied details")
                    }
                    .flatSheetSecondaryButton()
                    .accessibilityHint("Copies every detail as text, for a support request")
                    .accessibilityIdentifier("cashu.history.details.copy-all")
                }
                .padding(.horizontal)
                .padding(.vertical, 16)
            }
            .scrollBounceBehavior(.basedOnSize)
            .navigationTitle("Details")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
        .sheet(item: $selectedPayment) { payment in
            TransactionTechnicalDetailsView(transaction: payment)
                .presentationDetents([.large])
        }
    }

    /// The receipt's block explorer row, so Details reaches the same evidence.
    private func explorerLinkRow(url: URL) -> some View {
        Link(destination: url) {
            HStack {
                Text("View in block explorer")
                    .foregroundStyle(.secondary)
                Spacer()
                Image(systemName: "arrow.up.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .paymentDetailRow(layout: .history, isInteractive: true)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .simultaneousGesture(TapGesture().onEnded { HapticFeedback.selection() })
        .accessibilityHint("Opens the block explorer in your browser")
    }
}

/// A receipt row: label and value, tap-to-copy (the full value) when the value
/// is a reference. Stacks label over value at accessibility sizes.
private struct TechnicalDetailRow: View {
    let row: TransactionTechnicalDetails.Row
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        if row.isCopyable {
            Button {
                UIPasteboard.general.string = row.fullValue
                HapticFeedback.notification(.success)
                ConfirmationToast.show(TransactionTechnicalDetails.copyConfirmation(for: row.label))
            } label: {
                content(trailingCopyGlyph: true)
                    .paymentDetailRow(layout: .history, isInteractive: true)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(row.label)
            .accessibilityValue(row.value)
            .accessibilityHint("Copies the full \(row.label.lowercased()) to clipboard")
        } else {
            content(trailingCopyGlyph: false)
                .paymentDetailRow(layout: .history)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(row.label)
                .accessibilityValue(row.value)
        }
    }

    private func content(trailingCopyGlyph: Bool) -> some View {
        let stacked = dynamicTypeSize.isAccessibilitySize
        let layout = stacked
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(spacing: 8))
        return layout {
            Text(row.label)
                .foregroundStyle(.secondary)
            if !stacked {
                Spacer()
            }
            HStack(spacing: 8) {
                Text(row.value)
                    .fontWeight(.regular)
                    .multilineTextAlignment(stacked ? .leading : .trailing)
                    .lineLimit(row.label == "Mint" ? 2 : nil)
                    .truncationMode(.middle)
                    .fixedSize(horizontal: false, vertical: true)
                if trailingCopyGlyph {
                    Image(systemName: "doc.on.doc")
                        .font(.footnote)
                        .foregroundStyle(.tertiary)
                }
            }
            .frame(maxWidth: .infinity, alignment: stacked ? .leading : .trailing)
        }
    }
}
