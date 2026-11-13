import SwiftUI

/// Shared ledger row; hosts retain navigation and pending-token removal actions.
struct TransactionRowView: View {
    @ObservedObject private var settings = SettingsManager.shared
    @ObservedObject private var priceService = PriceService.shared
    let transaction: WalletTransaction
    /// Names a transfer's destination mint in its title, as History does.
    var mints: [MintInfo] = []
    var horizontalPadding: CGFloat = 4
    var verticalPadding: CGFloat = 16
    var accessibilityHint: String = "Opens transaction details"
    let onSelect: () -> Void

    var body: some View {
        Button(action: onSelect) {
            HStack(spacing: 14) {
                TransactionIcon(
                    direction: transaction.type,
                    isTransfer: transaction.transfer != nil,
                    isFailed: transaction.status == .failed
                )
                    .frame(width: 36, height: 36)

                VStack(alignment: .leading, spacing: 4) {
                    Text(transaction.displayTitle(mints: mints))
                        .font(.body.weight(.medium))
                        .lineLimit(1)

                    Text(Self.formatRelativeDate(transaction.date))
                        .cashuText(.metadata)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)

                TransactionAmountColumn(transaction: transaction)
            }
            .padding(.horizontal, horizontalPadding)
            .padding(.vertical, verticalPadding)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(rowAccessibilityLabel(for: transaction))
        .accessibilityHint(accessibilityHint)
    }

    private func rowAccessibilityLabel(for transaction: WalletTransaction) -> String {
        [
            transaction.displayTitle(mints: mints),
            transaction.isUnfundedAddress ? nil : formatAmount(transaction),
            transaction.status == .completed ? "completed" : transaction.displayStatusText.lowercased(),
            Self.formatRelativeDate(transaction.date),
        ]
        .compactMap { $0 }
        .joined(separator: ", ")
    }

    private func formatAmount(_ transaction: WalletTransaction) -> String {
        if settings.hideBalances { return AmountFormatter.hiddenRowAmountAccessibility }
        let value = AmountFormatter.displayMintUnitAmount(
            amount: transaction.amount,
            unit: transaction.unit,
            preferredPrimary: settings.homeBalancePrimary,
            showFiat: settings.showFiatBalance,
            btcPrice: priceService.btcPriceUSD,
            currencyCode: settings.bitcoinPriceCurrency,
            useBitcoinSymbol: settings.useBitcoinSymbol
        ).primary
        guard !transaction.isUnsettled else { return value }
        return transaction.type == .incoming ? "+\(value)" : value
    }

    // MARK: - Relative date

    private static let shortTimeFormatter: DateFormatter = {
        let f = DateFormatter()
        f.dateStyle = .none
        f.timeStyle = .short
        return f
    }()

    private static let sameYearDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("MMMd")
        return f
    }()

    private static let otherYearDateFormatter: DateFormatter = {
        let f = DateFormatter()
        f.setLocalizedDateFormatFromTemplate("MMMdyyyy")
        return f
    }()

    static func formatRelativeDate(_ date: Date) -> String {
        let now = Date()
        let delta = now.timeIntervalSince(date)
        if delta < 60 { return "Now" }

        let calendar = Calendar.current
        if calendar.isDateInToday(date) {
            if delta < 3600 {
                let minutes = max(1, Int(delta / 60))
                return "\(minutes) min ago"
            }
            return Self.shortTimeFormatter.string(from: date)
        }
        if calendar.isDateInYesterday(date) {
            return "Yesterday \(Self.shortTimeFormatter.string(from: date))"
        }
        let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: now)
        return (sameYear ? Self.sameYearDateFormatter : Self.otherYearDateFormatter).string(from: date)
    }

}
