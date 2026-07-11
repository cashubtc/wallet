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
                .lineLimit(1)
                // No `.minimumScaleFactor` here: it collides with `.numericText`
                // (the numeric renderer reports a tiny intermediate width and the
                // scale factor then shrinks short amounts toward 50%). Row amounts
                // use compact grouped wallet formatting, so they remain readable.
                .contentTransition(.numericText(value: Double(transaction.amount)))

            if let secondaryAmount {
                Text(secondaryAmount)
                    .font(.caption)
                    .monospacedDigit()
                    .foregroundStyle(.secondary)
            }
        }
    }

    // Two-state ledger: pending reads muted, everything settled reads
    // .primary. Amounts are never green (see The One Green Rule).
    private var amountColor: Color {
        transaction.status == .pending ? .secondary : .primary
    }

    // The +/− sign is a *settled-ledger* signal: a pending receive hasn't
    // credited the balance and a pending send hasn't debited it, so neither
    // wears a sign until it settles — matching the waiting Cashu Request /
    // Reusable Invoice, which shows a bare amount until paid. The sign and the
    // `.primary` colour arrive together on settlement. See DESIGN.md — The
    // Quiet Pending Rule.
    private var formattedAmount: String {
        let value = nativeAmount
        guard transaction.status != .pending else { return value }
        let prefix = transaction.type == .incoming ? "+" : "−"
        return "\(prefix)\(value)"
    }

    private var isSatUnit: Bool {
        transaction.unit.lowercased() == "sat"
    }

    private var nativeAmount: String {
        if isSatUnit {
            return satDisplay.primary
        }
        return CurrencyAmount(
            value: transaction.amount,
            currency: CurrencyRegistry.currency(forMintUnit: transaction.unit)
        ).formatted()
    }

    private var secondaryAmount: String? {
        isSatUnit ? satDisplay.secondary : nil
    }

    private var satDisplay: AmountDisplayText {
        AmountFormatter.displayText(
            amountSats: transaction.amount,
            preferredPrimary: settings.amountDisplayPrimary,
            showFiat: settings.showFiatBalance,
            btcPrice: priceService.btcPriceUSD,
            currencyCode: settings.bitcoinPriceCurrency,
            useBitcoinSymbol: settings.useBitcoinSymbol
        )
    }
}
