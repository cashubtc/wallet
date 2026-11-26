import SwiftUI

/// The app's motion values in one place. The rules behind them live in
/// docs/product/DESIGN.md §6; Android's twin is `ui/theme/Motion.kt`.
///
/// A name here is a role, not a number: reach for the role that matches what
/// is happening on screen, and retune the role rather than a call site. Values
/// used in a single place stay where they are used.
enum CashuMotion {
    // MARK: Press (DESIGN.md §6, animation 4)

    /// Fast on touch-down, slower on release.
    static func press(isPressed: Bool) -> Animation {
        .snappy(duration: isPressed ? 0.09 : 0.18)
    }

    // MARK: State changes

    /// A control or line changing state in place: a flip, a toggle, a hint
    /// arriving under a field. The default for anything tapped often.
    static let flip: Animation = .snappy(duration: 0.25)

    /// A block arriving or leaving at an unhurried half second: an error line,
    /// the error banner, a restore row changing phase. Prefer `flip` for
    /// anything the user triggers repeatedly.
    static let settle: Animation = .snappy

    // MARK: Entrances and reflows

    /// A face, row or block arriving, or content reflowing around it.
    static let reflow: Animation = .smooth(duration: 0.3)

    // MARK: Cross-fades

    /// Two faces of one thing trading places by opacity.
    static let crossFade: Animation = .easeInOut(duration: 0.2)

    /// An onboarding or restore step giving way to the next.
    static let stepSwap: Animation = .easeInOut(duration: 0.28)

    /// What a moving change collapses to under Reduce Motion.
    static let reducedFade: Animation = .easeOut(duration: 0.2)

    // MARK: Numbers

    /// A money value rolling to a new value. Half a second, because the roll is
    /// the signal that money moved (DESIGN.md, "Received delta beat").
    static let valueRoll: Animation = .snappy

    // MARK: One of a kind

    /// The single bounce in the app: a payment landing.
    static let celebration: Animation = .spring(response: 0.5, dampingFraction: 0.7)

    /// The confirmation toast sliding in, and fading out.
    static let toastEnter: Animation = .spring(response: 0.38, dampingFraction: 0.9)
    static let toastExit: Animation = .easeOut(duration: 0.14)
}
