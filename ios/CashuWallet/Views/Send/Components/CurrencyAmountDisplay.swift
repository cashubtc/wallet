import SwiftUI

/// Family-style two-line amount display.
///
/// Renders the active amount in either fiat or sats as the primary (large) line,
/// with the alternate unit underneath. Tapping either line flips which side is
/// primary and persists the choice. A note about the amount being entered
/// takes the alternate unit's line while it shows.
struct CurrencyAmountDisplay: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    let sats: UInt64
    @Binding var primary: AmountDisplayPrimary
    /// The rung of the amount ladder this instance sits on. A role, never a
    /// point size: six sizes serving this one job is how the same number ended
    /// up rendering in two typefaces on adjacent screens.
    var role: CashuTextRole = .amountHero
    /// Live-entry mode: the raw typed string. When set, the primary line renders
    /// the typed value verbatim (partial decimals included) instead of deriving
    /// from `sats`; the secondary line still shows `sats` converted. Display-only
    /// call sites omit this and are unchanged.
    var entryRaw: String? = nil
    /// Dims the primary amount (e.g. while the typed value exceeds spendable balance).
    var isDimmed: Bool = false
    /// A note about the amount, said in the conversion line's place: the line
    /// is there already, so the note costs no room.
    var note: AmountNote? = nil
    /// Holds the conversion line's height when there is no price to show, so
    /// a note appearing never moves the amount. For screens that pass `note`.
    var holdsNoteLine: Bool = false
    /// Announce the note to VoiceOver as it changes. A screen that offers
    /// several of these to a `ViewThatFits` announces once itself instead: the
    /// candidates it is not showing still see the note change, so each would
    /// announce it.
    var announcesNote: Bool = true

    @ObservedObject private var priceService = PriceService.shared
    @ObservedObject private var settings = SettingsManager.shared

    /// Display-mode fiat string; nil when no price is loaded or the amount
    /// converts to under one cent (sub-cent fiat is never displayed).
    private var displayFiat: String? {
        priceService.formatSatsAsFiat(sats)
    }

    private var fiatAvailable: Bool {
        // Entry mode only needs a live price — the primary unit and flip control
        // must stay put while the user types through small values. Display
        // mode also drops fiat for sub-cent amounts.
        if entryRaw != nil { return priceService.btcPriceUSD > 0 }
        return displayFiat != nil
    }

    private var effectivePrimary: AmountDisplayPrimary {
        // If user picked fiat but price isn't loaded yet, fall back to sats so we
        // never show "$0.00" as the headline number.
        if primary == .fiat && !fiatAvailable { return .sats }
        return primary
    }

    private var primaryParts: AmountParts {
        if let entryRaw {
            return AmountFormatter.entryPrimaryParts(
                raw: entryRaw,
                unit: effectivePrimary,
                useBitcoinSymbol: settings.useBitcoinSymbol
            )
        }
        let satsParts = AmountFormatter.satsParts(sats, useBitcoinSymbol: settings.useBitcoinSymbol)
        switch effectivePrimary {
        case .fiat: return displayFiat.map(AmountParts.parse) ?? satsParts
        case .sats: return satsParts
        }
    }

    private var primaryText: String { primaryParts.joined }

    /// Matches Home's transition driver: the animated value follows whichever
    /// unit is currently primary rather than staying fixed in sats.
    private var primaryNumericValue: Double {
        switch effectivePrimary {
        case .fiat: priceService.satsToFiat(sats)
        case .sats: Double(sats)
        }
    }

    private var secondaryText: String {
        switch effectivePrimary {
        case .fiat: return AmountFormatter.sats(sats, useBitcoinSymbol: settings.useBitcoinSymbol)
        case .sats:
            // Entry mode keeps the raw conversion (even "$0.00") so the control
            // doesn't blink in and out while typing through small values.
            return displayFiat ?? AmountFormatter.fiat(priceService.satsToFiat(sats), currencyCode: priceService.currencyCode)
        }
    }

    var body: some View {
        VStack(spacing: AmountPairMetrics.spacing) {
            Group {
                if fiatAvailable {
                    Button(action: flip) {
                        amountPair
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel("Amount: \(primaryText)")
                    .accessibilityHint("Double tap to make \(secondaryText) primary")
                    .sensoryFeedback(.selection, trigger: primary)
                } else {
                    amountPair
                }
            }

            // Outside the toggle, so VoiceOver reads it as its own line. The
            // two lines trade places without overlapping, so the amount holds
            // still.
            if let note {
                AmountNoteLine(note: note, announces: announcesNote)
                    .transition(.asymmetric(insertion: .opacity, removal: .identity))
            } else if holdsNoteLine && !fiatAvailable {
                AmountNoteLine.placeholder
            }
        }
    }

    private var amountPair: some View {
        VStack(spacing: AmountPairMetrics.spacing) {
            AmountLockup(
                parts: primaryParts,
                role: role,
                value: primaryNumericValue,
                isDimmed: isDimmed,
                isEntry: entryRaw != nil
            )
            .animation(reduceMotion ? .easeOut(duration: 0.2) : .snappy, value: isDimmed)

            // The whole pair is the toggle, matching Home. Keeping the support
            // line visually plain avoids implying that only the smaller value
            // can be tapped.
            if fiatAvailable && note == nil {
                Text(secondaryText)
                    .cashuText(.bodyEmphasis)
                    .monospacedDigit()
                    .contentTransition(.numericText(value: Double(sats)))
                    .foregroundStyle(.secondary)
                    .transition(.asymmetric(insertion: .opacity, removal: .identity))
            }
        }
        .frame(minHeight: AmountPairMetrics.minimumTapTarget)
    }

    private func flip() {
        guard fiatAvailable else { return }
        withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .snappy) {
            primary.toggle()
        }
    }
}

/// A note about an amount being entered: why it can't go as typed, or what was
/// done to it.
struct AmountNote: Equatable {
    let text: String
    var severity: ErrorSeverity = .error
    /// What VoiceOver reads in place of `text`, when the line is a shortened
    /// form of a longer message (a wallet error's {what broke} sentence).
    var spoken: String? = nil
}

/// A note in the conversion line's place under an amount (Family's pattern):
/// plain words, red for a problem and secondary otherwise, with no glyph. It
/// wraps rather than truncating; the line is the conversion's, so the amount
/// above it does not move.
struct AmountNoteLine: View {
    let note: AmountNote
    var announces = true

    var body: some View {
        Text(note.text)
            .cashuText(.body)
            .foregroundStyle(note.severity.noteForeground)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
            .padding(.horizontal)
            .accessibilityLabel(note.spoken ?? note.text)
            .onChange(of: note, initial: true) { _, note in
                guard announces else { return }
                Self.announce(note)
            }
    }

    static func announce(_ note: AmountNote) {
        // The colour is lost to VoiceOver, so the announcement names the
        // tier.
        guard note.severity != .info else { return }
        AccessibilityNotification.Announcement(
            note.severity.announcementPrefix + (note.spoken ?? note.text)
        ).post()
    }

    /// The line's height with nothing in it.
    static var placeholder: some View {
        Text(" ")
            .cashuText(.body)
            .hidden()
            .accessibilityHidden(true)
    }
}
