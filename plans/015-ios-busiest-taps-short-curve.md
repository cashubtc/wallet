# 015 — Give the busiest iOS taps a short, explicit curve

- **Status**: DONE (typing speed still to be picked by eye)
- **Commit**: e61dcb3c (`main`; the audit ran on a branch at 18ba3c89, one PR behind)
- **Severity**: HIGH
- **Category**: Easing & duration, on high-frequency surfaces
- **Estimated scope**: 3 files, 5 one-line edits, plus a side-by-side capture for the pick

## Problem

The most repeated taps in the iOS app animate on the slowest curve in the app.

A bare `.snappy` in SwiftUI is `.snappy(duration: 0.5, extraBounce: 0)`: a
half-second spring that also carries the preset's small overshoot. The repo's
own motion spec lists the allowed `.snappy(duration:)` values as 0.09, 0.18,
0.25, 0.28 and 0.35 (`docs/product/DESIGN.md` §6, "Allowed easings"), so the
bare form is both slower than every documented value and outside the list.

It sits on exactly the surfaces that are hit most often:

```swift
// ios/CashuWallet/DesignSystem/AmountLockup.swift:127 — current
// Runs on every number-pad key press and on every sats ↔ fiat flip of an
// amount being typed: each digit comes into focus and its neighbours slide
// aside over a half-second spring.
.animation(reduceMotion ? .easeOut(duration: 0.2) : .snappy, value: parts)
```

```swift
// ios/CashuWallet/Views/Send/Components/CurrencyAmountDisplay.swift:136 — current
.animation(reduceMotion ? .easeOut(duration: 0.2) : .snappy, value: isDimmed)

// ios/CashuWallet/Views/Send/Components/CurrencyAmountDisplay.swift:153-158 — current
private func flip() {
    guard fiatAvailable else { return }
    withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .snappy) {
        primary.toggle()
    }
}
```

```swift
// ios/CashuWallet/Views/Main/MainWalletView.swift:389-394 — current
private func cycleHomeBalance(to next: HomeBalanceStep) {
    HapticFeedback.selection()
    withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .snappy) {
        settings.applyHomeBalanceStep(next)
    }
}

// ios/CashuWallet/Views/Main/MainWalletView.swift:411 — current (unit pager dots)
.animation(reduceMotion ? nil : .snappy, value: selected)
```

Why it matters: typing an amount is the highest-frequency action in a wallet.
Someone entering "21000" presses five keys in about a second; each press starts
a half-second settle, so the number is still moving for the whole entry and for
a beat after the last key. Android answers the same key press and the same
flip with a `spring(stiffness = Spring.StiffnessMedium)` fade
(`android/app/src/main/java/com/cashu/me/ui/components/Amount.kt:152-154`,
`AmountFlipDisplay.kt:189-191`), which is done in roughly 150 ms, so the iOS
app currently feels slower than its twin on the same gesture.

## Target

Same motion, same design, shorter. Every value below is already on the
documented list in `docs/product/DESIGN.md` §6, so no new curve is introduced.

```swift
// ios/CashuWallet/DesignSystem/AmountLockup.swift:127 — target
.animation(reduceMotion ? .easeOut(duration: 0.2) : .snappy(duration: 0.25), value: parts)
```

```swift
// ios/CashuWallet/Views/Send/Components/CurrencyAmountDisplay.swift:136 — target
.animation(reduceMotion ? .easeOut(duration: 0.2) : .snappy(duration: 0.25), value: isDimmed)

// ios/CashuWallet/Views/Send/Components/CurrencyAmountDisplay.swift:155 — target
withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .snappy(duration: 0.25)) {
```

```swift
// ios/CashuWallet/Views/Main/MainWalletView.swift:391 — target
withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .snappy(duration: 0.25)) {

// ios/CashuWallet/Views/Main/MainWalletView.swift:411 — target
.animation(reduceMotion ? nil : .snappy(duration: 0.25), value: selected)
```

`0.25` is the starting value, not the final word. The typed-amount line
(`AmountLockup.swift:127`) is the one place where the right number has to be
picked by eye: build it at **0.18** as well and capture both against today's
build (see Verification). The product owner picks from the side-by-side, the
way the Transfer glyph turn was picked.

## Repo conventions to follow

- Reduce Motion branch stays exactly as it is (`.easeOut(duration: 0.2)` or
  `nil`). Only the non-reduced branch changes.
- Explicit durations are written `.snappy(duration: 0.25)`, as in
  `ios/CashuWallet/Views/Receive/ReceiveView.swift:99`:
  `.animation(reduceMotion ? .easeInOut(duration: 0.2) : .snappy(duration: 0.25), value: inputHint != nil)`
- If plan 016 (`CashuMotion.swift`) has already landed, use its `CashuMotion.flip`
  name instead of the literal.

## Steps

1. `ios/CashuWallet/DesignSystem/AmountLockup.swift:127`: replace `.snappy` with `.snappy(duration: 0.25)`.
2. `ios/CashuWallet/Views/Send/Components/CurrencyAmountDisplay.swift:136` and `:155`: same replacement.
3. `ios/CashuWallet/Views/Main/MainWalletView.swift:391` and `:411`: same replacement.
4. Capture the side-by-side described under Verification and hand it over for the pick between 0.25 and 0.18 on step 1's line. Apply the picked value to that line only.

## Boundaries

- Do NOT touch `ios/CashuWallet/DesignSystem/CashuTextRole.swift:251`
  (`.animation(.snappy, value: value)`). That is the digit roll when a balance
  actually changes; `docs/product/DESIGN.md` ("Received delta beat") documents
  it as the main signal that money arrived, and it is an occasional event.
- Do NOT touch `MainWalletView.swift:335` or `:436` (the same value roll on the
  non-sat hero and on the fiat line).
- Do NOT change the `.blurReplace` transition, the glyph-by-glyph structure of
  `typed`, the haptics, or any layout. Duration only.
- Do NOT touch Android: its twins are already short.
- Do NOT commit `ios/CashuWallet.xcodeproj/project.pbxproj` changes caused by local signing.
- If a line does not match what is quoted here (drift since the commit stamp), STOP and report instead of improvising.

## Verification

- **Mechanical**: `xcodebuild -project ios/CashuWallet.xcodeproj -scheme CashuWallet -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/cashu-dd build` succeeds. Always pass `-derivedDataPath`; this machine has two DerivedData directories and a stale one gives false greens.
- **Feel check** (simulator recording at 60 fps, `xcrun simctl io booted recordVideo`, then step through frame by frame or play at 25%):
  - Open Send, type `21000` at a normal typing pace. Each digit should be readable and at rest before the next key lands; the number should be still within a quarter second of the last key. Today it is still drifting sideways.
  - Type the fifth digit so the thousands separator appears: the separator should arrive where it lands without the row visibly bouncing past its resting place.
  - Hold delete-tap five times quickly: digits leave without a queue of leftover motion.
  - Tap the amount to flip sats ↔ fiat three times fast: each tap retargets from where the number is; nothing restarts from scratch and nothing overshoots.
  - Home: tap the balance through bitcoin → fiat → hidden → bitcoin. The hidden face should come into focus and leave in about a quarter second.
  - Home with two units: swipe the unit pager; the dot stretches into a pill without a visible bounce.
  - Reduce Motion on (Settings > Accessibility > Motion): every one of the above is a plain 0.2 s fade, unchanged from today.
  - Produce one clip with three columns for the typed amount: today (0.5), 0.25, 0.18. That clip is the deliverable for the pick.
- **Done when**: the five lines carry an explicit duration, the build is green, and the typed-amount value has been picked from the side-by-side.

## Outcome (2026-11-26, branch `polish/motion-audit`)

All five lines carry `.snappy(duration: 0.25)`; after plan 016 they read
`CashuMotion.flip`. The app builds. Not done: the three-column clip (0.5, 0.25,
0.18) for the typed amount. Until that pick is made the typed amount runs at
0.25, the plan's starting value. To try 0.18, change the one line in
`AmountLockup.swift` that animates on `parts`.

### Measured on the simulator (2026-11-26, PR #483)

Recorded at 60 fps on the iOS 27.2 simulator, counting how long the screen keeps
changing after each tap:

| Interaction | Before | After (0.25 s) | Alternative (0.18 s) |
| --- | --- | --- | --- |
| Typing a digit | about 400 ms | about 200 ms | about 150 ms |
| Hiding or showing the balance | about 400 ms | about 220 ms | not tried |

The recording also showed that this plan was wrong about the home balance. The
hidden and visible balances swap inside the button's label, so the button's own
press animation was timing the swap, and changing the animation in
`cycleHomeBalance` made no difference to it: before and after were identical frame
for frame. Naming the animation on the swap itself (`.animation(…, value: hidden)`
on the `ZStack` inside the label) fixed it. That line is part of PR #483 and of
this branch. The animation in `cycleHomeBalance` still times the fiat line under
the balance.

Not captured: the bitcoin and fiat flip on amount screens, the unit dots, and the
backup label.
