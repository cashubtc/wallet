import SwiftUI

// Shared trailing region for Cashu Request rows on Home and History.
// - Received: .primary +amount + fiat sub-line (settled reads white).
// - Waiting (fixed amount): muted amount + fiat, no indicator (gray = waiting).
// - Waiting (any amount, no fixed expected total): no trailing element.
// All amounts share the .semibold weight. See DESIGN.md —
// The Amount Column Rule, The One Green Rule, The Fiat Sub-Amount Rule.
struct CashuRequestAmountColumn: View {
    let request: CashuRequest
    let received: Bool
    let receivedAmount: UInt64

    @ObservedObject var settings: SettingsManager = .shared
    @ObservedObject var priceService: PriceService = .shared

    @ViewBuilder
    var body: some View {
        if received {
            VStack(alignment: .trailing, spacing: 2) {
                Text("+\(settings.formatAmountShort(receivedAmount))")
                    .font(.system(.body, design: .rounded).weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    // No `.minimumScaleFactor` — it collides with `.numericText`
                    // (short amounts collapse toward 50%). Amounts are abbreviated,
                    // so the trailing column never truncates them.
                    .contentTransition(.numericText(value: Double(receivedAmount)))

                if showFiat {
                    Text(priceService.formatSatsAsFiat(receivedAmount))
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
            }
        } else if let amount = request.amount, amount > 0 {
            VStack(alignment: .trailing, spacing: 2) {
                Text(settings.formatAmountShort(amount))
                    .font(.system(.body, design: .rounded).weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                if showFiat {
                    Text(priceService.formatSatsAsFiat(amount))
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
            }
        }
        // "any amount" + waiting: no trailing element.
    }

    private var showFiat: Bool {
        settings.showFiatBalance && priceService.btcPriceUSD > 0
    }
}
