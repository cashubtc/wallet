import SwiftUI

// Shared trailing region for Cashu Request rows on Home and History.
// - Received: green +amount + fiat sub-line.
// - Waiting (fixed amount): clock to the LEFT, amount + fiat to the right.
// - Waiting (any amount, no fixed expected total): no trailing element.
// See DESIGN.md — The Amount Column Rule, The One Green Rule,
// The Fiat Sub-Amount Rule.
struct CashuRequestAmountColumn: View {
    let request: CashuRequest
    let received: Bool
    let receivedAmount: UInt64

    @ObservedObject var settings: SettingsManager = .shared
    @ObservedObject var priceService: PriceService = .shared

    var body: some View {
        HStack(spacing: 10) {
            if shouldShowClock {
                Image(systemName: "clock")
                    .font(.caption2.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }

            amountStack
        }
    }

    @ViewBuilder
    private var amountStack: some View {
        if received {
            VStack(alignment: .trailing, spacing: 2) {
                Text("+\(settings.formatAmountShort(receivedAmount))")
                    .font(.system(.body, design: .rounded).weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(Color.green)
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
                    .font(.system(.body, design: .rounded).weight(.medium))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)

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

    private var shouldShowClock: Bool {
        guard !received else { return false }
        if let amount = request.amount, amount > 0 { return true }
        return false
    }

    private var showFiat: Bool {
        settings.showFiatBalance && priceService.btcPriceUSD > 0
    }
}
