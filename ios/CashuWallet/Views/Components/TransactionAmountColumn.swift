import SwiftUI

// Shared trailing region for transaction rows on Home and History.
// Renders the configured primary amount plus its optional conversion —
// trailing-aligned.
// Amount styling is the ledger signal: received = green with a plus, sent =
// primary with no sign, failed/pending/expired = muted with no sign.
// No status line or row badge;
// re-check lives on History pull-to-refresh. See DESIGN.md — The Received
// Amount Rule, The Quiet Pending Rule,
// The Fiat Sub-Amount Rule.
// Private mode swaps only the digits for asterisks: the sign and ink stay, and the
// fiat line keeps its height without its value, so hiding never moves a row.
struct TransactionAmountColumn: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

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
                    Text(settings.hideBalances ? AmountFormatter.hiddenDigits : secondaryAmount)
                        .font(.subheadline)
                        .monospacedDigit()
                        .foregroundStyle(.secondary)
                        .opacity(settings.hideBalances ? 0 : 1)
                }
            }
            .id(AmountColumnSwap(primary: settings.homeBalancePrimary, hidden: settings.hideBalances))
            .transition(.opacity)
        }
        .animation(swapAnimation, value: settings.homeBalancePrimary)
        .animation(swapAnimation, value: settings.hideBalances)
    }

    // Received value is the only green element in the row. Sent value stays
    // primary; failed, pending, and expired share the same muted amount ink.
    private var amountColor: Color {
        if transaction.isUnsettled || transaction.status == .failed { return .secondary }
        return transaction.type == .incoming ? .green : .primary
    }

    // Only a settled receipt gets a sign. Sent, pending, expired, and failed rows stay
    // unsigned; direction remains explicit in the title.
    private var formattedAmount: String {
        let value = settings.hideBalances ? AmountFormatter.hiddenDigits : amountDisplay.primary
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

/// Identity of a row's amount stack. A change to either half cross-fades the
/// whole stack, so bitcoin ↔ fiat and hiding share one quiet transition.
struct AmountColumnSwap: Hashable {
    let primary: AmountDisplayPrimary
    let hidden: Bool
}
