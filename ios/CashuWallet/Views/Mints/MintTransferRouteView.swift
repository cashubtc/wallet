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
    /// The amount is the one Max filled, until it changes.
    var isUsingMax = false
    var onUseMax: (() -> Void)?
    /// Nil when there is no other mint to choose; the rows then only inform.
    var onChooseSource: (() -> Void)?
    var onChooseDestination: (() -> Void)?
    /// Nil when the two mints cannot trade places.
    var onSwap: (() -> Void)?

    /// The name's line at the current text size.
    @ScaledMetric(relativeTo: .body) private var nameLineHeight: CGFloat = 22

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// Each swap turns the glyph another half turn.
    @State private var swapCount = 0

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
                .cashuText(.metadata)
                .foregroundStyle(.secondary)
                .padding(.bottom, Metrics.captionGap)
                .accessibilityHidden(true)
            identity(mint, direction: direction, onChoose: onChoose)
            // The balance line belongs to the mint, so it changes with the
            // name. Stacked, so the line on its way out never pushes the new
            // one down.
            ZStack(alignment: .leading) {
                support()
                    .id(mint.id)
                    .transition(mintChange)
            }
        }
        // A different mint taking the slot is the only change that morphs
        // (DESIGN.md §6, animation 8); the caption stays put, and the
        // chevron keeps its place after the name.
        .animation(reduceMotion ? .easeInOut(duration: 0.2) : .snappy(duration: 0.28), value: mint.id)
    }

    /// The old mint's lines go out of focus as the new one's come into it, in
    /// place: nothing travels between the slots.
    private var mintChange: AnyTransition {
        reduceMotion ? .opacity : AnyTransition(.blurReplace)
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
        // The chevron follows the name, as on Send's "From {mint}", which
        // leaves the trailing edge to Max on the line below.
        let content = HStack(spacing: 6) {
            // The name belongs to the mint, the chevron to the slot.
            ZStack(alignment: .leading) {
                Text(mint.name)
                    .font(.body.weight(.medium))
                    .lineLimit(1)
                    .truncationMode(.tail)
                    .id(mint.id)
                    .transition(mintChange)
            }
            if onChoose != nil {
                Image(systemName: "chevron.down")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
            Spacer(minLength: 0)
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
            balanceAndMax(onUseMax)
        } else {
            // An empty mint has nothing to take: its balance only informs.
            sourceBalance
                .frame(minHeight: Metrics.supportHeight, alignment: .leading)
        }
    }

    private var sourceBalance: some View {
        supportText(sourceBalanceText)
            .accessibilityLabel("\(sourceBalanceText) available")
    }

    /// Max sits at the end of the balance's line, as on Send. Side by side
    /// while both fit; at large text Max drops under the balance rather than
    /// squeezing it.
    private func balanceAndMax(_ onUseMax: @escaping () -> Void) -> some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: Metrics.gap) {
                sourceBalance
                Spacer(minLength: 8)
                maxControl(onUseMax, alignment: .trailing)
                    // The 44pt target must not grow the line it shares.
                    .padding(.vertical, (Metrics.supportHeight - 44) / 2)
            }
            VStack(alignment: .leading, spacing: 0) {
                sourceBalance
                maxControl(onUseMax, alignment: .leading)
            }
        }
        .frame(minHeight: Metrics.supportHeight)
    }

    /// Fills the largest amount this mint can transfer after fees. The word
    /// keeps its place under the spinner, so the line never shifts. Once used
    /// it reads "Using Max", dimmed, until the amount changes: that is why
    /// the amount came out under the balance (Family's pattern).
    private func maxControl(_ onUseMax: @escaping () -> Void, alignment: Alignment) -> some View {
        let label: LocalizedStringKey = isUsingMax ? "Using Max" : "Max"
        let spokenLabel: LocalizedStringKey = isUsingMax ? "Using the maximum" : "Transfer maximum"
        return Button(action: onUseMax) {
            ZStack(alignment: alignment) {
                // The longer word holds the width, so Max landing neither
                // moves the line nor tips it into the stacked layout.
                Text("Using Max")
                    .cashuText(.textLink)
                    .hidden()
                // One word fades into the other, each pinned to the edge,
                // rather than one Text sliding between two widths.
                Text(label)
                    .cashuText(.textLink)
                    .foregroundStyle(isUsingMax ? .secondary : .primary)
                    .opacity(isFindingMax ? 0 : 1)
                    .id(isUsingMax)
                    .transition(.opacity)
                if isFindingMax {
                    ProgressView().controlSize(.small)
                        .transition(.opacity)
                }
            }
            // Opacity alone, so Reduce Motion keeps the same fade.
            .animation(.easeInOut(duration: 0.2), value: isUsingMax)
            .animation(.easeInOut(duration: 0.2), value: isFindingMax)
            // At the trailing edge, or under the balance when stacked.
            .frame(minWidth: 44, minHeight: 44, alignment: alignment)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isFindingMax || isUsingMax)
        .accessibilityLabel(spokenLabel)
        // Voice Control matches what is on screen, so the visible word comes
        // first (copy-guidance.md, "Quote the label verbatim").
        .accessibilityInputLabels([Text(label), Text(spokenLabel)])
        .accessibilityHint(isUsingMax ? "" : "Fills the largest amount this mint can transfer after fees")
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

    /// The balance, then the problem in red words with no glyph, as the note
    /// under the amount is written.
    private func problemLine(_ balance: String, _ problem: String) -> Text {
        let words = Text(problem).foregroundStyle(ErrorSeverity.caution.noteForeground)
        return Text("\(balance) · \(words)")
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
                swapCount += 1
                onSwap?()
            } label: {
                // The captions carry the direction, so the glyph is symmetric
                // and a half turn lands it on itself: it reads as the flip
                // the tap makes. The glass stays still; only the arrows turn.
                Image(systemName: "arrow.up.arrow.down")
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(.primary)
                    .rotationEffect(.degrees(reduceMotion ? 0 : Double(swapCount) * 180))
                    .animation(.smooth(duration: 0.4), value: swapCount)
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
