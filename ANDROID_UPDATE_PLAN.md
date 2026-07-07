# Android Update Plan

Date: 2026-07-07

This plan compares the current Android implementation against the iOS wallet, treating iOS as the target for feature, payment, UI, UX, and test parity. Android should not copy iOS Liquid Glass controls directly. It should deliver equivalent behavior through native Android and Material 3 patterns: top app bars, bottom navigation, modal bottom sheets, dialogs, snackbars/inline notices, BiometricPrompt, Android NFC, CameraX, and TalkBack-friendly semantics.

## Audit Scope

The audit covered the product docs, design docs, iOS app, iOS unit/UI/integration tests, Android app, Android unit/instrumented tests, and Android migration/design notes. Build outputs and generated Gradle/Xcode artifacts are excluded.

Primary source areas reviewed:

- Product and design docs: `PRODUCT.md`, `README.md`, `DESIGN.md`, `ICLOUD_RECOVERY.md`, `android/README.md`, `android/UX_SPEC.md`, `android/DESIGN-ANDROID.md`, `android/UX_MAPPING.md`.
- iOS app: `CashuWallet/App`, `CashuWallet/Core`, `CashuWallet/Models`, `CashuWallet/Views`.
- iOS tests: `CashuWalletTests`, `CashuWalletUITests`, `CI/IntegrationTests`.
- Android app: `android/app/src/main/java/org/cashu/wallet`, `android/app/src/main/res`.
- Android tests: `android/app/src/test`, `android/app/src/androidTest`.

Repo inventory found 312 Swift/Kotlin/XML files across those app and test scopes. The Android codebase already has a broad Compose shell, payment parser, wallet manager, history timeline, mints, settings, send/receive surfaces, NFC, scanner, Nostr/NPC, Sentry opt-in, multi-unit models, and many JVM unit tests. The remaining work is not a blank rewrite. It is a parity completion pass across security, restore, advanced Cashu Request flows, locked ecash, Lightning/on-chain receive polish, mint metadata, and UI/instrumentation coverage.

## Executive Summary

Android is strongest in:

- Material 3 shell with Home, History, Mints, Settings tabs.
- Unified Send direction with destination inference for Lightning, on-chain, Cashu Requests, and ecash receive handoff.
- Basic receive ecash, receive Lightning/on-chain, Cashu Request detail, scanner, NFC, mint management, Nostr, NPC, privacy, Sentry, and P2PK scaffolding.
- A growing JVM test suite for parsers, models, QR, history grouping, settings, Sentry, Nostr, NFC record parsing, token parsing, and wallet metadata.

Android is materially behind iOS in:

- Security and lifecycle: App Lock, privacy cover, authenticated seed/nsec/private-key reveal, cloud backup/restore, startup saga recovery, keyset refresh, stale quote sync on app foreground.
- Onboarding and restore: iOS has seed reveal acknowledgement, restore-method choice, iCloud restore, staged mint restore, multiple mint URL paste, per-mint restore progress/results, and first-mint multi-select/custom flows. Android still follows an older create/verify/first-mint/restore-input model.
- Cashu Requests: iOS has editable/regenerating requests, quote-backed request intents, attach-by-quote, reusable BOLT12/on-chain history rows, richer detail screens, and NUT-18 locked receive requests. Android only supports a smaller request store/detail path.
- P2PK and locked ecash: iOS has a seed-derived primary P2PK key, NUT-10/NUT-18 request encoding, known-key receive validation, rich P2PK settings, QR/backup/reveal flows, and quick lock affordances. Android mainly has generated/imported P2PK keys and a send-side public-key field.
- Lightning/on-chain receive: iOS has BOLT11 expiry, reusable/amountless BOLT12 offers, on-chain address reuse/new-address controls, explorer observation, success screens, quote-backed history intents, and better polling state. Android is functional but thinner.
- Mint detail: iOS fetches full NUT-06 metadata and renders capabilities, NUT support, contact links, ToS, software version, payment method ranges, connection state, copy/share feedback, and multi-unit balances. Android mostly shows stored mint data and local wallet facts.
- Tests: Android has useful JVM tests but lacks Compose UI coverage, scanner/NFC instrumentation, live CDK/Nutshell integration parity, restore/onboarding tests, and screenshot/accessibility regression coverage.

## Screen And Feature Comparison Matrix

| Area | iOS target state | Android current state | Android gaps |
| --- | --- | --- | --- |
| App shell | `CashuWalletApp` gates loading/onboarding/main tabs, handles app lock/privacy cover, syncs stale quotes on foreground. | `CashuApp` gates loading/onboarding/main tabs and starts/stops listeners. | Add app lock/privacy cover, foreground stale quote sync, startup recovery/keyset refresh, lock-aware secret reveal. |
| Onboarding create | Welcome, seed reveal, acknowledgement, first-mint multi-select/custom/skip, polished transitions. | Welcome, show mnemonic, verify quiz, first mint, restore input. | Replace old verify-first model with iOS-equivalent reveal/ack flow; support multi-mint first setup, previews, skip, restore progress. |
| Onboarding restore | Restore method: iCloud or seed phrase. Seed restore stages mint URLs, restores per mint, shows progress/results. | Mnemonic restore only, much thinner mint restore path. | Add Android cloud restore equivalent, staged mint restore, paste multiple URLs, per-mint retries/results, first-run and settings restore parity. |
| Home | Pinned mint chip, unit pager, received-delta beat, recent transaction/request timeline, scan toolbar, pull refresh. | Pinned top, unit pager, recent timeline, scan icon, pull refresh. | Add received-delta notification/beat, stale quote sync behavior, app-lock overlay integration, final polish/accessibility parity. |
| Send unified | One destination field routes Lightning, BOLT11/12, on-chain, Cashu Request, ecash token handoff; rich quote skeleton, errors, mint switching, add-mint-and-pay. | Unified Send supports common rails and status screen. | Add full Cashu Request fee/recovery/acquire/top-up flow, richer failure CTAs, amountless copy parity, non-sat guardrails, quote retry behavior. |
| Send ecash | Multi-unit token generation, memo, P2PK lock, scanner/quick-fill for P2PK key, claim polling, shared claimed success screen. | Multi-unit send, memo, P2PK text field, claim polling. | Add P2PK scanner/quick fills, seed-derived primary key, locked chip UX, settings-driven key labels, stronger receive/claim parity. |
| Contactless/NFC | Native NFC sheet reads Cashu Request or BOLT11 fallback, prepares token, writes tag, hands Lightning to Send. | Android NFC reader UI reads/writes NDEF and routes Lightning. | Align routing/fallback/acquire decisions, errors, non-sat unsupported copy, success state, tests on Android NFC behavior. |
| Receive chooser | Paste ecash, scan QR, payment request, receive locked ecash. | Home receive chooser covers ecash vs Bitcoin; Receive Ecash has New Request. | Add explicit locked ecash entry and parity-level receive chooser semantics where Android navigation expects it. |
| Receive ecash token | Review amount/fee/mint/locked-to, unknown mint caution, receive later, shared processing/success/failure screen, home delta notification. | Review amount/fee/mint/P2PK, receive later, inline receive close. | Add shared status morph, known-key validation using seed primary key, unknown mint caution, home notification delta, terminal failure retry model. |
| Cashu Request creation/detail | Editable request amount/mint/unit, regenerate encoded request with same id, share top, copy, delete, status, paid auto-complete. | QR/detail screen with copy/delete and static rows. | Add store update/upsert/attach-by-quote, editable detail rows, unit/mint pickers, quote-backed intents, paid detection behavior. |
| Receive Lightning | BOLT11/BOLT12/on-chain method picker, BOLT11 expiry, reusable BOLT12 offer reuse, on-chain address reuse/new address, explorer observer, final success screen. | Supports method selection, QR display, subscriptions/polling. | Add reusable amountless BOLT12 offer model, quote-backed history rows, expiry, on-chain explorer observation, address reuse/new address controls. |
| Locked ecash/P2PK | Seed-derived primary P2PK key, NUT-10 request builder, locked receive QR, advanced keys, QR/backups, authenticated secret reveal. | Generated/imported P2PK keys and send-side lock field. | Implement primary key, NUT-10/NUT-18 builder, locked receive request, receive signing with seed key, rich P2PK settings UI. |
| History | Unified request/transaction timeline, search, filter, date buckets, lazy windowing, swipe delete request, stale quote sync on open. | Unified timeline, search toggle, filter, date buckets, long-press request delete. | Add stale sync on tab open, large-ledger windowing, discoverable Material swipe/delete action, exact row/detail parity audit. |
| Transaction detail | Actionable QR only while useful, passive copy for settled ecash, explorer links, canonical row order. | Similar detail screen and display helper. | Audit extra/missing rows, copy labels, QR/share conditions, non-sat future handling, tests against iOS rules. |
| Mints list/discovery | Mint list, default dot, discover via Nostr, added/discovered sections, pull refresh, add custom/paste, refresh mint info on open. | Mint list, swipe actions, discovery sheet, add form, scan/paste. | Add refresh mint info on tab open, discovery refresh/errors/session added animation, more exact copy and accessibility. |
| Mint detail | Full NUT-06 metadata, NUT capabilities, contact links, ToS, software version, min/max rail amounts, connection state, share/copy, multi-unit balances. | Header, local metadata, payment methods, local balances, set active/remove. | Implement full mint info fetch/rendering and connection/error states. |
| Settings root | Display, Backup & Security, Payments, Integrations, Privacy, About, Danger. App Lock and iCloud Backup are first-class. | Similar sections, but no App Lock/cloud backup; has startup pending toggle. | Add App Lock/cloud backup, hide or wire storage-only toggles, sync Nostr/P2PK/Lightning detail behavior. |
| Backup | Authenticated seed reveal/copy, iCloud Backup settings, restore wizard. | Seed grid and restore entry; reveals seed directly. | Require BiometricPrompt/device credential, add cloud backup equivalent, direct restore wizard parity. |
| Nostr | Key source/status, generated/imported key management, authenticated nsec reveal, relay validation/reset/copy. | Signer selection, public/private key, relays. | Add auth for nsec reveal/copy, richer relay validation/errors, reset confirmations, key-card polish. |
| Privacy/Sentry | Auto-paste, Nostr/NPC/WebSockets, Sentry opt-in, payment request receive toggles when runtime exists. | Similar toggles plus storage-only startup/payment request toggles. | Remove/hide unwired toggles or implement runtime; ensure Sentry copy and logging privacy match. |
| Scanner | Camera scanner with UR progress, quick fills, restricted Cashu Request mode, internal routing, mint URL copy fallback. | CameraX scanner with UR progress and shell routing. | Add quick fills/restricted mode/caller prompts, richer unsupported-content behavior, scanner-specific tests. |
| Tests | iOS has unit, UI, and CI integration tests covering parsers, service layers, UI tabs/settings/receive, and Nutshell flows. | Android has many JVM unit tests and one storage instrumentation test. | Add Compose UI tests, integration harness, scanner/NFC instrumentation, restore/security tests, screenshots/accessibility. |

## Milestone 0: Source Of Truth And Parity Guardrails

Goal: make Android parity work traceable before changing behavior.

Checklist:

- [x] Treat `CashuWallet` iOS code and shared product/design docs as the behavior source of truth. Implemented in `android/PARITY_TRACKER.md`.
- [x] Update or archive stale Android migration notes that claim parity where code does not yet match iOS behavior. The stale Kotlin migration plan was removed; `ANDROID_UPDATE_PLAN.md` is now the authoritative plan.
- [x] Add an Android parity tracker issue/list that links each milestone in this file to implementation PRs. Implemented in `android/PARITY_TRACKER.md`.
- [x] Create a screen inventory for Android routes in `CashuNavHost` and map each route to its iOS counterpart. Implemented in `android/ROUTE_PARITY_INVENTORY.md`.
- [x] Hide or label Android settings that are storage-only and not wired to runtime behavior, especially `checkPendingOnStartup`, `enablePaymentRequests`, and `receivePaymentRequestsAutomatically`. The visible startup toggle now accurately scopes itself to sent-token claim checks; the stored payment-request toggles remain hidden from UI until runtime behavior lands.
- [x] Add a small set of fake wallet/state fixtures for Compose previews/tests so UI parity can be developed without a live mint. Implemented as `PreviewWalletFixtures` with JVM sanity tests.
- [x] Define Android UI acceptance rules: Material 3 components, Android back behavior, TalkBack semantics, large text support, dark theme, no iOS Liquid Glass cloning. Implemented in `android/UI_ACCEPTANCE_RULES.md`.

Success condition:

- Every Android screen and backend area has an owner milestone, stale parity claims are corrected, and no visible user setting promises behavior that is not implemented.

## Milestone 1: Security, App Lock, Backup, And Wallet Lifecycle

Goal: bring Android's security posture and startup correctness up to iOS before expanding payment surfaces.

iOS reference:

- `AppLockManager`, `AppLockView`, and `PrivacyCoverView` lock the wallet, obscure the app switcher snapshot, and fail open only when device authentication is unavailable.
- Backup and private-key reveal flows require local authentication.
- iCloud Backup stores seed in iCloud Keychain and mint URLs in iCloud; onboarding/settings can restore from it.
- Wallet startup runs best-effort recovery, keyset refresh, cached state load, balance refresh, and pending quote maintenance.

Android gaps:

- No App Lock, BiometricPrompt/device credential gate, or privacy cover.
- Seed and Nostr private key can be revealed/copied without authentication.
- Android manifest currently sets `android:allowBackup="false"` while backup XML files exist, so there is no product-level cloud backup equivalent.
- `WalletManager.initialize()` opens the wallet and loads cached state but does not match iOS startup maintenance for incomplete sagas/keysets/stale quotes.
- Logging can include full mint URLs in some paths; privacy-safe logging should be audited before release.

Checklist:

- [ ] Add `AppLockManager` equivalent using `BiometricPrompt` with `DEVICE_CREDENTIAL` fallback.
- [ ] Add settings state for app lock enablement and a Material settings row under Backup & Security.
- [ ] Add a full-screen lock gate in `CashuApp` after onboarding and before `WalletScaffold`.
- [ ] Add lifecycle handling to obscure balances on `ON_PAUSE`/`ON_STOP` and relock after the same grace period as iOS, adjusted for Android lifecycle norms.
- [ ] Use `WindowManager.LayoutParams.FLAG_SECURE` or a dedicated privacy overlay according to the chosen Android UX; document the choice.
- [ ] Require authentication before seed reveal/copy in `BackupScreen`.
- [ ] Require authentication before Nostr nsec reveal/copy in `NostrScreen`.
- [ ] Require authentication before any P2PK private key reveal/copy once P2PK backups land.
- [ ] Decide Android cloud backup product model: encrypted Google Drive app-data, Android Auto Backup with app-side encryption, Google Block Store, or explicit no-cloud MVP.
- [ ] If cloud backup is implemented, store seed and mint list encrypted with a key protected by Android Keystore and document restore constraints.
- [ ] Add cloud backup settings, last-backup status, enable/disable confirmation, backup now, clear backup, and restore-from-cloud entry points.
- [ ] Add startup maintenance matching iOS: recover incomplete CDK sagas, refresh keysets for tracked mints, load cached state, refresh balance, load transactions, and sync pending mint quotes if stale.
- [ ] Run stale pending-quote sync on app foreground and when History opens.
- [ ] Audit logs and Sentry breadcrumbs for raw token, seed, private key, local path, full URL, and user/device identifiers.

Success condition:

- A returning Android user with App Lock enabled cannot view wallet content in-app or in the app switcher without authentication; secrets require authentication; startup recovers pending wallet state as iOS does; backup behavior is intentionally implemented or intentionally hidden.

## Milestone 2: Onboarding And Restore Parity

Goal: replace the older Android onboarding with the current iOS restore/create model.

iOS reference:

- Create flow: welcome, generated seed reveal, acknowledgement, first mint selection, then wallet.
- Restore flow: restore method, seed input, staged mint restore, progress, per-mint results, and iCloud restore.
- First mint supports recommended mints, custom URLs, staged rows, skip, previews, and restore progress.

Android gaps:

- Android still includes a mnemonic quiz step that iOS no longer uses.
- First mint is older and less capable than iOS; it lacks staged multiple mints and richer restore results.
- Restore input does not offer cloud restore, staged mint URLs, per-mint progress, retry, or settings restore parity.

Checklist:

- [ ] Redesign `OnboardingScreen` state machine to match iOS steps: welcome, show mnemonic, first mint, restore method, restore input, restore mints, restore progress, cloud restore.
- [ ] Remove or demote the mnemonic quiz unless a deliberate Android-only product decision keeps it.
- [ ] Add seed reveal/acknowledgement UI with Material 3 cards/rows and safe copy behavior.
- [ ] Add restore method screen: restore from Android cloud backup, restore from seed phrase.
- [ ] Add mnemonic input validation matching iOS supported word counts and error copy.
- [ ] Add staged mint restore UI that accepts multiple URLs from paste, normalizes candidates, fetches mint preview name/icon, and allows remove/reorder/retry.
- [ ] Add first-mint setup with recommended mints, custom URL entry, paste multiple URLs, skip, and progress/result rows.
- [ ] Add per-mint restore result model equivalent to iOS `RestoreMintResult`: recovered, pending, failed, skipped.
- [ ] Make restore usable from Settings without forcing an awkward full onboarding restart.
- [ ] Add onboarding analytics/breadcrumbs only if Sentry is opted in and without sensitive values.

Success condition:

- A new Android user can create a wallet and add zero, one, or multiple mints with the same decision points as iOS; a restoring user can restore seed plus staged mints with progress/results and can recover from partial failures.

## Milestone 3: Home, Shell, And Foreground Behavior

Goal: finish the user-facing home experience and app shell parity.

iOS reference:

- Home has a pinned mint chip, unit pager, large balance, transient received-delta beat, Receive/Send actions, scanner toolbar button, recent timeline, pull-to-refresh, and stale quote sync hooks.
- The shell respects app lock and privacy cover.

Android current state:

- `HomeScreen` already has pinned top content, mint chip, unit pager, scan button, action duet, unified recent timeline, empty states, and pull refresh.

Checklist:

- [ ] Add home received-delta beat for sat receives, with Material motion and TalkBack-safe behavior.
- [ ] Post receive events from ecash, Lightning, on-chain, Cashu Request, and NPC receive paths consistently.
- [ ] Avoid showing sat delta for non-sat token receives, matching iOS.
- [ ] Add foreground stale quote sync and pending-token checks behind the same settings/runtime semantics as iOS.
- [ ] Ensure Home recents suppress duplicate transaction rows when a Cashu Request row represents the same payment.
- [ ] Verify unit pager only appears when the active mint supports multiple units and a non-sat balance is held.
- [ ] Align empty states, button enabled states, scanner routing, and refresh semantics with iOS while using Material 3 idioms.
- [ ] Add TalkBack labels for active mint, balance toggle, Receive, Send, Scan, unit pager dots, and recent rows.

Success condition:

- Home shows the same wallet facts and lifecycle feedback as iOS, with no duplicate request/transaction rows and no unprotected content during app lock or task-switching.

## Milestone 4: Unified Send, Pay Flows, And Contactless

Goal: complete all outgoing payment behavior that iOS supports.

iOS reference:

- `UnifiedSendView` accepts Lightning address, BOLT11, BOLT12, on-chain address, Cashu Request, ecash token, scan, ecash creation, and NFC tap.
- Cashu Requests can be paid from existing ecash, paid after adding/funding a requested mint, or fall back to bundled BOLT11 when appropriate.
- Fee rows are precomputed/reserved; failures use shared status screens with useful CTAs.

Android gaps:

- `UnifiedSendScreen` handles common rails but lacks iOS's `addMintAndPayCashuRequest`, `NeedsExternalTopUp`, `MintSettling`, fee precomputation helpers, and add-mint chooser/top-up QR.
- Cashu Request confirm rows are simpler and do not show live fee/acquire states.
- Send ecash P2PK entry lacks scanner quick-fill and seed-derived primary key integration.
- Contactless has Android UI, but routing/fallback/error behavior is not as complete as iOS.

Checklist:

- [ ] Port or implement `routeForCashuPaymentRequest` decisions fully, including pay-with-ecash, pay-BOLT11-fallback, and acquire-then-pay.
- [ ] Add Android equivalents for `mintInputFeePpk`, `estimateCashuPaymentFee`, `addMintAndPayCashuRequest`, external top-up, and settling errors.
- [ ] Add Add Mint To Pay bottom sheet with preview name/icon and multi-mint target choice.
- [ ] Add top-up QR flow when no held mint can bankroll the target mint.
- [ ] Reserve fee/mint rows during loading so confirm/status screens do not jump.
- [ ] Add insufficient-balance recovery CTA to choose another compatible mint.
- [ ] Match iOS amountless BOLT11/BOLT12 caution copy and do not route to doomed quote creation.
- [ ] Normalize method copy so BOLT12 naming matches iOS product language where intentional.
- [ ] Add non-sat Cashu Request handling: show clear unsupported notice until Android can pay non-sat requests.
- [ ] Add send ecash scanner/quick-fill for recipient P2PK public keys.
- [ ] Add locked-key chip UX instead of only a raw text field.
- [ ] Use seed-derived primary P2PK key in quick-fill once Milestone 7 lands.
- [ ] Align contactless NFC routing with iOS: Cashu Request when payable, BOLT11 fallback when needed, Lightning handoff to Send, clear unsupported unit/no mint/insufficient balance errors.
- [ ] Add tests for destination inference, Cashu Request fallback/acquire, fee rows, mint switching, top-up, and NFC input decoding.

Success condition:

- Every outgoing payment path available on iOS can be completed on Android or produces the same intentional unsupported/recovery surface; Cashu Requests are not dead ends when iOS can add/fund/pay them.

## Milestone 5: Receive Ecash And Cashu Request Parity

Goal: complete the inbound ecash and NUT-18 request experience.

iOS reference:

- Receive ecash has paste/scan, auto-paste, token review, fee loading, unknown mint caution, locked-to-known-key validation, receive later, and shared processing/success/failure status.
- Cashu Requests are editable, regeneratable, shareable, deletable, attachable to payments, and integrated into Home/History.
- `CashuRequestStore` supports `create`, `createNew`, `upsertQuoteIntent`, `update`, attach by request or quote id, delete, reset, and reload.

Android gaps:

- Receive ecash closes directly on success rather than using the full shared status flow and home delta event.
- Locked token validation only sees generated/imported P2PK keys, not the iOS seed-derived primary key.
- New request defaults to the active mint URL, while iOS creates an any-mint request by default.
- Cashu Request detail is mostly read-only and store APIs are missing quote-backed and update behaviors.

Checklist:

- [ ] Add shared PaymentStatus screen to receive-token flow: Claiming, Payment Received, Could Not Receive.
- [ ] Add a minimum processing beat for instant receive, matching iOS's legibility behavior.
- [ ] Post home received notification after successful token receive, including unit, and suppress misleading sat deltas for non-sat.
- [ ] Add unknown mint caution before receiving a token from a mint not yet tracked.
- [ ] Add locked-to row that distinguishes "Your key" from unknown key and disables Receive for unknown locked tokens.
- [ ] Generate stable pending receive IDs that do not collide for repeated long tokens.
- [ ] Decide and align default New Request mint scope with iOS. Current iOS uses any mint (`mints: []`); Android should match unless product explicitly changes both platforms.
- [ ] Expand Android `CashuRequestStore` with create/upsert/update/attach-by-quote/reset/reload semantics.
- [ ] Add quote-backed Cashu Request intents for BOLT12 reusable offers and on-chain/BOLT11 receive requests.
- [ ] Add editable Cashu Request detail rows: Amount, Mint, Unit, Memo where applicable.
- [ ] Regenerate encoded request with the same request id when amount/mint/unit changes.
- [ ] Add Material pickers for Cashu Request amount, mint, and unit.
- [ ] Add top-bar share action and bottom copy action rules matching iOS.
- [ ] Add paid/waiting status behavior and auto-completion when request payment lands.
- [ ] Add request delete confirmation copy that makes clear payment routing remains valid.

Success condition:

- Android can create, edit, share, copy, receive, complete, and history-track Cashu Requests the same way iOS can, including quote-backed receive intents.

## Milestone 6: Receive Lightning, BOLT12, And On-chain

Goal: finish inbound Lightning/on-chain receive parity.

iOS reference:

- Receive Lightning supports BOLT11 invoices, reusable BOLT12 invoices/offers, and on-chain Bitcoin addresses.
- BOLT12 amountless offers are reusable and can be re-opened without duplicate history rows.
- BOLT11 has expiry countdown and expired state.
- On-chain receive can reuse an address, request a new one, observe payment, show explorer status/link, and mint after payment.
- Receive flows use shared success screens and update history/home.

Android gaps:

- `ReceiveLightningScreen` is structurally present but simpler.
- Reusable amountless BOLT12 offer handling, quote-backed history intent, amount editing, expiry countdown, on-chain observer link/status, and address reuse/new-address controls need parity.

Checklist:

- [ ] Build a Material method picker equivalent to iOS method choices: Lightning invoice, reusable invoice, on-chain address.
- [ ] For BOLT11, show expiry countdown, expired state, and disable/refresh behavior as iOS does.
- [ ] For BOLT12, support reusable amountless offers and fixed-amount offers with clear copy.
- [ ] Reuse an existing amountless BOLT12 offer instead of creating duplicates.
- [ ] Store BOLT12 and on-chain receive quotes as Cashu Request quote intents in History.
- [ ] Add amount editing for reusable BOLT12 request rows.
- [ ] For on-chain, support existing address reuse and "Use new address".
- [ ] Observe on-chain payment and show status, block explorer link, address, and tx link where available.
- [ ] Use shared processing/success screens when minting paid quotes.
- [ ] Support multi-unit mint units for receive quote creation where CDK supports them.
- [ ] Align BOLT12 product copy with iOS. If iOS says "Reusable invoice", Android should not surface conflicting "Offer" wording without a deliberate copy decision.

Success condition:

- A user can receive via BOLT11, reusable BOLT12, and on-chain on Android with the same lifecycle, history, copy/share, and success behavior as iOS.

## Milestone 7: Locked Ecash And P2PK

Goal: bring Android locked ecash to the same public user-facing and protocol level as iOS.

iOS reference:

- `PaymentRequestBuilder` can encode NUT-10 data inside NUT-18 requests.
- `LockedReceiveRequest.build()` creates a request locked to the wallet's seed-derived primary P2PK key and routed over Nostr relays.
- Settings exposes "Your key", quick lock, advanced keys, key detail, QR, backup/reveal with auth, rename/remove/used count, and explainer.
- Receive token validation and signing include the seed-derived primary key plus stored P2PK keys.

Android gaps:

- Android `PaymentRequestBuilder` has no `nut10` payload.
- There is no `LockedReceiveRequest`.
- `SettingsManager.p2pkSigningKeysFor` only checks stored keys and does not include seed-derived primary key.
- `P2PKScreen` is a basic generated/imported key list without primary key card, backup/reveal auth, QR, rename, key detail, or explainer.

Checklist:

- [ ] Add Android NUT-10 encoding to `PaymentRequestBuilder.build`.
- [ ] Add `LockedReceiveRequest` builder using seed-derived primary P2PK key and configured Nostr relays.
- [ ] Add tests equivalent to iOS `testLockedReceiveRequestEncodesNut10AndParses`.
- [ ] Add seed-derived primary P2PK public/private key access through Android secure storage/seed entropy, protected by authentication for private reveal.
- [ ] Include seed-derived primary private key in `p2pkSigningKeysFor` when a token is locked to that public key.
- [ ] Add `isKnownP2PKPublicKey` equivalent that checks primary and stored keys.
- [ ] Update receive token review to label primary-key locked tokens as "Your key".
- [ ] Add Receive Locked Ecash entry and QR detail screen.
- [ ] Redesign `P2PKScreen` with Material sections: Your key, Quick lock toggle, Advanced keys, generated/imported device keys.
- [ ] Add key detail screen: public key QR/copy, private key reveal/copy behind auth, rename, remove, usage count.
- [ ] Add import sheet validation and duplicate handling.
- [ ] Add explainer sheet/section for locked ecash using concise Android-native copy.
- [ ] Add send ecash quick-fill for "Your key" and recent copied public key.

Success condition:

- Android can create receive requests for ecash only the wallet can claim, receive locked tokens addressed to its primary key, and manage P2PK keys with security and UX parity.

## Milestone 8: Mints And Mint Metadata

Goal: bring mint list, discovery, and detail up to iOS information richness.

iOS reference:

- Mints list refreshes mint info, supports discovery, custom/paste add, default set/remove gestures, active dot, and detailed mint pages.
- Mint Detail fetches full NUT-06 info via CDK and renders balance, non-sat balances, connection, about, MOTD, capabilities, NUT technical details, payment method ranges, contact links, software, units, ToS, share/copy, set default/remove.

Android gaps:

- List/discovery are close but do not refresh mint info on tab open like iOS.
- Mint Detail lacks full NUT-06 fetch/rendering, capabilities/NUTs, contact links, ToS, software version, connection state, method min/max details, and share action.

Checklist:

- [ ] Add Android `fetchFullMintInfo` via CDK gateway.
- [ ] Add `refreshMintInfo` on Mints tab open, with safe placeholder handling.
- [ ] Render connection state: checking, online, offline.
- [ ] Render About, long description/read more, MOTD.
- [ ] Render capabilities summary: Lightning, on-chain, locked ecash, HTLC where supported.
- [ ] Render NUT technical details for NUT-04/05/07/08/09/10/11/12/14/20 where CDK exposes them.
- [ ] Render Receive/Send payment methods with min/max amounts and on-chain confirmation details.
- [ ] Render contact rows as Android intents where possible: email, website, X/Twitter, Telegram, Nostr, generic copy.
- [ ] Render software version and ToS link.
- [ ] Add top-bar share action for mint URL.
- [ ] Add copy feedback for full URL.
- [ ] Keep multi-unit balances and ensure non-sat unit wallets are queried without unintentionally creating new wallets.
- [ ] Improve discovery: search, added/discovered sections, pull/explicit refresh, error state, session-added state, and WebSockets-disabled state.

Success condition:

- Mint Detail on Android exposes the same mint facts a user can inspect on iOS, and mint discovery/list management behave as equivalent Android-native flows.

## Milestone 9: History And Transaction Detail

Goal: make Android's ledger review surfaces fully equivalent and scalable.

iOS reference:

- History merges transactions and Cashu Requests, suppresses duplicates, filters, searches, groups by date, lazily windows large lists, syncs stale quotes on open, supports pull refresh, and offers swipe delete for request rows.
- Transaction detail uses strict QR/share/copy rules and canonical row order.

Android current state:

- History already merges requests and transactions, filters, searches, groups, refreshes, opens transaction/request details, and supports request deletion through long press.
- Transaction detail mostly mirrors actionable QR, settled ecash copy, explorer link, and detail rows.

Checklist:

- [ ] Add stale pending quote sync when History opens, matching iOS `syncPendingMintQuotesIfStale`.
- [ ] Add large-ledger windowing/pagination equivalent to iOS visible-count extension.
- [ ] Replace or supplement long-press request delete with a discoverable Material swipe action or overflow menu.
- [ ] Audit row copy and empty states against iOS: No Results, Nothing Here, No History Yet.
- [ ] Audit transaction detail rows against iOS canonical rows. Remove Android-only rows unless the same row is intentionally added to iOS.
- [ ] Verify QR visibility rules for pending ecash, reusable BOLT12, settled one-shot Lightning, on-chain addresses, and failed transactions.
- [ ] Verify passive copy for settled ecash without QR/share.
- [ ] Add non-sat transaction display plan for future non-sat completed rows.
- [ ] Add tests for duplicate suppression, search over request received amount, date buckets, QR/share/copy rules, and explorer URL generation.

Success condition:

- Android History and Transaction Detail tell the same ledger story as iOS, scale to large histories, and expose destructive request removal in an Android-discoverable way.

## Milestone 10: Settings, Integrations, And Privacy

Goal: align every user-facing setting with iOS behavior and remove misleading surfaces.

iOS reference:

- Settings sections: Display, Backup & Security, Payments, Integrations, Privacy, About, Danger.
- Backup & Security includes Backup & Restore and App Lock.
- Payments includes Lightning and Locked Ecash.
- Integrations includes Nostr.
- Privacy includes auto-paste, WebSockets/Nostr/NPC, Sentry opt-in, and related runtime-backed toggles.

Android gaps:

- No App Lock row.
- No cloud backup row.
- Backup seed reveal is unauthenticated.
- Nostr nsec reveal/copy is unauthenticated.
- Some Android privacy toggles are storage-only or not fully wired to runtime behavior.
- Nostr relay validation and key management are thinner than iOS.

Checklist:

- [ ] Add App Lock settings row and detail behavior from Milestone 1.
- [ ] Add Android cloud backup settings row if product implements backup; otherwise do not show a backup promise.
- [ ] Update Backup & Restore to launch a real restore wizard rather than only reopening old onboarding.
- [ ] Require auth for Backup seed reveal/copy.
- [ ] Require auth for Nostr private key reveal/copy.
- [ ] Add Nostr key card/status, generate/import/reset confirmations, nsec reveal sheet, and relay validation.
- [ ] Validate relays as `ws://` or `wss://`, deduplicate, show errors, support reset to defaults.
- [ ] Align Lightning/NPC settings with iOS, including lightning address rows, mint selection, claim behavior, and Sentry-safe errors.
- [ ] Hide or implement `checkPendingOnStartup` based on iOS product decision.
- [ ] Hide or implement `enablePaymentRequests` and `receivePaymentRequestsAutomatically`; do not leave them as inert user promises.
- [ ] Ensure Sentry opt-in copy is explicit, off by default, and never sends secrets/tokens/seeds.
- [ ] Align Display settings: currency picker, BTC/sat symbol toggle, fiat price refresh/caching, and home balance unit persistence.
- [ ] Align Danger Zone delete wallet confirmation and backup implications.

Success condition:

- Every Android setting either has the same runtime effect as iOS or is intentionally absent. Secrets and destructive actions are protected and explained.

## Milestone 11: Protocol, CDK, Storage, And Runtime Hardening

Goal: ensure Android is not only visually caught up but also as robust against wallet edge cases as iOS.

Checklist:

- [ ] Port startup saga recovery behavior or implement equivalent CDK repository recovery for incomplete mint/melt/send operations.
- [ ] Refresh keysets for tracked mints at startup and when stale.
- [ ] Add stale quote throttling and pending quote sync equivalent to iOS.
- [ ] Ensure all mint/melt/send/receive operations refresh balance, load transactions, and emit update events consistently.
- [ ] Audit multi-unit paths across send, receive, mint detail, payment request decoding, quote creation, token parsing, history, and amount formatting.
- [ ] Add quote-backed transaction/request metadata storage equivalent to iOS for BOLT12/on-chain/BOLT11 receive intents.
- [ ] Revisit Android service architecture. Current `LightningService`, `MintService`, `TokenService`, and `TransactionService` are typealiases/anchors to `WalletManager`; either keep this intentionally with tests or split responsibilities to match iOS service boundaries.
- [ ] Add privacy-safe error mapping equivalent to iOS `WalletErrors` so raw CDK errors do not leak internal jargon to users.
- [ ] Make wallet replacement/delete restore snapshots as robust as iOS during failures.
- [ ] Ensure Android secure storage deletes wallet-scoped secrets at wallet boundary while preserving app-scoped settings intentionally.
- [ ] Add CDK feature tests for latest supported NUTs, BOLT11, BOLT12, on-chain, NUT-18, NUT-10/P2PK, NUT-20 subscriptions, NUT-09 restore, and multi-unit.

Success condition:

- Android wallet operations recover from interrupted startup/payment states, handle the same protocol features as iOS, and expose user-facing errors at the same quality level.

## Android UI Bug Audit Backlog

This section captures Android-specific UI bugs and likely bugs found during the Compose code audit. These are not feature gaps alone; they are issues that can cause broken navigation, clipped content, lag, inaccessible controls, or surprising interactions even before full iOS parity work is complete.

### Back Gesture And Predictive Back Bugs

The Android UI currently relies heavily on top app bar back buttons and route pops. Several screens have internal steps, overlays, or modal-like states but no matching `BackHandler`, so the system back gesture can leave the flow instead of moving back one logical step.

Checklist:

- [ ] Add `BackHandler` to `CashuApp` overlays so Android back closes scanner and contactless pay overlays before leaving the app or popping navigation.
- [ ] Add `BackHandler` to `OnboardingScreen` so system back mirrors the visible back action, moves through onboarding steps predictably, and does not accidentally exit mid-create or mid-restore.
- [ ] Add `BackHandler` to `UnifiedSendScreen` so input, amount, confirm, and status states follow the same behavior as the toolbar back button; block or confirm during in-flight sends.
- [ ] Add `BackHandler` to `SendEcashScreen` so the generated-token state returns to input instead of closing the route.
- [ ] Add `BackHandler` to `ReceiveEcashScreen` so review returns to paste/scan input and receive-later or in-flight states are not abandoned silently.
- [ ] Add `BackHandler` to `ReceiveLightningScreen` so invoice/offer/address display returns to input or confirms cancellation instead of popping the whole route.
- [ ] Add `BackHandler` to `HistoryScreen` so back closes search mode before leaving the tab.
- [ ] Add `BackHandler` to scanner and contactless surfaces directly, even when launched through shell state, so close/dispose logic is always executed.
- [ ] Verify predictive back previews on Android 14+ for pushed routes, full-screen overlays, bottom sheets, dialogs, and multi-step send/receive flows.

Success condition:

- Every Android system back gesture produces the same logical result as the visible Material navigation control, with no accidental app exits, lost in-flight payments, stuck camera/NFC sessions, or skipped intermediate steps.

### Layout, Clipping, Insets, And Scroll Bugs

Multiple screens assume a comfortable phone height or default font size. These layouts need to survive compact phones, split-screen, gesture navigation bars, landscape-ish heights, display cutouts, keyboard/IME, and large font/accessibility settings.

Checklist:

- [ ] Replace `HomeScreen`'s hard-coded pinned top height and fade assumptions with measured layout height so the transaction list cannot hide under or detach from the pinned balance area at large text sizes or with extra unit/status rows.
- [ ] Add responsive constraints to `BalanceDisplay` and Home unit pager so large amounts, long unit codes, and received-delta labels do not overlap or resize the pinned header unpredictably.
- [ ] Make `LightningScreen` scrollable and navigation-bar aware; the quote check action and lower sections can be clipped on short screens today.
- [ ] Make `P2PKScreen` use a lazy or scrollable layout with bottom insets so long key lists and action buttons remain reachable.
- [ ] Make `ContactlessPayView` scrollable or vertically adaptive so NFC instructions/status/actions do not clip on compact devices or large text.
- [ ] Audit `UnifiedSendScreen` amount/confirm faces for keyboard and small-height clipping; add `verticalScroll`/`imePadding`/`bringIntoViewRequester` where destination fields or CTAs can be covered.
- [ ] Audit `SendEcashScreen` input face for keypad, P2PK lock fields, keyboard, and CTA clipping; ensure the generated-token face keeps copy/share/actions reachable above navigation bars.
- [ ] Audit `ReceiveLightningScreen` input face for amount keypad, method picker, keyboard, and CTA clipping; move to scrollable/adaptive composition if needed.
- [ ] Audit `ReceiveEcashScreen` paste/review faces for keyboard, long token text, locked-token metadata, and lower action clipping.
- [ ] Make QR display surfaces use responsive QR sizing instead of fixed sizes that can overflow narrow split-screen widths.
- [ ] Add bottom `navigationBarsPadding` or explicit safe-area spacers to pushed settings sub-screens such as Privacy, Backup, Nostr, P2PK, Lightning, and Mint Detail.
- [ ] Add `imePadding` and scroll support to all text-entry dialogs and sheets: Nostr relay add/import, P2PK import/generate labels, mint add/discovery filters, restore seed/mint input, and send destination entry.
- [ ] Add max-lines, overflow, and width constraints to `SettingsRows.NavRow`, `ToggleRow`, mint rows, history rows, QR detail rows, and public-key rows so long titles, trailing values, mint URLs, relay URLs, Lightning addresses, and P2PK keys cannot push controls off-screen.
- [ ] Verify segmented controls in Nostr and receive method pickers at large font sizes; labels should wrap or adapt instead of clipping.
- [ ] Keep modal bottom sheet content scrollable and inset-aware, especially currency picker, mint discovery, receive chooser, and method picker sheets.

Success condition:

- Core Android wallet screens remain fully usable at default and large font sizes on compact phone, large phone, split-screen narrow width, and dark theme, with no clipped primary actions or unreadable row content.

### Settings Rendering Performance And Jank

The settings area is a likely source of lag because the root screen collects a broad `SettingsState`, rebuilds many rows from one state object, and several sub-screens compose full vertical columns of dynamic data. Currency and relay/key sections also perform formatting or list work during composition.

Checklist:

- [ ] Profile Settings open/scroll/toggle paths with Macrobenchmark, JankStats, and Compose recomposition counts before changing behavior.
- [ ] Split `SettingsScreen` state observation into stable selectors or row models so toggling Sentry/NPC/Nostr/auto-paste does not recompose unrelated sections.
- [ ] Use lifecycle-aware state collection (`collectAsStateWithLifecycle`) across Compose screens to avoid off-screen collectors causing extra recomposition and work.
- [ ] Replace settings row rebuilding with immutable/stable row definitions where possible; use `remember` for static section content, icons, and expensive labels.
- [ ] Review the collapsing top app bar/nested scroll behavior on Settings. If it contributes to jank without providing value, simplify to a pinned Material top app bar.
- [ ] Optimize `CurrencyPickerSheet`: precompute currency display names/symbols/formatters, avoid rebuilding all row labels on every price tick, and use stable keys.
- [ ] Convert long or potentially long Settings sub-screen lists to `LazyColumn`, especially Nostr relays and P2PK keys, instead of composing the full list in a `verticalScroll` column.
- [ ] Optimize image loading in dense lists. `MintAvatar`/mint rows should avoid expensive subcomposition or repeated model creation during scroll where a simpler cached image path works.
- [ ] Profile Home list masking/fade drawing and animated QR generation; move QR bitmap generation off the main thread or add frame caching if animated UR/QR display causes missed frames.
- [ ] Add baseline profiles for app startup, opening Settings, switching tabs, opening Send/Receive, opening scanner, and scrolling Home/History/Mints/Settings.

Success condition:

- Settings opens and scrolls smoothly on a representative physical Android device, with no visible dropped frames when toggling settings, opening pickers, or returning from sub-screens.

### Interaction And State Bugs

Several Compose controls have behavior that can surprise users or produce duplicate actions.

Checklist:

- [ ] Fix `ToggleRow` semantics so tapping the switch and tapping the row cannot double-toggle or expose duplicate TalkBack actions; prefer one `toggleable` semantic owner.
- [ ] Add disabled/busy state to mint discovery add rows to prevent double-tapping the same discovered mint before wallet state catches up.
- [ ] Make the Add Mint nickname field actually persist/use the nickname or remove the field until backend support exists.
- [ ] Audit `SwipeToDismissBox` plus `combinedClickable` in `MintsScreen`; swiping should not also open mint detail, and long press should not conflict with delete affordances.
- [ ] Make retry quote behavior explicit in Unified Send. The current retry-by-resetting-selected-mint pattern can fail when the same mint remains selected.
- [ ] Add user-visible confirmation/feedback for copy/share actions that currently silently write to clipboard.
- [ ] Add safe external-link handling for explorer, contact, and support links so missing browser/activity handlers do not crash the app.
- [ ] Ensure receive-later token ids are stable and collision-resistant; avoid using only a token prefix for pending receive identity.
- [ ] Audit Receive Lightning polling/subscription effects to ensure only one active watcher exists per quote and watchers cancel on navigation/back.
- [ ] Add per-screen loading state instead of reusing broad wallet loading flags for unrelated buttons, especially mint add/discovery and settings toggles.

Success condition:

- UI controls have one clear action, busy states prevent duplicate work, retry paths are deterministic, and copy/share/external-link flows provide feedback or graceful error handling.

### UI Bug Regression Tests

Checklist:

- [ ] Add Compose tests for every custom back gesture listed above.
- [ ] Add large-font screenshot tests for Home, Unified Send, Send Ecash, Receive Ecash, Receive Lightning, Settings, Nostr, P2PK, Lightning, Mints, Mint Detail, and Transaction Detail.
- [ ] Add compact-height screenshot tests for amount entry/keypad screens and NFC/scanner overlays.
- [ ] Add tests that verify primary CTAs remain visible above keyboard and navigation bars.
- [ ] Add tests for settings toggle semantics, row overflow, mint swipe/delete/open behavior, and discovery double-tap prevention.
- [ ] Add performance benchmarks for Settings open/scroll/toggle and Home/History/Mints list scroll.

Success condition:

- The Android UI bug sweep is protected by automated tests and benchmarks so the same classes of regressions do not return while parity work continues.

## Milestone 12: Material UI, Accessibility, And Motion Polish

Goal: make Android feel as polished as iOS while remaining native Android.

Checklist:

- [ ] Complete the Android UI Bug Audit Backlog above before declaring visual polish complete.
- [ ] Use Material 3 top app bars, bottom navigation, modal bottom sheets, alert dialogs, segmented controls, chips, icon buttons, and pull-to-refresh where platform appropriate.
- [ ] Avoid copying Liquid Glass visuals directly; use Material tonal surfaces, elevation, ripple/indication, dynamic color where appropriate, and Android-native motion.
- [ ] Keep page sections on the bare canvas; avoid nested cards and marketing-style decoration.
- [ ] Define stable sizes for QR cards, keypads, icon buttons, amount heroes, row heights, and bottom actions to avoid layout jumps.
- [ ] Support large font sizes without clipped button labels or overlapped amount text.
- [ ] Add TalkBack labels/hints for balances, toggles, QR copy/share, scanner, NFC, destructive actions, key reveals, and transaction rows.
- [ ] Respect reduce-motion/animation scale settings where possible.
- [ ] Align haptics: selection on navigation/choice, success on completed scan/payment, warning/error on failures.
- [ ] Add dark theme and contrast review for all screens.
- [ ] Add screenshot checks for core screens on compact phone, large phone, and tablet-ish widths.

Success condition:

- Android achieves behavioral and UX parity through Material conventions, with accessible, stable, polished screens across common device sizes and themes.

## Milestone 13: Android Test Coverage Parity

Goal: bring Android's test coverage to the same confidence level as iOS.

Current Android test strengths:

- JVM tests exist for amount formatting, animated UR decoding, app logger, Bitcoin address validation, mint quote metadata/polling, Cashu Request listener, connectivity, haptics, history filters, home balance, mint discovery, mint URL input, mnemonic input, model parity, NIP44/NIP17, NPC, navigation deep links, Nostr, on-chain explorer, payment request builder/decoder, pending quote transactions, Sentry, secure storage, settings, token parser/history, transaction display, unit amount entry, wallet database recovery, and QR/platform actions.
- Instrumentation currently covers storage migration and secure storage deletion.

Major gaps:

- No broad Compose UI suite equivalent to `CashuWalletUITests`.
- No Android equivalent to CI/Nutshell integration tests.
- No instrumentation tests for onboarding restore, scanner, NFC, app lock, backup, or receive/send happy paths.
- No screenshot/accessibility regression suite.

Unit test checklist:

- [ ] Add `PaymentRequestBuilder` tests for NUT-10 payload and locked receive request parse.
- [ ] Add `CashuRequestStore` tests for update/regenerate, quote-intent upsert, attach by quote id, delete/reset/reload, and duplicate suppression.
- [ ] Add `WalletManager` tests for startup maintenance orchestration with fake gateway.
- [ ] Add tests for keyset refresh and incomplete saga recovery routing.
- [ ] Add tests for `addMintAndPayCashuRequest`, external top-up, mint settling, and fee estimation.
- [ ] Add send destination inference tests for amountless BOLT11/BOLT12, BIP-321 Cashu Request plus Lightning fallback, on-chain, Lightning address, and ecash token handoff.
- [ ] Add receive token tests for unknown mint, locked known primary P2PK key, locked unknown key, non-sat unit, receive later, and home event payload.
- [ ] Add receive Lightning tests for BOLT11 expiry, reusable BOLT12 offer reuse, on-chain address reuse/new address, quote-backed request store attachment.
- [ ] Add Mint Detail tests for NUT-06 mapping, contact URL mapping, method min/max ranges, and connection state.
- [ ] Add Settings tests for relay validation, storage-only toggle removal/implementation, Sentry opt-in, App Lock state.
- [ ] Add logging tests that reject raw seed/token/private-key strings in privacy-safe messages.

Compose UI and instrumentation checklist:

- [ ] Add `androidTest` Compose test harness with fake wallet/container dependencies.
- [ ] Home tests: balance toggle, unit pager, received delta, recent request/transaction row, empty state, scan/send/receive actions.
- [ ] Onboarding tests: create seed reveal/ack, first mint skip/add, restore method, staged mint restore progress/results.
- [ ] Send tests: unified input paste/scan route, amount entry, quote loading, mint switch, success/failure status, send ecash P2PK.
- [ ] Receive tests: paste token, review locked/unknown mint states, receive later, success/failure status, New Request edit/detail.
- [ ] Receive Lightning tests: method picker, BOLT11 invoice display/expiry, BOLT12 reusable offer, on-chain observer/link.
- [ ] History tests: filter/search, request deletion, transaction detail QR/share/copy, explorer link.
- [ ] Mints tests: add/paste/scan, discovery search/add, set active, remove, full detail metadata.
- [ ] Settings tests: App Lock, backup reveal auth, Nostr reveal auth, relay validation, P2PK key flows, privacy toggles, delete wallet.
- [ ] Scanner tests: permission denied/granted, animated UR progress, quick-fill routing, unsupported payload error.
- [ ] NFC instrumentation or Robolectric-adjacent tests for NDEF text/URI record read/write and routing.
- [ ] Accessibility tests for content descriptions on critical controls and large-font screenshots.

Integration checklist:

- [ ] Add an Android integration test target equivalent to `CI/IntegrationTests`.
- [ ] Run against local Nutshell/CDK test mints for mint, melt, restore, token parser, payment request parser, multi-unit, BOLT11, BOLT12, and on-chain where available.
- [ ] Add fake-gateway integration tests for no-network CI paths.
- [ ] Add CI jobs for JVM unit tests, instrumentation tests on managed devices, lint, and release build.

Success condition:

- Android has unit, UI, instrumentation, and integration coverage that exercises every milestone's success path and critical failure path, with CI gates preventing regressions.

## Milestone 14: Release Readiness And Manual Acceptance

Goal: define the final gate for declaring Android caught up.

Checklist:

- [ ] Run complete Android test suite: JVM, instrumentation, Compose UI, lint, release build.
- [ ] Run iOS tests to ensure shared product assumptions did not diverge.
- [ ] Perform manual parity walkthrough on physical Android device: onboarding, restore, backup/security, home, send, receive, scanner, NFC, mints, history, settings.
- [ ] Perform manual parity walkthrough on iOS after any shared model/protocol changes.
- [ ] Validate with at least one real mint supporting current CDK features, one BOLT11 path, one BOLT12 path, one on-chain path, one P2PK locked token, and one Cashu Request paid over Nostr.
- [ ] Verify no PII/secrets/tokens/seeds/private keys appear in logs, screenshots, crash reports, commits, PRs, or release notes.
- [ ] Verify Android release build preserves secure storage, backup policy, network security, app lock, and Sentry opt-in behavior.
- [ ] Update README/product docs with accurate Android feature coverage.

Success condition:

- Android can be described publicly as feature-complete with iOS for all current user-facing wallet features, with platform-native UI differences documented and covered by tests.

## Priority Order

1. Security/lifecycle and misleading settings cleanup.
2. Onboarding/restore and backup decisions.
3. Cashu Request store/backend parity.
4. P2PK/locked ecash protocol parity.
5. Send Cashu Request acquire/top-up and fee parity.
6. Receive Lightning/on-chain reusable quote parity.
7. Mint detail metadata parity.
8. Android UI bug sweep: back gestures, clipping/scroll/insets, settings jank, duplicate actions.
9. UI polish and accessibility pass across Home/History/Mints/Settings.
10. Full Android test parity and CI gates.

## Definition Of Done For Each Milestone

- The Android behavior matches iOS for the same user story, except where a documented Android platform convention intentionally differs.
- The Android UI uses Material 3-native patterns and passes TalkBack/large-text/dark-theme checks.
- The milestone has focused unit tests and, for user-facing flows, Compose/instrumentation tests.
- Secrets, tokens, seeds, private keys, local paths, usernames, and device identifiers are not logged or emitted into git/GitHub artifacts.
- Product docs and Android docs no longer overclaim or underdocument the implemented behavior.
