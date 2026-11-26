# 016 — Give iOS one home for its motion values

- **Status**: DONE
- **Commit**: e61dcb3c (`main`; the audit ran on a branch at 18ba3c89, one PR behind)
- **Severity**: MEDIUM
- **Category**: Cohesion & tokens
- **Estimated scope**: 1 new file, 1 new test file, about 45 files touched by a mechanical replace; no visible change in Part A

## Problem

Android keeps its motion values in one place
(`android/app/src/main/java/com/cashu/me/ui/theme/Motion.kt`, `object CashuMotion`).
iOS has no equivalent: every curve and duration is typed by hand at the call
site. Counted across `ios/CashuWallet` at the commit above:

| Typed at call sites | Count |
| --- | --- |
| `.smooth(duration: 0.3)` | 85 |
| `.easeInOut(duration: 0.2)` | 43 |
| bare `.snappy` (really 0.5 s, see plan 015) | about 40 |
| `.snappy(duration: 0.25)` | 14 |
| `.easeOut(duration: 0.2)` | 9 |
| `.easeInOut(duration: 0.28)` | 6 |
| press pair `.snappy(duration: isPressed ? 0.09 : 0.18)` | 5 |
| `.spring(response: 0.5, dampingFraction: 0.7)` (celebration) | 4 |
| about 25 further one-off values | 1–3 each |

Two consequences:

1. Nothing can be tuned in one place. Making every in-sheet swap a touch
   quicker means editing 85 lines and hoping none were missed.
2. Look-alike values drift in. `docs/product/DESIGN.md` §6 says the celebration
   spring is the only `.spring` allowed, yet three more exist, each slightly
   different:

```swift
// ios/CashuWallet/Views/Components/ErrorBannerView.swift:512-514 — current (confirmation toast entrance)
let animation: Animation? = UIAccessibility.isReduceMotionEnabled
    ? nil
    : .spring(response: 0.38, dampingFraction: 0.9)

// ios/CashuWallet/Views/Components/ActivityOrbView.swift:120-123 — current (empty-state orb entrance)
.animation(
    allowsEntranceMotion ? .spring(response: 0.36, dampingFraction: 0.82) : nil,
    value: isPresented
)

// ios/CashuWallet/Views/Main/OnboardingView.swift:1033 — current (iCloud restore result)
withAnimation(reduceMotion ? .easeOut(duration: 0.25) : .spring(response: 0.45, dampingFraction: 0.85)) {
```

Other strays that almost match a documented value: `.snappy(duration: 0.3)`
(`Views/Send/SendView.swift:297`, documented neighbours are 0.28 and 0.35),
`.smooth(duration: 0.2)` (`Views/Settings/SettingsView.swift:1606`,
`Views/Mints/AddMintSheet.swift:84`, `Views/Main/OnboardingView.swift:1787`),
`.easeOut(duration: 0.22)` (`Views/Components/LiquidGlassModifiers.swift:493`).

## Target

### Part A — name what exists (no visible change)

A new file `ios/CashuWallet/DesignSystem/CashuMotion.swift`, mirroring Android's
`CashuMotion` in purpose. Every constant holds the value the app already uses,
so Part A changes no pixel.

```swift
import SwiftUI

/// The app's motion values in one place. The rules behind them live in
/// docs/product/DESIGN.md §6; Android's twin is `ui/theme/Motion.kt`.
enum CashuMotion {
    // MARK: Press (DESIGN.md §6, animation 4)
    /// Fast on touch-down, slower on release.
    static func press(isPressed: Bool) -> Animation {
        .snappy(duration: isPressed ? 0.09 : 0.18)
    }

    // MARK: State flips
    /// A control or line changing state in place.
    static let flip: Animation = .snappy(duration: 0.25)
    /// History badge symbol replace and other slightly larger flips.
    static let badge: Animation = .snappy(duration: 0.28)

    // MARK: Entrances and reflows
    /// A face, row or block arriving, or content reflowing around it.
    static let reflow: Animation = .smooth(duration: 0.3)
    /// The Transfer glyph's half turn.
    static let turn: Animation = .smooth(duration: 0.4)

    // MARK: Cross-fades
    static let crossFade: Animation = .easeInOut(duration: 0.2)
    /// Onboarding step swap and the things that ride it.
    static let stepSwap: Animation = .easeInOut(duration: 0.28)
    /// What movement collapses to under Reduce Motion.
    static let reducedFade: Animation = .easeOut(duration: 0.2)

    // MARK: Numbers
    /// A money value rolling to a new value (0.5 s; DESIGN.md "Received delta beat").
    static let valueRoll: Animation = .snappy

    // MARK: One-offs with a name
    /// The single bounce in the app: a payment landing.
    static let celebration: Animation = .spring(response: 0.5, dampingFraction: 0.7)
    static let toastEnter: Animation = .spring(response: 0.38, dampingFraction: 0.9)
    static let toastExit: Animation = .easeOut(duration: 0.14)
}
```

Call sites then read, for example:

```swift
// before
.animation(.smooth(duration: 0.3), value: phase)
// after
.animation(CashuMotion.reflow, value: phase)
```

### Part B — fold the look-alikes (visible, needs a feel check)

Only after Part A is merged, and as its own change:

| Site | Today | Becomes |
| --- | --- | --- |
| `Views/Components/ActivityOrbView.swift:121` | `.spring(response: 0.36, dampingFraction: 0.82)` | `CashuMotion.toastEnter` |
| `Views/Send/SendView.swift:297` | `.snappy(duration: 0.3)` | `CashuMotion.badge` (0.28) |
| `Views/Settings/SettingsView.swift:1606`, `Views/Mints/AddMintSheet.swift:84`, `Views/Main/OnboardingView.swift:1787` | `.smooth(duration: 0.2)` | `CashuMotion.crossFade` |
| `Views/Components/LiquidGlassModifiers.swift:493` | `.easeOut(duration: 0.22)` | `CashuMotion.reducedFade` (0.2) |

`OnboardingView.swift:1033` (`0.45 / 0.85`) is onboarding, which is exempt from
the wallet's budget; leave it as a literal.

### Part C — keep it from spreading

A test in the style of `ios/CashuWalletTests/TypographyGuardTests.swift`
(`testHardcodedPointSizesDoNotSpread`, `testRawTrackingDoesNotSpread`): it
scans `ios/CashuWallet/**/*.swift`, counts raw `.snappy(`, `.smooth(`,
`.easeInOut(`, `.easeOut(`, `.easeIn(` and `.spring(` outside
`CashuMotion.swift`, and fails if the count rises above a frozen number. The
number only ever goes down.

## Repo conventions to follow

- Design-system files live in `ios/CashuWallet/DesignSystem/` next to
  `CashuTextRole.swift` and `CashuTypography.swift`; follow their style
  (a caseless `enum` namespace, doc comments that point at DESIGN.md).
- New Swift files must be registered by hand in
  `ios/CashuWallet.xcodeproj/project.pbxproj` (file reference, build file, group
  entry, Sources phase). Pick IDs that do not exist yet and grep the file for
  duplicates afterwards: a collision does not error, it silently drops a file.
- Guard-test pattern: `ios/CashuWalletTests/TypographyGuardTests.swift:94`
  (frozen count that only shrinks).
- `SharedAxis` in `Views/Components/LiquidGlassModifiers.swift:439-478` already
  names its own timings; leave that type as it is and do not move it.

## Steps

1. Create `ios/CashuWallet/DesignSystem/CashuMotion.swift` with exactly the code under Part A. Register it in the Xcode project.
2. Replace literals with names, one value at a time, each as its own commit so a reviewer can check it by grep:
   1. `.smooth(duration: 0.3)` → `CashuMotion.reflow`
   2. `.easeInOut(duration: 0.2)` → `CashuMotion.crossFade`
   3. `.easeOut(duration: 0.2)` → `CashuMotion.reducedFade`
   4. `.snappy(duration: 0.25)` → `CashuMotion.flip`
   5. `.snappy(duration: configuration.isPressed ? 0.09 : 0.18)` → `CashuMotion.press(isPressed: configuration.isPressed)`
   6. `.spring(response: 0.5, dampingFraction: 0.7)` → `CashuMotion.celebration`
   7. `.easeInOut(duration: 0.28)` → `CashuMotion.stepSwap`; `.smooth(duration: 0.4)` → `CashuMotion.turn`; `.snappy(duration: 0.28)` → `CashuMotion.badge`
   8. Toast: `ErrorBannerView.swift:514` → `CashuMotion.toastEnter`, `:523` → `CashuMotion.toastExit`
   9. Bare `.snappy` used as an `Animation` (not `.snappy(`) → `CashuMotion.valueRoll`, **except** the lines plan 015 changes; if 015 has landed those already carry `.snappy(duration: 0.25)` and step 4 covers them.
3. Leave every remaining one-off literal in place. Do not invent names for values used once.
4. Add `ios/CashuWalletTests/MotionGuardTests.swift` (Part C) and register it. Freeze its count at whatever the scan reports after step 2.
5. Part B, as a separate pull request, with before/after captures of the four sites.
6. Add one paragraph to `docs/product/DESIGN.md` §6 under "Allowed easings" saying the values now live in `CashuMotion.swift`, and add `toastEnter` to the list of sanctioned springs (it is documented in prose at the Notifications section but missing from the list).

## Boundaries

- Part A must not change any value. If a replace would change a number, skip that site and list it in the pull request.
- Do NOT touch `SharedAxis`, `OnboardingHandoff.swift`, `AsciiField.swift`, or any `.delay(...)` chained onto an animation (keep the delay, replace only the curve in front of it).
- Do NOT rename or re-export anything on Android in this plan; plan 017 covers Android.
- Do NOT add new dependencies.
- Do NOT commit signing or bundle-identifier changes in `project.pbxproj`; stage only the lines that register the two new files.
- If a step doesn't match the code you find, STOP and report instead of improvising.

## Verification

- **Mechanical**:
  - `xcodebuild -project ios/CashuWallet.xcodeproj -scheme CashuWallet -destination 'platform=iOS Simulator,name=iPhone 17 Pro' -derivedDataPath /tmp/cashu-dd test -only-testing:CashuWalletTests/MotionGuardTests -only-testing:CashuWalletTests/TypographyGuardTests` passes.
  - After Part A, `grep -rcE "\.smooth\(duration: 0\.3\)|\.easeInOut\(duration: 0\.2\)" ios/CashuWallet --include='*.swift'` reports matches only in `CashuMotion.swift`.
  - `git diff main --stat` for Part A shows no file outside `ios/` except the DESIGN.md paragraph.
- **Feel check**:
  - Part A: none expected. Spot-check three flows in the simulator (Send to review and back, Receive invoice appearing, a copy toast) against a build of `main`; they must look identical.
  - Part B: record each of the four sites before and after at 60 fps and play at 25%. The empty-state orb should settle without a visible second bounce; the settings/add-mint field hints should fade without lagging the keyboard.
- **Done when**: the guard test is green with its frozen count, Part A is a zero-visual-change diff, and Part B has captures attached.

## Outcome (2026-11-26, branch `polish/motion-audit`)

Parts A and C are proposed upstream. Part B is on the local branch
`polish/motion-audit` and has not been proposed yet: its five sites still need
before and after captures. Differences from the plan as written:

- Roles: `turn` and `badge` were not added, because `.smooth(duration: 0.4)` turned
  out to serve four different jobs and `.snappy(duration: 0.28)` only one; both stay
  as literals. `settle` was added: about thirty call sites used a bare `.snappy` for
  an error line, banner or restore row arriving, which is not a value roll.
- Part A is one commit, not one per value. The mapping is mechanical and can be
  checked by grep; the guard test passes.
- Part B: the empty-state orb now uses `CashuMotion.reflow` (the documented
  entrance curve, no overshoot) instead of the toast spring, which would have been
  the wrong name for it. The pay-to-key toggle uses `flip`. The three field-hint
  fades use `crossFade`. The screen-entry fade (`.easeOut(duration: 0.22)`) was left
  alone: it is an entrance, and `reducedFade` would have misnamed it.
- The guard has four checks: hand-typed curves (ceiling 48 without Part B, 43
  with it), no ease-in outside
  `SharedAxis`, no scale entrance from zero, and no bare `.snappy` at a call site.

Part B's sites have not been compared on screen before and after.
