import SwiftUI

// Shared trailing region for transaction rows on Home and History.
// Renders the configured primary amount plus its optional conversion —
// trailing-aligned.
// Amount styling is the ledger signal: received = green with a plus, sent =
// primary with no sign, failed = red with no sign, pending/expired = muted.
// No status line or row badge;
// re-check lives on History pull-to-refresh. See DESIGN.md — The Received
// Amount Rule, The Quiet Pending Rule,
// The Fiat Sub-Amount Rule.
struct TransactionAmountColumn: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.colorScheme) private var colorScheme

    let transaction: WalletTransaction

    @ObservedObject var settings: SettingsManager = .shared
    @ObservedObject var priceService: PriceService = .shared

    @ViewBuilder
    var body: some View {
        // An unfunded deposit address has no amount yet: no trailing element,
        // like an any-amount Cashu Request that is still waiting.
        if !transaction.isUnfundedAddress {
            amountStack
        }
    }

    private var amountStack: some View {
        ZStack(alignment: .trailing) {
            VStack(alignment: .trailing, spacing: 2) {
                // The Row rung of the amount ladder. It carries the tabular figures,
                // the line limit, the digit transition and the deliberate absence of
                // autoscale — see `CashuTextRole.amountRow`, which documents why
                // those last two cannot both be on.
                Text(formattedAmount)
                    .cashuAmount(.amountRow, value: Double(transaction.amount))
                    .foregroundStyle(amountColor)

                if let secondaryAmount {
                    Text(secondaryAmount)
                        .font(.subheadline)
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                }
            }
            .id(settings.homeBalancePrimary)
            .transition(.opacity)
        }
        .animation(swapAnimation, value: settings.homeBalancePrimary)
    }

    // Received value is the only green element in the row. Sent value stays
    // primary; failed is red, while pending and expired stay muted.
    private var amountColor: Color {
        if transaction.status == .failed {
            // Match Android's error text ink, with at least 4.5:1 contrast on
            // the light and dark list backgrounds at the normal row text size.
            return colorScheme == .dark
                ? Color(red: 239 / 255, green: 83 / 255, blue: 80 / 255)
                : Color(red: 198 / 255, green: 40 / 255, blue: 40 / 255)
        }
        if transaction.isUnsettled { return .secondary }
        return transaction.type == .incoming ? .green : .primary
    }

    // Only a settled receipt gets a sign. Sent, pending, expired, and failed rows stay
    // unsigned; direction remains explicit in the title and arrow.
    private var formattedAmount: String {
        let value = amountDisplay.primary
        guard transaction.status == .completed, transaction.type == .incoming else { return value }
        return "+\(value)"
    }

    private var secondaryAmount: String? {
        amountDisplay.secondary
    }

    private var amountDisplay: AmountDisplayText {
        AmountFormatter.displayMintUnitAmount(
            amount: transaction.amount,
            unit: transaction.unit,
            preferredPrimary: settings.homeBalancePrimary,
            showFiat: settings.showFiatBalance,
            btcPrice: priceService.btcPriceUSD,
            currencyCode: settings.bitcoinPriceCurrency,
            useBitcoinSymbol: settings.useBitcoinSymbol
        )
    }

    private var swapAnimation: Animation {
        reduceMotion ? .easeOut(duration: 0.2) : .snappy
    }
}
