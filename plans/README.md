# Motion plans

Plans 001–011 came from the onboarding audit of 2026-08-06 at commit
`f54a829c`. Plans 012–014 come from the native UI and motion sweep of
2026-08-31 at commit `fa754037`. Plans 015–019 come from the app-wide motion
audit of 2026-11-26, written against `main` at `e61dcb3c`. The app-wide motion
budget (`docs/product/DESIGN.md` §6, `docs/android/DESIGN-ANDROID.md`) and
platform-native behavior remain the governing constraints.

## Plans

| # | Plan | Platform | Severity | Status |
| --- | --- | --- | --- | --- |
| 001 | [Copy → Copied morph](001-android-copy-copied-morph.md) | Android | MEDIUM | DONE |
| 002 | [Headline rise on every step](002-android-headline-rise.md) | Android | MEDIUM | DONE |
| 003 | [70 ms element cascade](003-element-cascade-both-platforms.md) | Both | MEDIUM | PARTIAL |
| 004 | [Restore progress: row phases + rolling total](004-android-restore-progress-motion.md) | Android | MEDIUM | DONE (rolling total confirmed; row slot not re-read) |
| 005 | [Animated notices and status lines](005-android-animated-notices.md) | Android | MEDIUM | TODO |
| 006 | [Stage swap: exit blur + overlap](006-android-stage-swap-exit-blur-overlap.md) | Android | MEDIUM | PARTIAL (Part A in; Part B not checked) |
| 007 | [Chassis accessory fade](007-android-chassis-accessory-fade.md) | Android | LOW | DONE (built differently) |
| 008 | [App-gate reduce-motion branch](008-android-app-gate-reduce-motion.md) | Android | LOW | TODO, probably retire |
| 009 | [Seed-reveal Reduce Motion gate](009-ios-seed-reveal-reduce-motion.md) | iOS | LOW | DONE |
| 010 | [Seed reveal blur resolve](010-android-seed-reveal-blur.md) | Android | LOW | DONE |
| 011 | [Haptic parity](011-android-haptic-parity.md) | Android | LOW | PARTIAL (not checked site by site) |
| 012 | [Reduce Motion press feedback](012-reduced-motion-press-feedback.md) | Both | HIGH | DONE |
| 013 | [Quiet full-tab empty states](013-quiet-full-tab-empty-states.md) | Both | MEDIUM | DONE |
| 014 | [Payment terminal state coherence](014-payment-terminal-state-coherence.md) | Both | HIGH | DONE |
| 015 | [Give the busiest iOS taps a short, explicit curve](015-ios-busiest-taps-short-curve.md) | iOS | HIGH | DONE |
| 016 | [Give iOS one home for its motion values](016-ios-motion-tokens.md) | iOS | MEDIUM | DONE (Part B not yet proposed) |
| 017 | [Name Android's repeated fade specs in CashuMotion](017-android-name-repeated-specs.md) | Android | LOW | DONE |
| 018 | [Tie iOS confirmation timers to the state they clear](018-ios-confirmation-timers.md) | iOS | LOW | DONE (backup label only; scanner half withdrawn) |
| 019 | [Bring the motion docs back in line with the code](019-motion-docs-reconcile.md) | Docs | LOW | DONE |

### Status notes for 001–011 (reconciled 2026-11-26 against the branch at `18ba3c89`)

Statuses were checked by searching for each plan's target identifiers, not by a
line-by-line read of every step. Paths are under
`android/app/src/main/java/com/cashu/me/` unless they start with `ios/`.

- **001 DONE**: `ui/onboarding/OnboardingChassis.kt:347` passes `animatedLabel = true`; `ui/onboarding/OnboardingScreen.kt:1119` uses `IconSwap`.
- **002 DONE**: seven `riseIn(…, index = 0)` headers in `ui/onboarding/OnboardingScreen.kt` (`:700`, `:735`, `:800`, `:840`, `:953`, `:1181`, `:1476`).
- **003 PARTIAL**: only the restore-input stage cascades past the header, on both platforms (`OnboardingScreen.kt:762`, `ios/CashuWallet/Views/Main/OnboardingView.swift:1492`). First mint, restore mints and restore progress are still header-only on both.
- **004 DONE**: `ui/restore/RestoreProgressTransition.kt:38-50` (rolling total with a size transform). The per-row trailing slot was not re-read.
- **005 TODO**: onboarding and restore still place a bare `InlineNotice(` at nine sites (`OnboardingScreen.kt:825`, `:962`, `:973`, `:1588`; `ui/restore/RestoreWalletFlow.kt:235`, `:641`, `:1104`; `ui/restore/SeedWordEntry.kt:310-311`). `InlineNoticeHost` is used elsewhere in the app but not here. The plan's line numbers have drifted.
- **006 PARTIAL**: exit blur is in (`OnboardingScreen.kt:650`, `morphBlur`). The feel-check-gated entrance overlap (Part B) was not checked.
- **007 DONE**: `ui/onboarding/OnboardingChassis.kt:172-194` fades the accessory through an `AnimatedContent` with a snapshot of the outgoing accessory, instead of the plan's `AnimatedVisibility`.
- **008 TODO, probably retire**: `ui/shell/CashuApp.kt:285-291` has no reduced-motion branch. With system animations off, Compose finishes every animation at once, so the plan's gentler fade is unlikely ever to play. Confirm on a device with animations off, then mark it retired.
- **009 DONE**: `ios/CashuWallet/Views/Main/OnboardingView.swift:1212`.
- **010 DONE**: `ui/onboarding/OnboardingScreen.kt:1359-1361` (`seed-reveal-blur`).
- **011 PARTIAL**: haptics exist in `OnboardingScreen.kt` and `ui/restore/RestoreWalletFlow.kt`; the plan's table of moments was not checked site by site.

## Recommended execution order

Plans 015–019 were carried out on 2026-11-26 on the branch
`polish/motion-audit`; each plan ends with an Outcome section saying what
landed and where it differs from the plan. Still open, most valuable first:

1. **016 Part B**: capture its five sites before and after, then propose it.
2. **005**, then the rest of **003**: the remaining onboarding work on Android.
3. **008**: confirm on a device with animations off, then retire.

## Dependencies

- 005 and 011 both edit `RestoreWalletFlow.kt`; coordinate if run in parallel.
- 003's remaining stages depend on the `riseIn` wiring from 002 (done).

## Repo-wide execution notes

- Plans 015–019 quote code as it stands on `main` at `e61dcb3c`. Verify each excerpt before editing; on drift, stop and report rather than improvise (each plan repeats this).
- Android UI changes that shift Compose screenshot references: regenerate via the workflow in `.github/workflows/android-ui-tests.yml` (`:app:validateDebugScreenshotTest`) and commit the new baselines with the PR.
- UI PRs need matched before/after visual evidence per repo convention (see `.agents/skills/wallet-ui-visual-review`).
- Reduce-motion checks: iOS = Settings > Accessibility > Motion; Android = `adb shell settings put global animator_duration_scale 0` (restore `1`).
- iOS builds: always pass `-derivedDataPath` to `xcodebuild`, and never commit signing changes in `project.pbxproj`.
- A change requested for one platform needs an explicit decision about the other (`AGENTS.md`, cross-platform parity). 015 and 018 are iOS-only because Android already behaves the target way or has no twin; say so in the pull request.

## Audited and left alone (2026-11-26)

Recorded so the next audit does not re-open them.

- **Ease-in curves** appear only on the 90 ms outgoing half of staged fade-throughs (`ios/CashuWallet/Views/Components/LiquidGlassModifiers.swift:453`, `:470`; `ui/navigation/CashuNavHost.kt:472`, `:490`, `:515`; `ui/components/TwoFaceScreen.kt:97`; `ui/receive/nfc/NfcReceiveUi.kt:209`). The outgoing face is gone before the eye reads the curve; this is the Material fade-through recipe and stays.
- **Nothing grows from zero**: every scale entrance on both platforms starts between 0.8 and 0.98.
- **Reduce Motion**: every iOS movement transition is gated; the iOS files with no check (`ScannerWrapperView.swift`, `NostrSettingsSection.swift`, `ReceiveTokenDetailView.swift`) only fade. Android inherits the system animation scale.
- **Press feedback** is fast-down, slower-up and Reduce-Motion-aware on both platforms.
- **Toasts** match across platforms: slide and fade in, fade out, 2.2 s hold, replaced cleanly by a second toast.
- **Copy feedback** on iOS goes through one shared toast at every site.

## Two platform differences, left as they are (decided 2026-11-26)

- **Switching the home balance between bitcoin and fiat rolls the digits on iOS and cross-fades on Android.** Left alone. The iOS roll was kept on purpose when the hidden balance was added (`ios/CashuWallet/Views/Main/MainWalletView.swift`, `balanceHeroButton`: "Bitcoin ↔ fiat stays inside this branch, so the digits still roll"), and `docs/android/DESIGN-ANDROID.md` records the cross-fade as Android's own choice. Neither is a defect.
- **Typing an amount moves glyph by glyph on iOS and cross-fades the whole number on Android.** Left alone. The cross-fade is Android's documented behaviour for every amount (`docs/android/DESIGN-ANDROID.md`, "Numbers are quiet") and lasts about 150 ms. Revisit only if the number looks smeared while typing on a real phone: that was a worry from reading the code, not something seen on screen.

## Not covered by the 2026-11-26 audit

The per-screen deep read was cut short. These were swept by search only, not
read line by line: Send and Receive step internals, Transfer, the scanner, the
biometric authorizing overlay, NFC receive and contactless pay, and
onboarding's ASCII field, vault morph, handoff and seed entry.
