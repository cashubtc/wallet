import SwiftUI

/// The identifiers behind a receipt, for support and debugging. Opened from the
/// receipt's last row as a nested `.large` sheet — the Description "Read more"
/// precedent — so the content-fit receipt never pushes a page it would clip.
struct TransactionTechnicalDetailsView: View {
    let transaction: WalletTransaction
    @EnvironmentObject private var walletManager: WalletManager
    @Environment(\.dismiss) private var dismiss
    @State private var loaded: TransactionTechnicalDetails?

    /// Row-derived sections render at once; the stored quote fills in after
    /// its local read.
    private var details: TransactionTechnicalDetails {
        loaded ?? TransactionTechnicalDetails(transaction: transaction)
    }

    private var explorerURL: URL? {
        guard transaction.kind == .onchain else { return nil }
        if let txid = transaction.preimage {
            return OnchainExplorer.transactionWebURL(
                for: txid,
                address: transaction.invoice,
                mintURL: transaction.mintUrl
            )
        }
        guard let address = transaction.invoice else { return nil }
        return OnchainExplorer.addressWebURL(for: address, mintURL: transaction.mintUrl)
    }

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
                            if section.id == details.sections.last?.id, let explorerURL {
                                explorerLinkRow(url: explorerURL)
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
        .task(id: transaction.id) {
            loaded = await walletManager.transactionTechnicalDetails(for: transaction)
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
