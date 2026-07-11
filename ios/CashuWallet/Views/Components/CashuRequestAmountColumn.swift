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
            let display = amountDisplay(receivedAmount)
            VStack(alignment: .trailing, spacing: 2) {
                Text("+\(display.primary)")
                    .font(.system(.body, design: .rounded).weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    // No `.minimumScaleFactor` — it collides with `.numericText`
                    // (short amounts collapse toward 50%).
                    .contentTransition(.numericText(value: Double(receivedAmount)))

                if let secondary = display.secondary {
                    Text(secondary)
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
            }
        } else if let amount = request.amount, amount > 0 {
            let display = amountDisplay(amount)
            VStack(alignment: .trailing, spacing: 2) {
                Text(display.primary)
                    .font(.system(.body, design: .rounded).weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
                    .lineLimit(1)

                if let secondary = display.secondary {
                    Text(secondary)
                        .font(.caption)
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
            }
        }
        // "any amount" + waiting: no trailing element.
    }

    private var isSatRequest: Bool { request.unit.lowercased() == "sat" }

    private func amountDisplay(_ amount: UInt64) -> AmountDisplayText {
        guard isSatRequest else {
            return AmountDisplayText(
                primary: CurrencyAmount(
                    value: amount,
                    currency: CurrencyRegistry.currency(forMintUnit: request.unit)
                ).formatted(),
                secondary: nil,
                effectivePrimary: .sats
            )
        }
        return AmountFormatter.displayText(
            amountSats: amount,
            preferredPrimary: settings.amountDisplayPrimary,
            showFiat: settings.showFiatBalance,
            btcPrice: priceService.btcPriceUSD,
            currencyCode: settings.bitcoinPriceCurrency,
            useBitcoinSymbol: settings.useBitcoinSymbol
        )
    }
}
