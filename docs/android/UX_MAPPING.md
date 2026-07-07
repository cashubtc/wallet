# Cashu Wallet — iOS ↔ Android UX Mapping

Companion to `UX_SPEC.md`. Lists every iOS surface and its Android counterpart, with a one-line note on any intentional Android-native divergence.

Rule of thumb: when iOS uses a pattern Android also has natively (sheet, alert, push), we use the Android version of the same pattern. When iOS uses a pattern that's not idiomatic on Android (swipe-to-delete, presentation-detent sheets, action sheets), we substitute the Material 3 idiom and note it below.

## Shell

| iOS | Android | Divergence |
|-----|---------|-------------|
| `TabView` with 4 tabs (Wallet / History / Mints / Settings) | `NavigationBar` with 4 items (same labels and order) | Same shape. |
| Bottom tab is system-style, pinned | M3 `NavigationBar` at bottom of `Scaffold`, pinned | Same. |
| `.onOpenURL` deep links | `MainActivity.onNewIntent` → existing `NavigationManager.pendingDeepLink` `StateFlow` → `NavController.navigate(...)` | Same; existing implementation kept. |
| Loading gate (spinner) | Centered `CircularProgressIndicator` full screen | Same. |
| Onboarding gate (full-screen) | Onboarding destination outside `WalletScaffold` | Same — no bottom nav while onboarding. |

## Wallet (Home)

| iOS | Android | Divergence |
|-----|---------|-------------|
| Fixed top section via `.safeAreaInset(edge: .top)` | Pinned region inside `Scaffold` body (a non-scrolling `Column` above a `LazyColumn`) | Same effect; different mechanism. |
| Mint chip = `Menu` (SwiftUI) | `AssistChip` + `DropdownMenu` | Same idiom. |
| Balance tap toggles ₿/sat | Same — taps `BalanceDisplay` | Same. |
| Receive / Scan / Send triptych as inline Liquid Glass capsules | Three equal-weight `FilledTonalButton`s in a `Row` (Scan as middle `IconButton` inside a tonal circle) | M3 has no Liquid Glass; tonal containers are the closest native analog. |
| Action chooser sheet (`.presentationDetents([.height(195)])`, cascade-in) | `ModalBottomSheet(skipPartiallyExpanded = true)` with cascade-in items | Same idiom (both have bottom-sheet primitives). M3 sheets don't support fixed-pixel detents; wrap-content + `skipPartiallyExpanded` matches the feel. |
| `.fullScreenCover` for Scanner | Pushed navigation destination | Push is idiomatic on Android; full-screen-cover is iOS-specific terminology for the same outcome. |
| Recent-activity scroll mask (gradient fade behind pinned top) | Same — `Modifier.drawWithCache` gradient mask on the `LazyColumn` | Same effect. |
| `TopAppBar` Scan icon as additional entry | **Drop the redundant top-bar Scan** — the triptych is enough on Android | Avoids double affordance; M3 doesn't combine pinned content with a `TopAppBar` cleanly. |

## History

| iOS | Android | Divergence |
|-----|---------|-------------|
| `.navigationTitle("History")` large title | `LargeTopAppBar(title = "History")` that collapses on scroll | Same idiom — both are large→small headers. |
| Filter `Menu` with picker | `IconButton` opening `DropdownMenu` with single-select | Same. |
| `.searchable(placement: .navigationBarDrawer)` | `IconButton(Icons.Outlined.Search)` in top bar that swaps the bar for an M3 `SearchBar` | Same idiom — both expand into a search field below the title. |
| `.contextMenu` long-press → "Remove from history" (Cashu Request) | `combinedClickable(onLongClick = ...)` opens a `DropdownMenu` with "Remove from history" | Same idiom. |
| Sheet for transaction detail | **Pushed `TransactionDetailScreen`** | Material 3 prefers full-screen pushed destinations for detail views over sheets. Same information, more idiomatic on Android. |
| `NavigationLink` to Cashu Request Detail | Push to `CashuRequestDetailScreen` | Same. |
| Date-bucketed sections with overline header | `LazyColumn` `stickyHeader` or in-flow header items | Same. |
| Row stagger (first 8) `.smooth(0.32).delay(...)` | `AnimatedVisibility` per row with 35ms-per-index delay, capped at 8 | Same. |
| Badge symbol-replace `.symbolEffect(.replace.downUp)` | `AnimatedContent` with fade-through cross-fade | M3 has no native symbol-replace animation; cross-fade is the closest universally-supported substitute. |

## Mints

| iOS | Android | Divergence |
|-----|---------|-------------|
| `List` with sections (mints, discover, add) | `LazyColumn` with same logical sections | Same. |
| Discover Mints as `.sheet` (`.medium`/`.large` detents) | **Pushed `MintDiscoveryScreen`** | Discovery is a search list — pushed destination with `SearchBar` is more idiomatic on Android than a draggable sheet. |
| `.confirmationDialog` to remove mint | `AlertDialog` with destructive action | M3's analog of action-sheet confirmation. |
| `NavigationLink` to Mint Detail | Push to `MintDetailScreen` | Same. |
| Add-mint inline form | Same — inline `OutlinedTextField`s + `FilledTonalButton` | Same. |

## Mint Detail

| iOS | Android | Divergence |
|-----|---------|-------------|
| `List { … }` of sections | `LazyColumn` of grouped `ListItem`s with `SectionHeader` + `CanvasDivider` between rows | Same shape. |
| `ProgressView()` overlay while loading | `LinearProgressIndicator` at the top edge | M3 prefers top-edge linear indicators for "loading more content" on a list. |
| Remove Mint destructive button + `.confirmationDialog` | Destructive `TextButton` + `AlertDialog` | Same idiom. |

## Settings

| iOS | Android | Divergence |
|-----|---------|-------------|
| `ScrollView` + `LazyVStack` grouped by `sectionGroup` helper | `LazyColumn` with `SectionHeader` + `ListItem`s grouped, `CanvasDivider` between rows | Same shape; M3 component primitives. |
| `NavigationLink` to detail screens | Push to detail routes | Same. |
| External-link rows (Cashu docs, NUT specs) | `ListItem` with trailing `Icons.AutoMirrored.Outlined.OpenInNew`; tap → `Intent.ACTION_VIEW` browser intent | Same idea; native intent on Android. |
| `.alert("Delete Wallet", …)` | `AlertDialog` | Same. |

## Sub-screens (Backup / Lightning / P2PK / Nostr / NWC / Privacy / Appearance)

| iOS | Android | Divergence |
|-----|---------|-------------|
| Pushed detail views inside `NavigationStack` | Pushed Compose destinations inside `NavHost` | Same. |
| Sub-sheet for Lightning address setup | Pushed sub-screen *or* `ModalBottomSheet` — pick whichever matches iOS's depth | Use pushed sub-screen if iOS does (it does for nsec import; uses sheet for relay add). Match iOS per-case. |
| Theme picker (`Picker`) | `SingleChoiceSegmentedButtonRow` for Light/Dark/System | Same idiom — both are segmented controls. |
| Setting toggles | M3 `Switch` rows | Same. |

## Send flows

| iOS | Android | Divergence |
|-----|---------|-------------|
| `.sheet(item:)` from chooser → `SendView` (`.large` detent) | `ModalBottomSheet` hosting `UnifiedSendScreen` / `SendEcashScreen` at full height (`WalletFlowSheetHost`) | **Revised 2026-07:** originally pushed destinations; converted to native M3 modal sheets for iOS sheet parity. Send → Send Ecash swaps content inside the same open sheet. Dismissal is blocked while a payment is in flight. |
| Two-face cross-fade (`AnyTransition.opacity` on `generatedToken`) | `AnimatedContent(targetState = face, transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(250)) })` | Same animation, M3 primitive. |
| Liquid Glass primary "Send" button | `FilledTonalButton`, full width | Closest native analog. |
| Share via `.topBarTrailing` `ShareLink` | `IconButton(Icons.Outlined.Share)` in `TopAppBar.actions`, fires `Intent.ACTION_SEND` | Same place; native share sheet. |
| `.contextMenu { Copy, Share }` on QR | `combinedClickable(onLongClick = ...)` → `DropdownMenu` with Copy / Share | Same idiom. |
| **No send-confirm gate** (per memory) | Same — tap Send fires immediately, button shows spinner | Same. |

## Receive flows

| iOS | Android | Divergence |
|-----|---------|-------------|
| `.sheet` from chooser → ReceiveView (`.medium`/`.large` detents) | `ModalBottomSheet` hosting `ReceiveEcashScreen` (wrap-content ≈ `.medium`) / `ReceiveLightningScreen` (full height ≈ `.large`) via `WalletFlowSheetHost` | **Revised 2026-07:** originally pushed destinations; converted to native M3 modal sheets. M3 has no fixed detents, but wrap-content approximates `.medium` and full-height content approximates `.large`. |
| Two-face cross-fade (`.opacity` on `currentRequest`) | `AnimatedContent` cross-fade | Same. |
| Nested sheets for editing Cashu Request mint/amount | Cashu Request Detail stays a pushed destination (flow sheet closes first), with `ModalBottomSheet` pickers over it | Avoids nested-sheet anti-pattern on Android. |
| Pulsing waiting indicator (`.symbolEffect(.pulse)`) | Infinite-transition alpha pulse on the clock icon | Same effect. |
| Celebration burst on payment received | `AnimatedVisibility(enter = scaleIn(spring) + fadeIn)` + 2.5s hold via `LaunchedEffect`/`delay` | Same. |
| `.fullScreenCover` for `ReceiveTokenDetailView` (deep-link entry) | Pushed `ReceiveTokenDetailScreen` | Same outcome via push. |
| **No receive-confirm gate** | Same — "Receive" fires immediately | Same. |

## Cross-cutting

| iOS | Android | Divergence |
|-----|---------|-------------|
| Liquid Glass (`.glassButton()`) | `FilledTonalButton` everywhere | M3 has no Liquid Glass; tonal containers are the closest native analog. Singular Button Rule preserved. |
| SF Symbols | `androidx.compose.material.icons.*` (extended set) + the existing custom `EcashIcon` | Same intent: standardized iconography. No SF Symbol names left in code. |
| `.thinMaterial`, `.regularMaterial` | M3 `surfaceContainer*` tonal slots; no blur | M3 doesn't ship blur primitives; tonal surfaces are the canonical substitute and align with Flat-By-Default. |
| Haptics via `SensoryFeedback` | `View.performHapticFeedback(...)` using `LocalView.current` | Same intent. |
| `.accessibilityLabel`, `.accessibilityHint` | `Modifier.semantics { contentDescription = ...; stateDescription = ... }` | Same accessibility goals. |
| `.contentTransition(.numericText())` for balance | `AnimatedContent` with `SizeTransform` + per-digit animation, or `NumberAnimation` helper | M3 has no first-class "numeric text transition" — implement a small helper using `AnimatedContent` keyed on digit position. |
| `.symbolEffect(.replace.downUp)` for badges | `AnimatedContent` fade-through | M3 has no symbol-effect; cross-fade is the universally-supported substitute. |
| Swipe-to-delete | **Long-press → `DropdownMenu` with destructive item** (or `ModalBottomSheet` for richer cases) | Material 3 recommends explicit destructive actions over swipe-to-delete for low-frequency irreversible actions. Same outcome, more discoverable. |

## Things this rebuild deliberately does NOT do

- No Material You dynamic color (locked decision — Semantic-Only Rule).
- No `ElevatedButton` / shadows on rows / `Card` chrome (Flat-By-Default Rule).
- No `BottomAppBar` with FAB (the 4 tabs + Wallet-screen triptych replace it).
- No iOS-style chevron back-buttons; uses Material `Icons.AutoMirrored.Filled.ArrowBack`.
- No SwiftUI-style "presentation detents" emulation — sheets are full or wrap-content via `skipPartiallyExpanded = true`. (The money-flow sheets approximate iOS `.medium`/`.large` through content height, not detents.)
- No mock confetti / celebration ribbons; only the iOS-defined 2.5s checkmark bounce on payment received.
- No "confirm sending X to Y?" dialogs (per memory: *No Send-confirm gate*).
- No QR-code library / `QRCodeView` changes (per memory: *Don't touch QR code / library*) — wrap the existing implementation in M3 chrome only.

## Surfaces that may need future iteration

Tracked here because they're iOS patterns that don't have a perfect native Android analog and may benefit from later UX review:

- **Cashu Request Detail editable inspector rows** — iOS opens a `.medium`-detent sub-sheet. We chose `ModalBottomSheet` for parity; an alternative is inline-expanding rows. Defer until Mints PR.
- **Two-face cross-fade** — iOS's pattern is very nice but unusual on Android, where multi-step flows usually push a new destination. Worth re-evaluating after the Send PR ships and we see how it feels.
- **Onboarding step transitions** — iOS uses asymmetric horizontal slide. M3 has no canonical slide-between-steps idiom; we'll use `AnimatedContent` with `slideInHorizontally` + `slideOutHorizontally`. Watch for issues with back-gesture animation.
