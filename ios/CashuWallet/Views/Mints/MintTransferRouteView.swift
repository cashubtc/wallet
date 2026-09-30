import SwiftUI

/// The two ends of a transfer, stacked: the mint the ecash leaves, the control
/// that swaps the two, and the mint it arrives at. Top is always From.
///
/// Unlike the centered From/To line on the pay screens, both ends here are the
/// user's own mints and the choice between them is the point of the screen, so
/// each gets its avatar, name and balance. No fill or card: one hairline.
struct MintTransferRouteView: View {
    let source: MintInfo
    let destination: MintInfo
    let sourceBalanceText: String
    let destinationBalanceText: String
    /// Short screens keep the destination to its identity line.
    var showsDestinationBalance = true
    var isFindingMax = false
    var onUseMax: (() -> Void)?
    /// Nil when there is no other mint to choose; the rows then only inform.
    var onChooseSource: (() -> Void)?
    var onChooseDestination: (() -> Void)?
    /// Nil when the two mints cannot trade places.
    var onSwap: (() -> Void)?

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var slots
    @State private var swapCount = 0

    private enum Metrics {
        static let avatar: CGFloat = 32
        static let gap: CGFloat = 12
        static let identityHeight: CGFloat = 44
        static let supportHeight: CGFloat = 24
        static let swapDiameter: CGFloat = 36
    }

    var body: some View {
        VStack(spacing: 0) {
            slot(source, direction: .source, onChoose: onChooseSource) { sourceSupport }
            swapDivider
            slot(destination, direction: .destination, onChoose: onChooseDestination) {
                if showsDestinationBalance { destinationSupport }
            }
        }
        // Each mint's identity is drawn once, above the slots, and follows the
        // placeholder that carries its id. When the mints trade slots the
        // identities travel to their new places instead of being redrawn.
        .overlay {
            if !reduceMotion {
                ForEach([source, destination]) { mint in
                    let direction: MintSelectorDirection = mint.id == source.id ? .source : .destination
                    identity(mint, direction: direction, onChoose: direction == .source ? onChooseSource : onChooseDestination)
                        .matchedGeometryEffect(id: mint.id, in: slots, isSource: false)
                }
            }
        }
    }

    private func slot<Support: View>(
        _ mint: MintInfo,
        direction: MintSelectorDirection,
        onChoose: (() -> Void)?,
        @ViewBuilder support: () -> Support
    ) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if reduceMotion {
                // No travel: the identity is replaced in place with a fade.
                identity(mint, direction: direction, onChoose: onChoose)
                    .id(mint.id)
                    .transition(.opacity)
            } else {
                identity(mint, direction: direction, onChoose: onChoose)
                    .hidden()
                    .accessibilityHidden(true)
                    .matchedGeometryEffect(id: mint.id, in: slots)
            }
            support()
                .padding(.leading, Metrics.avatar + Metrics.gap)
        }
    }

    @ViewBuilder
    private func identity(
        _ mint: MintInfo,
        direction: MintSelectorDirection,
        onChoose: (() -> Void)?
    ) -> some View {
        let content = HStack(spacing: Metrics.gap) {
            MintAvatarView(iconUrl: mint.iconUrl, name: mint.name, size: Metrics.avatar)
            Text(mint.name)
                .font(.body.weight(.medium))
                .lineLimit(1)
                .truncationMode(.tail)
            Spacer(minLength: 8)
            if onChoose != nil {
                Image(systemName: "chevron.down")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
        }
        .frame(minHeight: Metrics.identityHeight)
        .contentShape(Rectangle())

        if let onChoose {
            Button(action: onChoose) { content }
                .buttonStyle(.plain)
                .accessibilityLabel("\(direction.label) \(mint.name)")
                .accessibilityHint("Choose a different mint")
        } else {
            content
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("\(direction.label) \(mint.name)")
        }
    }

    private var sourceSupport: some View {
        // Side by side while both fit; at large text Max drops under the
        // balance rather than truncating it.
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                supportText("\(sourceBalanceText) available")
                Spacer(minLength: 8)
                maxControl
                    // The 44pt target must not grow the line it shares.
                    .padding(.vertical, (Metrics.supportHeight - 44) / 2)
            }
            VStack(alignment: .leading, spacing: 0) {
                supportText("\(sourceBalanceText) available")
                maxControl
            }
        }
        .frame(minHeight: Metrics.supportHeight)
    }

    @ViewBuilder
    private var maxControl: some View {
        if let onUseMax {
            Button(action: onUseMax) {
                ZStack {
                    Text("Max")
                        .font(.subheadline.weight(.medium))
                        .opacity(isFindingMax ? 0 : 1)
                    if isFindingMax {
                        ProgressView().controlSize(.small)
                    }
                }
                .frame(minWidth: 44, minHeight: 44)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .disabled(isFindingMax)
            .accessibilityLabel("Transfer maximum")
            .accessibilityHint("Fill the largest amount this mint can transfer after fees")
            .accessibilityValue(isFindingMax ? "Checking the network fee" : "")
        }
    }

    private var destinationSupport: some View {
        supportText(destinationBalanceText)
            .frame(minHeight: Metrics.supportHeight, alignment: .leading)
            .accessibilityLabel("Balance \(destinationBalanceText)")
    }

    private func supportText(_ text: String) -> some View {
        Text(text)
            .font(.subheadline)
            .monospacedDigit()
            .foregroundStyle(.secondary)
            .lineLimit(1)
            // A balance is a money value: it never truncates to fit.
            .fixedSize(horizontal: true, vertical: false)
            .contentTransition(.numericText())
    }

    private var swapDivider: some View {
        HStack(spacing: Metrics.gap) {
            hairline
            Button {
                swapCount += 1
                onSwap?()
            } label: {
                Image(systemName: "arrow.down")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    // A full turn: the mints trade places, the direction does not.
                    .rotationEffect(.degrees(reduceMotion ? 0 : Double(swapCount) * 360))
                    .animation(.snappy(duration: 0.28), value: swapCount)
                    .frame(width: Metrics.swapDiameter, height: Metrics.swapDiameter)
                    .background(Color(.secondarySystemFill), in: Circle())
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressableButtonStyle())
            .disabled(onSwap == nil)
            .opacity(onSwap == nil ? DisabledControlOpacity.content : 1)
            .accessibilityLabel("Swap direction")
            .accessibilityHint("Transfer from \(destination.name) to \(source.name) instead")
            hairline
        }
    }

    private var hairline: some View {
        Rectangle()
            .fill(Color(.separator))
            .frame(height: 0.5)
            .accessibilityHidden(true)
    }
}

/// Chooses the mint for one end of a transfer. Every held mint is listed so the
/// user can see why one is unavailable; those rows carry the reason and do
/// nothing.
struct MintTransferMintPicker: View {
    struct Option: Identifiable {
        let mint: MintInfo
        /// Why this mint cannot take the slot, or nil when it can.
        let unavailableReason: String?
        var id: String { mint.id }
    }

    let direction: MintSelectorDirection
    let options: [Option]
    let selectedMintURL: String
    let onSelect: (MintInfo) -> Void

    @Environment(\.dismiss) private var dismiss

    /// The same viewport as the pay flows' mint picker: the title and roughly
    /// four rows, scrolling beyond that.
    private static let pickerHeight: CGFloat = 368

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(options) { option in
                        row(option)
                    }
                }
            }
            .scrollBounceBehavior(.basedOnSize)
            .navigationTitle(direction == .source ? "Transfer from" : "Transfer to")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.height(Self.pickerHeight)])
        .presentationDragIndicator(.visible)
        .compactBottomSheetSurface()
    }

    private func row(_ option: Option) -> some View {
        let isSelected = MintURLIdentity.normalized(option.mint.url) == MintURLIdentity.normalized(selectedMintURL)
        // The same form the route rows show, so a balance reads alike in both.
        let balance = AmountFormatter.sats(
            option.mint.balance,
            useBitcoinSymbol: SettingsManager.shared.useBitcoinSymbol
        )
        return Button {
            HapticFeedback.selection()
            onSelect(option.mint)
            dismiss()
        } label: {
            HStack(spacing: 12) {
                MintAvatarView(iconUrl: option.mint.iconUrl, name: option.mint.name, size: 40)

                VStack(alignment: .leading, spacing: 2) {
                    Text(option.mint.name)
                        .font(.body.weight(.medium))
                        .lineLimit(1)
                    Text(option.unavailableReason ?? balance)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }

                Spacer()

                if isSelected {
                    Image(systemName: "checkmark")
                        .foregroundStyle(Color.accentColor)
                }
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
            .opacity(option.unavailableReason == nil ? 1 : DisabledControlOpacity.content)
        }
        .buttonStyle(.plain)
        .disabled(option.unavailableReason != nil)
        .accessibilityLabel(option.mint.name)
        .accessibilityValue(option.unavailableReason ?? balance)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
