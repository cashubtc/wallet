import SwiftUI

// Shared trailing region for transaction rows on Home and History.
// Renders: [optional refresh button] [amount VStack(sats, optional fiat)].
// Refresh button sits LEFT of the amount so the amount column stays
// trailing-aligned across rows with and without indicators. See
// DESIGN.md — The Amount Column Rule, The One Green Rule,
// The Fiat Sub-Amount Rule.
struct TransactionAmountColumn: View {
    let transaction: WalletTransaction
    let isCheckingStatus: String?
    let onRefresh: () -> Void

    @ObservedObject var settings: SettingsManager = .shared
    @ObservedObject var priceService: PriceService = .shared

    var body: some View {
        HStack(spacing: 10) {
            if transaction.status == .pending {
                refreshButton
            }

            VStack(alignment: .trailing, spacing: 2) {
                Text(formattedAmount)
                    .font(.system(.body, design: .rounded).weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(amountColor)
                    .contentTransition(.numericText(value: Double(transaction.amount)))

                if showFiat {
                    Text(priceService.formatSatsAsFiat(transaction.amount))
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
            }
        }
    }

    private var refreshButton: some View {
        Button(action: onRefresh) {
            if isCheckingStatus == transaction.id {
                ProgressView().controlSize(.small)
            } else {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .buttonStyle(.borderless)
        .accessibilityLabel(isCheckingStatus == transaction.id ? "Checking status" : "Refresh status")
        .accessibilityHint(refreshHint)
    }

    private var showFiat: Bool {
        settings.showFiatBalance && priceService.btcPriceUSD > 0
    }

    // Direction-aware: green only for incoming completed; outgoing
    // completed reads .primary; pending stays muted in both directions.
    private var amountColor: Color {
        if transaction.status == .pending { return .secondary }
        if transaction.status == .completed && transaction.type == .incoming { return .green }
        return .primary
    }

    private var formattedAmount: String {
        let prefix = transaction.type == .incoming ? "+" : "−"
        return "\(prefix)\(settings.formatAmountShort(transaction.amount))"
    }

    private var refreshHint: String {
        switch transaction.kind {
        case .ecash:                return "Checks if this pending token has been claimed"
        case .lightning, .onchain:  return "Refreshes this pending receive request"
        }
    }
}
