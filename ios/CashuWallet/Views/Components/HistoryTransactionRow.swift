import SwiftUI

/// History's native row, also rendered by the debug catalog for UI regression checks.
struct HistoryTransactionRow: View {
    let transaction: WalletTransaction
    let timestamp: String
    let amountAccessibilityText: String?
    let onSelect: () -> Void

    private var accessibilityLabel: String {
        [transaction.displayTitle, amountAccessibilityText,
         transaction.displayStatusText.lowercased(), timestamp]
            .compactMap { $0 }
            .joined(separator: ", ")
    }

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 14) {
                TransactionIcon(direction: transaction.type)
                    .frame(width: 36, height: 36)

                VStack(alignment: .leading, spacing: 4) {
                    Text(transaction.displayTitle)
                        .font(.body.weight(.medium))
                        .lineLimit(1)

                    Text(timestamp)
                        .cashuText(.metadata)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)
                TransactionAmountColumn(transaction: transaction)
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 16)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityLabel)
        .accessibilityHint(transaction.isPendingReceiveToken ? "Opens receive review" : "Opens transaction details")
    }
}
