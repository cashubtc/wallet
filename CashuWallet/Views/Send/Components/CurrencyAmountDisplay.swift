import SwiftUI

/// Family-style two-line amount display.
///
/// Renders the active amount in either fiat or sats as the primary (large) line,
/// with the alternate unit underneath. Tapping the secondary line or the `↕`
/// affordance flips which side is primary and persists the choice.
struct CurrencyAmountDisplay: View {
    let sats: UInt64
    @Binding var primary: AmountDisplayPrimary
    var primarySize: CGFloat = 64

    @ObservedObject private var priceService = PriceService.shared
    @ObservedObject private var settings = SettingsManager.shared

    private var fiatAvailable: Bool {
        priceService.btcPriceUSD > 0
    }

    private var effectivePrimary: AmountDisplayPrimary {
        // If user picked fiat but price isn't loaded yet, fall back to sats so we
        // never show "$0.00" as the headline number.
        if primary == .fiat && !fiatAvailable { return .sats }
        return primary
    }

    private var primaryText: String {
        switch effectivePrimary {
        case .fiat: return priceService.formatSatsAsFiat(sats)
        case .sats: return AmountFormatter.sats(sats, useBitcoinSymbol: settings.useBitcoinSymbol)
        }
    }

    private var secondaryText: String {
        switch effectivePrimary {
        case .fiat: return AmountFormatter.sats(sats, useBitcoinSymbol: settings.useBitcoinSymbol)
        case .sats: return priceService.formatSatsAsFiat(sats)
        }
    }

    var body: some View {
        VStack(spacing: 6) {
            Text(primaryText)
                .font(.system(size: primarySize, weight: .semibold, design: .rounded))
                .minimumScaleFactor(0.4)
                .lineLimit(1)
                .contentTransition(.numericText(value: Double(sats)))
                .animation(.snappy, value: sats)
                .animation(.snappy, value: effectivePrimary)

            Button(action: flip) {
                HStack(spacing: 6) {
                    Text(secondaryText)
                        .font(.subheadline.weight(.medium))
                        .contentTransition(.numericText(value: Double(sats)))
                    Image(systemName: "arrow.up.arrow.down")
                        .font(.caption.weight(.semibold))
                }
                .foregroundStyle(.secondary)
                .padding(.horizontal, 10)
                .padding(.vertical, 5)
                .background(.thinMaterial, in: Capsule())
                .contentShape(Capsule())
            }
            .buttonStyle(.plain)
            .disabled(!fiatAvailable)
            .accessibilityLabel("Flip primary currency")
            .accessibilityHint("Currently showing \(primaryText), tap to switch to \(secondaryText)")
        }
    }

    private func flip() {
        guard fiatAvailable else { return }
        HapticFeedback.selection()
        withAnimation(.snappy) {
            primary.toggle()
        }
    }
}
