import SwiftUI

/// The selected mint's role in the value flow. Requiring the role at each call
/// site prevents a receiving mint from being described as the payment source.
enum MintSelectorDirection {
    case source
    case destination

    var label: String {
        switch self {
        case .source: "From"
        case .destination: "To"
        }
    }
}

/// Shared metrics for the unboxed mint selector used throughout value flows.
enum FlowRowMetrics {
    static let minHeight: CGFloat = 48
    static let gap: CGFloat = 8
    static let actionInset: CGFloat = 8
    static let verticalPadding: CGFloat = 6

    /// The chevron's drawn width. A 48pt frame around an ~12pt glyph left 18pt
    /// of dead space on each side, which is what pushed the glyph a third of
    /// the way in from the trailing margin while inflating its gap to Send Max
    /// to three times the gap inside the identity. The hit area stays 44pt via
    /// `hitSlop`; only the layout box shrinks.
    static let chevronBox: CGFloat = 20

    /// Added to a control's hit area without costing layout width.
    static let hitSlop: CGFloat = 12
}

/// Amount entry groups the mint with the amount it qualifies. The picker is a
/// single centered target; spending context and Max occupy a quieter second line.
struct AmountEntryMintSelector: View {
    let direction: MintSelectorDirection
    let mint: MintInfo
    var balanceText: String? = nil
    var onUseMax: (() -> Void)? = nil
    var onChooseMint: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 0) {
            if let onChooseMint {
                Button(action: onChooseMint) { identity }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(direction.label) \(mint.name)")
                    .accessibilityHint("Choose a different mint")
            } else {
                identity
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("\(direction.label) \(mint.name)")
            }
            if let balanceText {
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) { availableBalance(balanceText); maxAction }
                    VStack(spacing: 0) { availableBalance(balanceText); maxAction }
                }
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var identity: some View {
        HStack(spacing: 6) {
            Text("\(direction.label) \(mint.name)")
                .multilineTextAlignment(.center)
                .lineLimit(2)
                .fixedSize(horizontal: false, vertical: true)
            if onChooseMint != nil {
                Image(systemName: "chevron.down")
                    .font(.caption)
                    .accessibilityHidden(true)
            }
        }
        .font(.subheadline)
        .foregroundStyle(.secondary)
        .padding(.horizontal, 8)
        .frame(minHeight: 48)
        .contentShape(Rectangle())
    }

    private func availableBalance(_ text: String) -> some View {
        Text("\(text) available")
            .font(.subheadline)
            .monospacedDigit()
            .foregroundStyle(.secondary)
            .fixedSize(horizontal: false, vertical: true)
            .multilineTextAlignment(.center)
    }

    @ViewBuilder
    private var maxAction: some View {
        if let onUseMax {
            Button(action: onUseMax) {
                Text("Max")
                    .font(.subheadline.weight(.medium))
                    .frame(minWidth: 44, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Send maximum")
            .accessibilityHint("Fill the amount with your full mint balance")
        }
    }
}
