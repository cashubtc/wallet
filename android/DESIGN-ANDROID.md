# DESIGN-ANDROID.md — Material 3 Translation of the Cashu Wallet Design System

Companion to `../DESIGN.md` (iOS, source of truth for *intent*) and `../PRODUCT.md`.
This document defines how the iOS design system is expressed on Android, what binds
verbatim, what translates, and what is deliberately not ported. Established during
the 2026-07 design re-sync (branch `feat/kotlin-v2`).

**North Star, restated for Android:** "The System Utility" — the wallet Google would
have shipped if Google shipped ecash. The identity *is* "behaves correctly on
Android": pure Material 3 components, Material Symbols, platform-conventional
navigation and gestures. Zero iOS chrome, zero faux Liquid Glass. Restraint is the
brand; the fixed monochrome palette (never Material You dynamic color) is the one
committed visual choice, exactly parallel to iOS's inverted-ink AccentColor.

---

## 1. What binds vs what translates

### Binding product behaviors (port verbatim — these are the product, not iOS)

| Rule (DESIGN.md / project decisions) | Android expression |
|---|---|
| **No send-confirm gate** — tap Send/Pay fires immediately; no confirm modals, no hold-to-send, no large-amount gate | Identical. `Button(onClick = ::send)`, no `AlertDialog` in the path |
| **Pay screen infers the rail** — destination string determines Lightning / on-chain / Cashu-Request; no rail toggle | Identical parser-driven routing |
| **Send/Receive chooser sheet** — leaf flows stay behind a compact chooser, never a segmented-control screen | `ModalBottomSheet` chooser (exists: `ChooserSheet.kt`), fitted height |
| **Mint-at-top confirms** — pay confirm surfaces put the mint at the top accessory, never a bottom "From" row | Identical layout order |
| **One Green Rule** (amended) — ledger amounts are never green; `.secondary` pending → `.primary` settled; green only on off-row success surfaces + default-mint dot | `onSurfaceVariant` pending → `onSurface` settled; `CashuColors.received` only on detail/status surfaces |
| **Quiet Pending Rule** — pending row = bare unsigned muted amount; no badge, no spinner, no orange on rows | Identical |
| **`+`/`−` sign is a settled signal** — pending rows render bare unsigned amounts | Identical |
| **Tabular Figure Rule** — every money value in tabular figures with numeric roll on change | `fontFeatureSettings = "tnum"` (exists) + animated digit transitions (`Amount.kt`) |
| **Amount Column Rule** — trailing-anchored amounts, one straight column | Identical |
| **Fiat Sub-Amount Rule** — fiat equivalent under sats amounts, caption/secondary, gated on the one Settings toggle | Identical |
| **Monochrome-Glyph + no-emoji** — monochrome system icons only (flag-emoji currency avatar is the sole carve-out) | Material Symbols, monochrome, `LocalContentColor` |
| **Iconless-CTA Rule** — bottom-sheet primary CTAs are text-only; "Copy" flips to "Copied" with no checkmark | Identical |
| **Share-At-Top Rule** — QR-artifact screens put Share in the top app bar trailing slot; bottom row is CTAs only | `TopAppBar` `actions` slot |
| **Flat-By-Default / No-Shadow Absolute** — zero decorative shadows | `Elevation 0.dp` on cards/surfaces we control; depth via `surfaceContainer` tiers + hairlines |
| **Only-Hero-Number** — the balance is the only hero metric; no stat panels | Identical |
| **Quiet/precise copy** — buttons say what they do; errors say what broke + what to try | Copy ports verbatim |
| **Unified error surface** — one inline notice component for all in-context errors; never raw red text | Single `InlineNotice`-style composable (see §4) |
| **Shared empty-state** — one component; Home vs History same geometry, distinct icon+copy | `EmptyState.kt` (exists) |
| **Full-screen payment status** — all pay flows resolve through one processing/success/failure screen; icon morphs spinner→check→X; Done tap required; single bounce beat, smooth easing, no stacked springs | One `PaymentStatusScreen` composable (see §4) |
| **Multi-unit balance pager carve-out** — pager only when active mint is multi-unit AND non-sat balance held; sat page keeps ₿/fiat toggle; non-sat pages never show fiat | `HorizontalPager` keyed by unit (this pass, see §5) |
| **Restore success is a centered "done" hero** | Identical layout intent |
| **Accessibility floor** — dynamic text scaling without truncating money, TalkBack labels, reduced-motion honored, AA contrast, never state-by-color-alone | `sp`-based type + `fontScale`, `contentDescription`, `LocalAccessibilityManager`/`Settings.Global` reduce-motion checks |

### iOS idioms (do NOT port — use the Android-native equivalent)

| iOS idiom | Android-native expression |
|---|---|
| Liquid Glass surfaces/buttons | Standard M3 components on the monochrome scheme. Primary + secondary full-width CTAs: `FilledTonalButton` in `CapsuleShape` (one button vocabulary = Singular-Button Rule; hierarchy by order/copy/enabled, never a second style). Text links: M3 `TextButton` styled `onSurfaceVariant` |
| SF Symbols | Material Symbols (`material-icons-extended`), monochrome |
| SF Pro / Dynamic Type styles | System sans (Roboto) via the M3 type scale in `Type.kt`; no custom fonts |
| `.sheet` detents (`.medium`/`.large`/`.height`) | `ModalBottomSheet` for choosers/pickers/inspectors (fitted content height ≈ fitted detents); **pushed NavHost destinations** for full flows (Send, Receive, detail screens). Android's push-based IA stays — sheets-as-flow-containers is the iOS idiom, not the product |
| Sub-sheet on a sheet (attribute editors) | Nested `ModalBottomSheet` editor over the screen, dismisses on selection — same "context never leaves" intent |
| `.fullScreenCover` camera scanner | Full-screen overlay composable (exists) |
| Swipe-down sheet dismissal | Predictive back + back gesture; `TopAppBar` navigation icon |
| iOS springs (`.smooth`/`.snappy`) | M3 motion scheme: `MotionScheme`/`tween` with `FastOutSlowInEasing`, 180–350 ms; the one celebration spring → `spring(dampingRatio = 0.7f)` equivalent, used once |
| `.contentTransition(.numericText())` | Existing animated tabular-digit implementation in `Amount.kt` |
| `.symbolEffect(.bounce/.pulse/.replace)` | Scale/alpha keyframe on the glyph (bounce = the single celebration beat); infinite alpha pulse for waiting states |
| Haptics (`UIImpactFeedbackGenerator`) | `HapticFeedbackConstants` / `VibrationEffect` (exists in `ui/components`) |
| Context menu (long-press preview) | Long-press → `DropdownMenu` |
| `confirmationDialog` (destructive) | M3 `AlertDialog` with destructive action colored `error` |
| Pull-to-refresh (History re-check) | M3 `PullToRefreshBox` |
| Scroll-fade nav title on detail screens | `TopAppBar` with `enterAlwaysScrollBehavior`/`pinnedScrollBehavior` + title alpha on scroll |
| iOS tab bar | M3 `NavigationBar` (exists in `WalletScaffold.kt`) |
| CanvasDivider 0.5pt hairline | `HorizontalDivider(thickness = Dp.Hairline, color = CashuColors.canvasDivider)` (exists: `Dividers.kt`) |
| Settings carve-out (no hairlines, plain leading icons) | Identical structure with Material Symbols |

**Predictive back** is enabled (`android:enableOnBackInvokedCallback`) — navigation must feel Android-first.

### Layout invariants (2026-07-08 structural pass)

- **Measure, never assume, overlay heights.** Home's pinned top block (scan →
  mint chip → balance hero → Receive/Send) is measured with `onSizeChanged`;
  the LazyColumn's top `contentPadding` and the scroll fade-mask thresholds
  derive from the measured px height (iOS `MainWalletView` GeometryReader +
  PreferenceKey parity). Fixed constants like the retired `PINNED_TOP_HEIGHT`
  break under fiat line / unit pager / font scale / tall status bars.
- **Consume the shell insets exactly once.** `WalletScaffold`'s `Scaffold` has
  no topBar, so its `innerPadding` carries the status-bar inset. Every tab
  applies `.padding(contentPadding)` **and** `.consumeWindowInsets(contentPadding)`
  so nested `TopAppBar`s / `statusBarsPadding()` cannot double-apply it.
- **Scroll fade band = 24dp** under the pinned-header bottom edge (iOS
  `scrollFadeBand: CGFloat = 24`).
- **Bottom inset spacers** use `Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)`,
  not zero-height `navigationBarsPadding()` spacer hacks.
- **Dimension parameters are `Dp`**, never raw `Int` (`CanvasDivider.leadingInset`,
  `QrCard.size`, `MintAvatar.size`).

---

## 2. Token mapping (DESIGN.md → `ui/theme/`)

Already in-tree and verified faithful; kept as the canonical mapping.

- **Colors** (`Color.kt`): fixed monochrome `lightColorScheme`/`darkColorScheme` —
  ink primaries (#000/#FFF inverted), iOS-neutral surface tiers, `outline(Variant)`
  hairlines, `error = #FF3B30`. State hues live in `CashuColors`
  (`received #34C759`, `pending #FF9500`, + 10–16% containers) via
  `LocalCashuColors` — the only hardcoded colors (Semantic-Only Rule).
  **Never** `dynamicLightColorScheme`/`dynamicDarkColorScheme`.
- **Typography** (`Type.kt`): M3 scale mapped to the DESIGN.md ramp — balance →
  `displaySmall`+`tnum`; title → `headlineMedium`; title3 → `titleMedium`(≈); body →
  `bodyLarge` (17sp); caption → `bodySmall`/`labelMedium`; section headers →
  `labelMedium.asOverline()`; mono fragments → `FontFamily.Monospace` at call site.
  `withMonoDigits()` = the Tabular Figure Rule helper.
- **Spacing** (`Spacing.kt`): 4/6/8/12/16/20/24/28 — identical scale.
- **Shape** (`Shape.kt`): 8/12/14/20/28 + `CapsuleShape` — identical radii intent.

---

## 3. Screen inventory: iOS HEAD vs Android (re-sync targets)

**2026-07-07 structural-parity pass:** every key screen was re-verified against
the Swift source's exact element order (not just the Named Rules) and
screenshot-compared on the emulator. Send has NO chooser — Home's Send opens the
unified surface directly (`UnifiedSendScreen` ⇄ iOS `UnifiedSendView`:
destination field with rail inference + round Scan · Ecash · Tap row); Receive
keeps the 2-option chooser (iOS `WalletActionSheetView`; the 4-option
`ReceiveView` in the Swift tree is Preview-only dead code). Settings root
mirrors the iOS section order verbatim (Display with the Currency picker sheet
and root ₿ toggle · Backup & Security · Payments with "Locked Ecash" ·
Integrations · Privacy · About · Danger · version footer); AppearanceScreen was
removed (theme follows the system — strict parity). Receive Lightning: mint row
top, toolbar method menu (no segmented row), method-specific CTAs. Receive
Ecash: tall token editor + **New Request creation** (NUT-18 over the wallet's
Nostr identity via PaymentRequestBuilder + CashuRequestStore).

| Surface | iOS (reference) | Android today | Re-sync work |
|---|---|---|---|
| Root gating | Loading → Onboarding → Tabs cross-dissolve | `CashuApp.kt` equivalent exists | Verify cross-fade, no swoop |
| Home | Balance hero (+multi-unit pager), fiat line, ₿ toggle, LN-address chip, Receive/Send row, Scan in toolbar, recent activity, received-delta beat | `HomeScreen.kt` pre-v8 layout | Full re-sync + pager (§5) |
| Send chooser | Ways-to-send: Scan · Ecash · Tap; step flow input→amount→…​ | `HomeChoosers.kt` | Re-sync copy/order/cascade |
| Send ecash | Amount entry → generated token QR; unit selector | `SendEcashScreen.kt` | Re-sync + multi-unit |
| Send Lightning/on-chain (melt) | Rail inferred from destination; mint-at-top; no confirm gate | `SendLightningScreen.kt` | Re-sync behaviors |
| Pay Cashu Request | payWithEcash / acquireThenPay routes; read-only required-mint | flow exists (`faece1c`) | Re-sync |
| Receive chooser | Paste · Scan · Lightning · Locked | exists | Re-sync |
| Receive ecash / request | Paste face ⇄ fresh Cashu Request cross-fade | `ReceiveEcashScreen.kt` (TwoFace) | Re-sync + unit display |
| Receive Lightning | Invoice QR, isPaid celebration; unit selector | `ReceiveLightningScreen.kt` | Re-sync + multi-unit |
| Token detail (redeem) | Deep-link target, redeem into token's unit | `ReceiveTokenDetail` route | Re-sync + unit fix |
| Cashu Request detail | Inspector rows, status badge trio, Copy/New Request | `CashuRequestDetailScreen.kt` | Re-sync (editable rows → backlog, no creation UI yet) |
| Scanner | Camera overlay, UR reassembly, routes by payload | `ScannerView.kt` overlay | Chrome-only restyle (internals protected) |
| History | Unified timeline, TODAY/… buckets, quiet pending, request rows inline, pull-to-refresh, context-menu delete | `HistoryScreen.kt` | Re-sync |
| Transaction detail | Hero state slot (QR/check/X), Status/Date/Fee/Mint rows, settled-ecash Copy receipt | `TransactionDetailScreen.kt` | Re-sync |
| Mints list/detail/discovery | Default-mint dot, per-unit balances, discovery | `Mints*.kt` | Re-sync + unit rows |
| Settings root + subs | Family-style (no hairlines, plain icons); appearance, LN address, Nostr, P2PK, privacy, backup, security, advanced | `SettingsScreen.kt` + subs | Re-sync |
| Onboarding + restore | welcome → mnemonic → first mint; restore twins (Settings + Onboarding share flow shape) | `OnboardingScreen.kt` | Re-sync |

---

## 4. Component canon (`ui/components/`)

One implementation per pattern; screens never hand-roll these:

- `Amount.kt` — animated tabular-digit money values (Tabular Figure Rule).
- `TransactionRow.kt` / `CashuRequestRow.kt` — canonical rows: muted 36dp
  directional-arrow circle, kind-first title, relative timestamp, trailing
  two-state amount.
- `EmptyState.kt` — the shared empty state.
- `InlineNotice` (in `CashuComponents`/dedicated file) — the single error/notice
  surface; severity-tinted, never raw red text.
- `PaymentStatusScreen` — full-screen processing/success/failure terminal for all
  pay flows; spinner→check→X morph, smooth easing, one bounce beat.
- `ChooserSheet.kt` / `MintPickerSheet.kt` / `UnitPickerSheet.kt` — bottom-sheet
  choosers and attribute editors.
- `Buttons.kt` — the singular button vocabulary (full-width tonal capsule + text
  link). No third style.
- `QrCard.kt` — presentational shell over the protected QR pipeline
  (`Views/Components/QRCodeView.kt`, `Core/AnimatedUrDecoder.kt` — internals
  off-limits).
- `NumberPad.kt` — amount entry pad; unit-native decimals (this pass).

---

## 5. Multi-unit (ported this pass, iOS PR #85 scope)

In scope: unit-native send / receive-token / mint-via-LN; request amounts in the
request's unit; home balance pager; per-unit mint-detail balances. Deferrals kept
identical to iOS: melt & pay-side stay sat-only; primary balance + History stay
sat-denominated; no fiat conversion for non-sat units; on-chain has no unit
selector. Foundation: `CurrencyProtocol` generic fallback, `HomeBalance`,
`UnitAmountEntry`, `MintInfo.mintUnits` helpers, unit-threaded `CdkWalletGateway`,
`WalletState.balancesByUnit`, persisted `homeBalanceUnit`.

---

## 6. Removed for strict parity (Android-only surfaces)

- **NWC (Nostr Wallet Connect)** — `NWCScreen`, route, settings entry,
  `NwcConnection` model, NWC keys in SettingsStore/Manager/StorageProtocol.
  Removed in one revertable commit; may return as a deliberate cross-platform
  feature.
- **QR speed/size on-screen controls** — iOS `QRCodeView(showControls:)` exposes
  them selectively; Android usage aligned to the iOS per-surface gating (kept
  inside the protected pipeline, surfaced only where iOS surfaces them).

No other Android-only user-facing surfaces were found.

---

## 7. Ranked gap backlog (iOS features Android lacks — follow-ups, not this pass)

1. **App Lock** — BiometricPrompt gate on launch/foreground + privacy scrim /
   `FLAG_SECURE` in the app switcher + Settings toggle (iOS `AppLockManager` /
   `PrivacyCoverView`). Security-relevant: top priority.
2. **Cloud seed backup** ("iCloud backup" equivalent) — candidate mechanisms:
   Android Auto Backup (encrypted, `backup_rules.xml` exists), Google Blockstore,
   or Drive app-data + passphrase. Needs a product decision; onboarding
   "Restore from cloud" step depends on it.
3. **Cashu Request creation/editing UI** — iOS builds requests in the receive flow
   and edits Mint/Amount/Unit via inspector sub-sheets; Android only displays
   inbound requests (`CashuRequestStore.createNew` has no call sites). Port after
   this pass; store/builder already accept `unit`.
4. **NFC tap-to-pay parity check** — Android `ContactlessPayView` exists (legacy
   package); verify against iOS `ContactlessPaymentCoordinator` flow and restyle.
5. **Restore-over-units hardening** — `restoreMint` restores the sat wallet only
   (same as iOS today). Loop restore across `mint.units` so non-sat proofs are
   seed-recoverable. Do on both platforms together.
6. **Locked-receive (NUT-18 P2PK-locked request) parity check** — iOS
   `LockedReceiveRequest.build()`; confirm Android's locked-receive path matches.
7. **Sentry opt-in parity** — landed on Android (#82); keep copy in sync with iOS
   privacy settings when that section is re-synced.
8. **Home received-delta beat** — iOS rolls the hero balance and shows a
   transient monochrome `+amount` in the fiat slot on background receives
   (`MainWalletView.receivedDeltaBeat`). Android's balance digits already roll
   (`Amount.kt`); the `+amount` beat + success haptic for background receives
   needs a receive-event signal from WalletManager.
9. **Non-sat History** — extending the timeline beyond sat requires a `unit`
   field on `WalletTransaction` first; do together with iOS (same deferral).
10. **Chooser cascade timing** — iOS options cascade in with
    `.smooth(0.32).delay(index * 0.07)` (fade + 12pt leading slide); verify the
    Receive chooser matches or intentionally diverges.
11. **Pending-amount sign audit** — DESIGN.md Quiet Pending Rule (amended):
    pending row amounts are muted `.secondary` AND unsigned; sign + primary
    color arrive together on settlement. Sweep `TransactionRow`/`Amount` sign
    logic against the amended rule.

## 8. Known no-clean-equivalent flags

- **Merged-glass action row** (iOS `GlassEffectContainer` Receive/Scan/Send
  triptych): no Compose equivalent; expressed as three tonal capsule/circle
  buttons in a row — same geometry, Material surface.
- **Sheet detent promotion** (`.medium → .large` when content grows): bottom
  sheets here use fitted content height; growth animates height within the same
  sheet instead of a detent jump.
- **`.symbolEffect` icon family**: approximated with explicit scale/alpha
  animations; keep to the named beats only.
