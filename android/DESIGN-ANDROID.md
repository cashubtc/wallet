# DESIGN-ANDROID.md — the Android design charter

**Charter (2026-07-08): Android-first, Material 3 Expressive, no iOS constraints.**

The iOS app's `DESIGN.md` governs **what** the product does — screens, flows,
copy intent, feature semantics. It never governs how Android looks, moves, or
feels. Android is a first-class native app: if a choice would make the app feel
like a port, make the Android-native choice instead.

---

## 1. Foundations

### Color — Material You, always
- **Dynamic color** (`dynamicLight/DarkColorScheme`) on Android 12+: the app's
  palette comes from the user's wallpaper. Fallback: M3 baseline scheme
  (violet family) on API 26–31. (`ui/theme/CashuTheme.kt`)
- Full M3 color roles are used as designed: filled primary CTAs, tonal
  secondaries, `secondaryContainer` nav indicator, tonal surface tiers.
- **Semantic state hues stay fixed** (received green / pending orange / error
  red via `CashuColors`) so payment state never shifts with the wallpaper.
  These are the only hardcoded colors.

### Type & shape — stock M3
- `Typography()` and `Shapes()` defaults (`ui/theme/Type.kt`, `Shape.kt`).
  Roles used as designed; no custom ramps.
- Money always chains `withMonoDigits()` (tabular figures) — amounts roll,
  never reflow. `asOverline()` for section headers. `CapsuleShape` available.

### Motion — M3 Expressive springs
- `MaterialExpressiveTheme` + `MotionScheme.expressive()`: spring physics drive
  component motion. New motion should use `spring(...)` specs (or motion-scheme
  tokens), not hand-tuned tweens.
- **Navigation**: shared-axis X (slide + fade) for push/pop; fade-through for
  tab switches (`CashuNavHost.kt`). Predictive back is enabled
  (`android:enableOnBackInvokedCallback`).
- **No hard cuts**: full-screen overlays (scanner, contactless) slide over the
  shell (`CashuApp.kt`); the bottom bar animates away on push
  (`WalletScaffold.kt`); the payment terminal fades/settles in
  (`PaymentStatusScreen.kt`).
- **Touch responds physically**: CTAs and number-pad keys spring-scale on press
  (`Buttons.kt`, `NumberPad.kt`).
- Lists animate placement (`Modifier.animateItem()`), reveals expand/shrink,
  page dots stretch into pills.
- **Reduce-motion**: decorative loops (waiting pulses) render their resting
  state when system animations are off (`ui/theme/Motion.kt`
  `rememberReducedMotion()`).

### Components — expressive first
- Loaders are the expressive `LoadingIndicator` / `LinearWavyProgressIndicator`
  — never the classic circular/linear spinners.
- Tab screens use `LargeFlexibleTopAppBar` (big collapsing titles).
- Settings rows are M3 `ListItem`.
- Button hierarchy: filled `Button` (primary action) → `FilledTonalButton`
  (secondary) → `TextButton` (inline). See `PrimaryButton` / `SecondaryButton`
  / `GhostButton` / `DestructiveTextButton` in `Buttons.kt`.
- Bottom sheets (`ModalBottomSheet`) for choosers/pickers/inspectors; pushed
  destinations for flows. `NavigationBar` for tabs. `AlertDialog` for
  confirmation, destructive action tinted `error`.

### Layout invariants (kept from the structural pass)
- Measure, never assume, overlay heights (Home pinned header uses
  `onSizeChanged`; the list inset and fade mask derive from the measured px).
- Consume the shell scaffold's window insets exactly once
  (`.consumeWindowInsets(contentPadding)` on every tab).
- Bottom inset spacers use `windowInsetsBottomHeight(WindowInsets.navigationBars)`.
- Dimension parameters are `Dp`, never raw `Int`.

### Haptics
`LocalHapticFeedback`: selection-class ticks on taps/toggles, `Confirm`/`Reject`
on payment terminal outcomes, `LongPress` where a long-press acts. Never
double-buzz one gesture.

---

## 2. Feature parity map (iOS = feature reference)

Screens and flows mirror iOS **functionally**: Home (balance, mint chip,
Receive/Send, recent activity), History (search/filter/pull-to-refresh),
Mints (+detail/discovery), Settings (+Backup, Lightning, Locked Ecash hub,
Nostr, Privacy), unified Send, Receive (ecash/lightning/requests), scanner,
contactless, onboarding. See git history for the parity passes.

## 3. Ranked gap backlog (features, not design)

1. **App Lock** — BiometricPrompt gate + `FLAG_SECURE` privacy scrim + Settings
   toggle. Also unlocks auth-gating for the nsec reveal sheets.
2. **Cloud seed backup** — Auto Backup / Blockstore / Drive decision pending.
3. **Cashu Request editing UI** — inspector sub-sheets for Mint/Amount/Unit.
4. **NFC tap-to-pay parity check** — verify `ContactlessPayView` against iOS
   coordinator flow and restyle to the new charter.
5. **Restore-over-units hardening** — loop restore across `mint.units` (do with
   iOS together).
6. **Home received-delta beat** — transient `+amount` beat + success haptic on
   background receives (needs a receive-event signal from WalletManager).
7. **Non-sat History** — needs a `unit` field on `WalletTransaction` (shared
   deferral with iOS).
8. **Shared-element transitions** — transaction row → detail, QR card flows
   (`SharedTransitionLayout`), once nav-level motion has settled.
9. **Expressive `ButtonGroup` / shape-morph press states** — evaluate for the
   Receive/Send pair and number pad when the APIs stabilize.

## 4. Protected areas

- QR pipeline internals (`Views/Components/QRCodeView.kt`,
  `Core/AnimatedUrDecoder.kt`) are off-limits; style around them.
- The Nostr/P2PK seed is `sha256(mnemonic utf8)` on both platforms — never
  change unilaterally.
