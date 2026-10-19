import SwiftUI

/// Leading icon for a transaction or Cashu Request row.
///
/// A single minimal directional arrow on a soft neutral circle —
/// `arrow.up` for outgoing, `arrow.down` for incoming; failed transactions
/// use an X. All glyphs remain muted on the same soft neutral circle.
/// The containing row announces status. See DESIGN.md — History Rows.
struct TransactionIcon: View {
    let direction: WalletTransaction.TransactionType
    var isFailed = false

    var symbolName: String {
        isFailed ? "xmark" : (direction == .incoming ? "arrow.down" : "arrow.up")
    }

    var body: some View {
        Image(systemName: symbolName)
            .font(.system(size: 16, weight: .medium))
            .foregroundStyle(.secondary)
            .frame(width: 36, height: 36)
            .background(Color(.secondarySystemFill), in: Circle())
            .accessibilityHidden(true)
    }
}

#Preview {
    VStack(spacing: 20) {
        Label { Text("Received") } icon: { TransactionIcon(direction: .incoming) }
        Label { Text("Sent") } icon: { TransactionIcon(direction: .outgoing) }
    }
    .padding()
}
