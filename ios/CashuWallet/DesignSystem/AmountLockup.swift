import SwiftUI

/// The gap between the numerals and a unit word, as a fraction of the value's
/// size. Carried by tracking on a near-zero-size carrier rather than by a space
/// character, so the gap is an exact measurement instead of whatever advance the
/// face happens to give a space.
private let unitGapEm: CGFloat = 0.15

/// A currency *symbol* is not a unit *word* and does not want the same handling.
/// `sat` is a label and recedes; the `$` in `$18.42` is part of reading the
/// number, and a greyed, half-size `$` reads as a rendering defect. So a symbol
/// keeps full ink and weight, drops only slightly in size, and tucks tight.
private let symbolScale: CGFloat = 0.85

/// Shared vertical rhythm for a primary amount and its supporting conversion.
/// Interactive secondary values keep a full tap target below the visible line,
/// so the optical gap remains identical to a static receipt amount pair.
enum AmountPairMetrics {
    static let spacing: CGFloat = 4
    static let minimumTapTarget: CGFloat = 44
}

/// The one hero numeral.
///
/// Owns the value/unit lockup, tabular figures, the digit transition, the line
/// box and the autoscale floor, so no caller can get any of them individually
/// wrong — which is how the balance and the amount being typed one screen later
/// ended up in two different typefaces at two different weights.
///
/// **One `Text`, several runs, deliberately.** An `HStack` of two `Text`s would
/// let `minimumScaleFactor` shrink the value and the unit independently, so the
/// size relationship between them would drift exactly when it matters most — on
/// the long amounts that trigger scaling. Runs concatenated into one `Text`
/// scale together. An amount being typed is the one exception, and only while
/// it fits at full size; see `typed`.
struct AmountLockup: View {
    let parts: AmountParts
    var role: CashuTextRole = .amountHero
    /// Drives the digit transition. Pass the underlying numeric value.
    var value: Double?
    var isDimmed: Bool = false
    var accessibilityPrefix: String?
    /// The amount is being typed on a keypad.
    var isEntry: Bool = false

    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.cashuFonts) private var fonts
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        lockup
            .foregroundStyle(isDimmed ? .secondary : .primary)
            // Reserve exactly one line box at the current text size: constant
            // for a given size, so a unit swap never reflows; growing with it,
            // so large-text users are not cropped.
            .frame(maxWidth: .infinity, minHeight: role.lineHeight(at: typeSize, fonts: fonts))
            .accessibilityLabel(
                accessibilityPrefix.map { "\($0): \(parts.spoken)" } ?? parts.spoken
            )
    }

    @ViewBuilder
    private var lockup: some View {
        if isEntry {
            // Past full size the one Text takes over, so a long amount still
            // scales its value and unit together.
            ViewThatFits(in: .horizontal) {
                typed
                composed.cashuAmount(role, value: value)
            }
        } else {
            composed.cashuAmount(role, value: value)
        }
    }

    /// The value and its unit as runs of a single `Text`.
    ///
    /// A unit word is demoted on three independent axes — size, weight and ink —
    /// so the digits are unmistakably the subject. At parity the unit occupies
    /// roughly a third of the lockup while carrying none of the information.
    ///
    /// Center the unit beside the numeral's visible height. Keeping the offset
    /// inside the same Text makes the alignment scale with long amounts.
    private var composed: Text {
        switch parts.affix {
        case .none:
            return Text(parts.value)
        case .suffix(let word):
            return Text(parts.value) + gap + unitRun(word)
        case .prefix(let symbol):
            return symbolRun(symbol) + Text(parts.value)
        }
    }

    /// An amount being typed, one view per glyph.
    ///
    /// `.numericText` diffs the old string against the new one from both ends,
    /// so once a grouping separator appears or moves, it rolls the digits in
    /// between: typing the last 0 of 2,100 rolled a 0 in after the 1 while the
    /// trailing 0 held still. Here each glyph keeps its place in the typing
    /// (`AmountGlyph`), so the digit just typed is the one arriving at the end,
    /// the rest slide aside to make room, and a separator that moves goes out
    /// of focus where it was and comes into focus where it lands.
    private var typed: some View {
        HStack(alignment: .firstTextBaseline, spacing: 0) {
            if case .prefix(let symbol) = parts.affix {
                symbolRun(symbol)
            }
            ForEach(AmountGlyph.glyphs(of: parts.value)) { glyph in
                Text(String(glyph.id.character))
                    .transition(reduceMotion ? .opacity : AnyTransition(.blurReplace))
            }
            if case .suffix(let word) = parts.affix {
                gap + unitRun(word)
            }
        }
        // The glyphs carry their own transition, so no value to roll.
        .cashuAmount(role, value: nil)
        .fixedSize()
        // Centred inside the animation, so the lockup re-centres in step with
        // the glyphs sliding within it.
        .frame(maxWidth: .infinity)
        .animation(reduceMotion ? .easeOut(duration: 0.2) : .snappy, value: parts)
        .accessibilityElement(children: .combine)
    }

    private var pointSize: CGFloat { role.pointSize(at: typeSize) }

    private func unitRun(_ word: String) -> Text {
        let unitSize = pointSize * CashuTextRole.unitScale
        return Text(word)
            .font(fonts.font(.sans, size: unitSize, weight: role.weight.oneStepDown))
            .foregroundStyle(.secondary)
            .baselineOffset((
                fonts.capHeight(.sans, size: pointSize, weight: role.weight)
                - fonts.capHeight(.sans, size: unitSize, weight: role.weight.oneStepDown)
            ) / 2)
    }

    /// A 1pt carrier whose tracking supplies the whole optical gap.
    private var gap: Text {
        Text(" ")
            .font(.system(size: 1))
            .tracking(pointSize * unitGapEm)
    }

    private func symbolRun(_ symbol: String) -> Text {
        // The Bitcoin sign is the unit's primary mark, not a subordinate
        // currency prefix. At the symbol scale it reads visibly smaller
        // than the numeral lockup, so retain the full point size for ₿.
        let symbolSize = symbol == "₿" ? pointSize : pointSize * symbolScale
        return Text(symbol).font(
            fonts.font(.sans, size: symbolSize, weight: role.weight)
        )
    }
}

/// One glyph of an amount being typed, named by its place in the typing rather
/// than on screen: how many digits come before it, and what it is. The nth digit
/// typed keeps its name however the grouping around it changes; a separator is
/// named by the digits before it, so one that moves is a new separator.
struct AmountGlyph: Identifiable, Equatable {
    struct ID: Hashable {
        let digitsBefore: Int
        let character: Character
    }

    let id: ID

    static func glyphs(of value: String) -> [AmountGlyph] {
        var digits = 0
        return value.map { character in
            defer { if character.isWholeNumber { digits += 1 } }
            return AmountGlyph(id: ID(digitsBefore: digits, character: character))
        }
    }
}

extension CashuTextRole {
    /// The unit's size as a fraction of the value's.
    ///
    /// 0.5 is two rungs of the ladder, and combines with a weight step and a
    /// drop to secondary ink for three independent axes of subordination.
    static let unitScale: CGFloat = 0.5
}

extension Font.Weight {
    /// One step down the weight ramp, for the subordinated unit.
    var oneStepDown: Font.Weight {
        switch self {
        case .black: .heavy
        case .heavy: .bold
        case .bold: .semibold
        case .semibold: .medium
        case .medium: .regular
        default: .regular
        }
    }
}

/// The one section overline.
///
/// Replaces six hand-rolled copies, five of which froze tracking at 1.2pt —
/// 0.1em at 12pt, nearly double the documented 0.06em, and correct at exactly
/// one text size because a point value cannot scale. Casing lives in the role,
/// so no call site has to remember it.
struct SectionHeader: View {
    let title: String

    var body: some View {
        Text(title)
            .cashuText(.overline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}
