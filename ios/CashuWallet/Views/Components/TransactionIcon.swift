import SwiftUI

/// Leading icon for a transaction or Cashu Request row.
///
/// A single minimal directional arrow on a soft neutral circle —
/// `arrow.up` for outgoing, `arrow.down` for incoming. The arrow is always
/// rendered in a muted neutral colour; state colour (the One Green Rule) is
/// carried solely by the trailing amount. See DESIGN.md — History Rows.
struct TransactionIcon: View {
    let direction: WalletTransaction.TransactionType
    /// A transfer between the user's own mints is neither received nor sent.
    var isTransfer = false

    private var glyph: String {
        if isTransfer { return "arrow.left.arrow.right" }
        return direction == .incoming ? "arrow.down" : "arrow.up"
    }

    var body: some View {
        Image(systemName: glyph)
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
        Label { Text("Transfer") } icon: { TransactionIcon(direction: .outgoing, isTransfer: true) }
    }
    .padding()
}
