# 018 — Tie iOS confirmation timers to the state they clear

- **Status**: DONE (backup label only; scanner half withdrawn)
- **Commit**: e61dcb3c (`main`; the audit ran on a branch at 18ba3c89, one PR behind)
- **Severity**: LOW
- **Category**: Interruptibility
- **Estimated scope**: 2 files, about 30 lines

## Problem

Three short-lived confirmations are cleared by a fire-and-forget
`DispatchQueue.main.asyncAfter`. Nothing cancels the timer, so it still fires
after the state it belongs to has been replaced or the screen has gone.

```swift
// ios/CashuWallet/Views/Settings/SettingsView.swift:1434-1444 — current
private func backUpNow() {
    let outcome = walletManager.performICloudBackup()
    if let message = backupErrorMessage(for: outcome) {
        backupError = message
        return
    }
    didBackUp = true
    DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
        didBackUp = false
    }
}
```

On screen: tap "Back Up Now", the button reads "Backed up" for 3 s. Tap it
again after 2 s and the new confirmation disappears 1 s later, because the
first timer clears it. The label also swaps in a single frame in both
directions, with no fade:

```swift
// ios/CashuWallet/Views/Settings/SettingsView.swift:1384-1391 — current
Button(action: backUpNow) {
    if didBackUp {
        Label("Backed up", systemImage: "checkmark")
            .foregroundStyle(.green)
    } else {
        Text("Back Up Now")
    }
}
```

```swift
// ios/CashuWallet/Views/Components/ScannerWrapperView.swift:394-409 — current
case .reject(let message):
    scannerModel.errorMessage = message
    HapticFeedback.notification(.error)
    DispatchQueue.main.asyncAfter(deadline: .now() + 2) {
        scannerModel.reset()
    }
case .notice(let message):
    let generator = UINotificationFeedbackGenerator()
    generator.notificationOccurred(.success)
    // A confirmation, not a failure — render it on a neutral toast.
    scannerModel.noticeSeverity = .info
    scannerModel.errorMessage = message
    DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) {
        self.dismiss()
    }
```

On screen: scan a code the wallet rejects, then a second one within 2 s. The
first timer resets the scanner in the middle of the second message, so the
second message is cut short. If the scanner is closed inside the window the
timer fires against a screen that is gone.

No Android twin of these three timers was found
(`ui/settings/BackupScreen.kt`, `Views/Components/ScannerView.kt`), so this is
iOS-only work; say so in the pull request per the repo's parity rule.

## Target

The pattern the confirmation toast already uses: one stored task, cancelled
before a new one starts, checked for cancellation before it acts.

```swift
// ios/CashuWallet/Views/Components/ErrorBannerView.swift:508-527 — the exemplar, already in the repo
func show(_ message: String) {
    ensureOverlayWindow()
    dismissTask?.cancel()
    ...
    dismissTask = Task { [weak self] in
        try? await Task.sleep(for: .seconds(2.2))
        guard !Task.isCancelled, let self else { return }
        withAnimation(UIAccessibility.isReduceMotionEnabled ? nil : .easeOut(duration: 0.14)) {
            self.toast = nil
        }
    }
}
```

Settings:

```swift
// target — SettingsView.swift, beside `@State private var didBackUp = false`
@State private var backedUpResetTask: Task<Void, Never>?

// target — backUpNow()
withAnimation(.easeInOut(duration: 0.2)) { didBackUp = true }
backedUpResetTask?.cancel()
backedUpResetTask = Task { @MainActor in
    try? await Task.sleep(for: .seconds(3))
    guard !Task.isCancelled else { return }
    withAnimation(.easeInOut(duration: 0.2)) { didBackUp = false }
}

// target — the button label: each branch fades instead of cutting
if didBackUp {
    Label("Backed up", systemImage: "checkmark")
        .foregroundStyle(.green)
        .transition(.opacity)
} else {
    Text("Back Up Now")
        .transition(.opacity)
}
```

Scanner: a `@State private var feedbackTask: Task<Void, Never>?` on the view
that owns `processCompleteContent`, cancelled at the top of that function and
in `.onDisappear`; both `asyncAfter` bodies move into it behind
`guard !Task.isCancelled`, keeping their 2 s and 1.5 s waits.

## Repo conventions to follow

- A cross-fade between two faces of one control: each branch carries
  `.transition(.opacity)` and the change is wrapped in an animation
  (`docs/product/DESIGN.md` §6, animation 5). `.easeInOut(duration: 0.2)` is the
  documented cross-fade value; an opacity-only change needs no Reduce Motion
  branch.
- Cancellable timers: `ios/CashuWallet/Views/Components/ErrorBannerView.swift:508-527`.
- If plan 016 has landed, write `CashuMotion.crossFade` instead of the literal.

## Steps

1. `SettingsView.swift`: add `backedUpResetTask`, rewrite `backUpNow()` as above, add `.transition(.opacity)` to both label branches.
2. `ScannerWrapperView.swift`: add `feedbackTask`, cancel it at the start of `processCompleteContent` and in `.onDisappear`, and move the two delayed closures into it.
3. Build and run the checks below.

## Boundaries

- Do NOT change the hold times (3 s, 2 s, 1.5 s), the copy, the haptics or the green tint.
- Do NOT touch the second and third scanner types further down `ScannerWrapperView.swift` (the ones with their own `@State private var errorMessage`); they use `Task.sleep` already.
- Do NOT add new dependencies.
- If a step doesn't match the code you find, STOP and report instead of improvising.

## Verification

- **Mechanical**: `xcodebuild -project ios/CashuWallet.xcodeproj -scheme CashuWallet -destination 'generic/platform=iOS Simulator' -derivedDataPath /tmp/cashu-dd build` succeeds.
- **Feel check**:
  - Settings > Backup: tap "Back Up Now". The label fades to "Backed up" and fades back after 3 s. Tap again 2 s after the first tap: the confirmation now stays a full 3 s from the second tap.
  - Scanner: show it a QR code the wallet rejects, then another within 2 s. The second message stays its full 2 s.
  - Scanner: trigger a rejection and close the scanner within 1 s; reopen it. It opens clean, with no message and scanning active.
  - Reduce Motion on: the label still fades (opacity only).
- **Done when**: no `asyncAfter` remains in the two functions, and the three checks above pass.

## Outcome (2026-11-26, branch `polish/motion-audit`)

The backup confirmation is fixed as planned: one cancellable task, cancelled on
disappear, and the label fades.

The scanner half was **withdrawn, because its premise was wrong**. The scanner
stops scanning while a message is on screen (`processCompleteContent` sets
`scannerModel.isScanning = false`, and `handleScan` returns early until `reset()`
runs), so a second code cannot be read inside the two-second window and the
overlap described under Problem cannot happen. The two `asyncAfter` calls there
are left as they are.

The label fade has not been looked at on a device.
