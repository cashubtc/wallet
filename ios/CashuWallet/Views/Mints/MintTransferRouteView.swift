import SwiftUI

/// The two ends of a transfer, stacked: the mint the ecash leaves, the control
/// that swaps the two, and the mint it arrives at. Top is always From.
///
/// Unlike the centered From/To line on the pay screens, both ends here are the
/// user's own mints and the choice between them is the point of the screen, so
/// each gets its name and balance. No avatar, fill or card: one hairline.
struct MintTransferRouteView: View {
    let source: MintInfo
    let destination: MintInfo
    let sourceBalanceText: String
    let destinationBalanceText: String
    /// Why an end can't take part as it stands ("too little to cover the
    /// fee"). Said on that mint's own line, not under the amount: the problem
    /// is the mint, not the number.
    var sourceProblem: String?
    var destinationProblem: String?
    /// Short screens keep the destination to its identity line.
    var showsDestinationBalance = true
    var isFindingMax = false
    var onUseMax: (() -> Void)?
    /// Nil when there is no other mint to choose; the rows then only inform.
    var onChooseSource: (() -> Void)?
    var onChooseDestination: (() -> Void)?
    /// Nil when the two mints cannot trade places.
    var onSwap: (() -> Void)?

    /// The name's line at the current text size.
    @ScaledMetric(relativeTo: .body) private var nameLineHeight: CGFloat = 22

    private enum Metrics {
        static let identityHeight: CGFloat = 44
        static let supportHeight: CGFloat = 24
        static let swapDiameter: CGFloat = 36
        static let captionGap: CGFloat = 4
        static let gap: CGFloat = 12
    }

    /// The part of the name row's 44pt target that rises over its caption
    /// rather than sitting between the name and its balance, so the balance
    /// reads as the name's second line. As text grows the name fills more of
    /// the target, until it fills it.
    private var touchOverhang: CGFloat {
        max(0, Metrics.identityHeight - nameLineHeight)
    }

    var body: some View {
        VStack(spacing: 0) {
            slot(source, direction: .source, onChoose: onChooseSource) { sourceSupport }
            swapDivider
            slot(destination, direction: .destination, onChoose: onChooseDestination) {
                // A short screen drops the balance, never the reason it can't
                // receive.
                if showsDestinationBalance || destinationProblem != nil {
                    destinationSupport
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
            // The slot carries the direction, so the caption stays put while
            // the mints trade places. VoiceOver hears it in the row's label.
            Text(direction.label)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .padding(.bottom, Metrics.captionGap)
                .accessibilityHidden(true)
            // The mints change places without motion (DESIGN.md §6,
            // animation 8): the press and the haptic answer the tap.
            identity(mint, direction: direction, onChoose: onChoose)
            support()
        }
    }

    private func identity(
        _ mint: MintInfo,
        direction: MintSelectorDirection,
        onChoose: (() -> Void)?
    ) -> some View {
        // The target keeps its full height but claims only the row's: the
        // spare height overhangs the caption, which takes no taps.
        identityControl(mint, direction: direction, onChoose: onChoose)
            .padding(.top, -touchOverhang)
    }

    @ViewBuilder
    private func identityControl(
        _ mint: MintInfo,
        direction: MintSelectorDirection,
        onChoose: (() -> Void)?
    ) -> some View {
        let content = HStack(spacing: Metrics.gap) {
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
        .frame(minHeight: Metrics.identityHeight, alignment: .bottom)
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

    @ViewBuilder
    private var sourceSupport: some View {
        if let sourceProblem {
            // A mint with a problem has no maximum to offer, so the line is
            // the reason.
            supportText(problemLine(sourceBalanceText, sourceProblem))
                .frame(minHeight: Metrics.supportHeight, alignment: .leading)
                .accessibilityLabel("\(sourceBalanceText) available, \(sourceProblem)")
        } else if let onUseMax {
            availableBalance(onUseMax)
        } else {
            // An empty mint has nothing to take: its balance only informs.
            supportText(sourceBalanceText)
                .frame(minHeight: Metrics.supportHeight, alignment: .leading)
                .accessibilityLabel("\(sourceBalanceText) available")
        }
    }

    /// The balance is the maximum: tapping it fills the largest amount this
    /// mint can transfer after fees. It reads as the plain balance; the
    /// whole-balance hint is what teaches the tap.
    private func availableBalance(_ onUseMax: @escaping () -> Void) -> some View {
        Button(action: onUseMax) {
            HStack(spacing: 6) {
                supportText(sourceBalanceText)
                if isFindingMax {
                    ProgressView().controlSize(.mini)
                }
            }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        // The 44pt target must not grow the line it sits on.
        .padding(.vertical, (Metrics.supportHeight - 44) / 2)
        .disabled(isFindingMax)
        .accessibilityLabel("\(sourceBalanceText) available")
        .accessibilityHint("Fills the largest amount this mint can transfer after fees")
        .accessibilityValue(isFindingMax ? "Checking the network fee" : "")
        .accessibilityIdentifier("mints-transfer-max")
    }

    /// What the destination holds. The plain number reads as its balance;
    /// VoiceOver, without the layout, hears the word.
    private var destinationSupport: some View {
        let line = destinationProblem.map { problemLine(destinationBalanceText, $0) }
            ?? Text(destinationBalanceText)
        let label = destinationProblem.map { "Balance \(destinationBalanceText), \($0)" }
            ?? "Balance \(destinationBalanceText)"
        return supportText(line)
            .frame(minHeight: Metrics.supportHeight, alignment: .leading)
            .accessibilityLabel(label)
    }

    /// An inline notice moved into the row: the caution glyph carries the
    /// colour and the words stay secondary, so the line keeps its contrast.
    private func problemLine(_ balance: String, _ problem: String) -> Text {
        let glyph = Text(Image(systemName: ErrorSeverity.caution.icon))
            .foregroundStyle(ErrorSeverity.caution.foreground)
        return Text("\(glyph) \(balance) · \(problem)")
    }

    private func supportText(_ text: String) -> some View {
        supportText(Text(text))
    }

    private func supportText(_ text: Text) -> some View {
        text
            .font(.subheadline)
            .monospacedDigit()
            .foregroundStyle(.secondary)
            // A balance is a money value: it wraps at large text rather than
            // truncating or running past the row.
            .fixedSize(horizontal: false, vertical: true)
    }

    private var swapDivider: some View {
        HStack(spacing: Metrics.gap) {
            hairline
            Button {
                onSwap?()
            } label: {
                // The captions carry the direction, so the glyph is symmetric
                // and holds still: the mints moving is the answer to the tap,
                // the press and the haptic are its feedback.
                Image(systemName: "arrow.up.arrow.down")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .frame(width: Metrics.swapDiameter, height: Metrics.swapDiameter)
                    .liquidGlass(in: Circle(), interactive: true)
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(PressableButtonStyle())
            .disabled(onSwap == nil)
            .opacity(onSwap == nil ? DisabledControlOpacity.content : 1)
            .accessibilityLabel("Swap mints")
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
    private static let rowsInViewport = 4

    /// Beyond the viewport's four rows the sheet can also be pulled up to
    /// show them all; with four or fewer there is nothing more to reveal.
    private var detents: Set<PresentationDetent> {
        options.count > Self.rowsInViewport ? [.height(Self.pickerHeight), .large] : [.height(Self.pickerHeight)]
    }

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
        .presentationDetents(detents)
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
