import SwiftUI

// Shared trailing region for transaction rows on Home and History.
// Renders the amount VStack(sats, optional fiat) — trailing-aligned.
// Amount color is a two-state ledger signal: .primary = settled,
// .secondary = pending. No row badge; pending is conveyed by the muted
// amount alone, and re-check lives on History pull-to-refresh. See
// DESIGN.md — The One Green Rule, The Quiet Pending Rule,
// The Fiat Sub-Amount Rule.
struct TransactionAmountColumn: View {
    let transaction: WalletTransaction

    @ObservedObject var settings: SettingsManager = .shared
    @ObservedObject var priceService: PriceService = .shared

    var body: some View {
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

    private var showFiat: Bool {
        settings.showFiatBalance && priceService.btcPriceUSD > 0
    }

    // Two-state ledger: pending reads muted, everything settled reads
    // .primary. Amounts are never green (see The One Green Rule).
    private var amountColor: Color {
        transaction.status == .pending ? .secondary : .primary
    }

    private var formattedAmount: String {
        let prefix = transaction.type == .incoming ? "+" : "−"
        return "\(prefix)\(settings.formatAmountShort(transaction.amount))"
    }
}
