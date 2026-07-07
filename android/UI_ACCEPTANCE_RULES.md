# Android UI Acceptance Rules

Status date: 2026-07-07

These rules apply to every Android parity milestone. They define how Android reaches iOS feature and UX parity while remaining a native Android app.

## Platform Direction

- Use Material 3 components and Android-native navigation patterns.
- Do not copy iOS Liquid Glass controls, translucency, or interaction details when Material has a clearer Android convention.
- Preserve iOS user stories, information hierarchy, copy intent, error states, privacy requirements, and payment behavior.
- Prefer native Android APIs: `BackHandler`/predictive back, `BiometricPrompt`, CameraX, Android NFC, system share sheets, TalkBack semantics, dynamic type/font scale, dark theme, and Android lifecycle handling.

## Screen Acceptance

Each user-facing screen is not done until:

- The corresponding iOS screen or flow has been checked against the route inventory.
- Primary actions are reachable on compact phones, large phones, split-screen/narrow widths, and large font sizes.
- Android system back matches the visible Material navigation behavior, including intermediate flow steps, overlays, sheets, dialogs, and in-flight payment states.
- Scroll, IME, status bar, and navigation bar insets are handled explicitly.
- Text has max-line/overflow behavior where user-controlled strings can be long.
- Loading, empty, success, failure, retry, and disabled states exist for all expected user journeys.
- TalkBack labels/hints exist for balances, QR, copy/share, scanner, NFC, destructive actions, toggles, secret reveal, and transaction/request rows.
- Copy/share/open-link actions give feedback or a graceful error.
- No visible setting promises runtime behavior that is not implemented.

## Performance Acceptance

- Settings open/scroll/toggle paths must be profiled before declaring Milestone 12 complete.
- Dense lists should use stable keys and avoid unnecessary subcomposition or expensive formatting during scroll.
- QR/animated-UR generation must not block the main thread during visible animation.
- Off-screen Compose collectors should use lifecycle-aware collection where applicable.
- Baseline profiles or macrobenchmarks should cover startup, tab switching, Settings, Send, Receive, scanner, and dense list scrolling before release.

## Security And Privacy Acceptance

- Seed phrases, nsec values, P2PK private keys, ecash tokens, and payment secrets must never be logged, exposed in crash reports, or committed.
- Secret reveal/copy flows require local authentication once Milestone 1 lands.
- App-switcher privacy and lock behavior must match the selected Android security model.
- Commit messages, PR descriptions, screenshots, and release notes must not include local paths, device names, user identifiers, seeds, tokens, private keys, or other personal data.

## Test Acceptance

- Every milestone with UI changes needs focused Compose or instrumentation coverage unless the change is documentation-only.
- Every parser/protocol/storage change needs JVM tests with positive and negative cases.
- Back gesture, large-font, compact-height, dark theme, and critical accessibility checks belong in the Android UI regression suite.
- Live mint/NFC/camera behavior requires either physical-device validation or a clearly documented deferred release gate.
