# Android Parity Tracker

Status date: 2026-07-07

`ANDROID_UPDATE_PLAN.md` is the active parity plan. This file is the working tracker for implementation PRs/commits, route ownership, and acceptance gates.

## Source Of Truth

- iOS app behavior: `CashuWallet/App`, `CashuWallet/Core`, `CashuWallet/Models`, and `CashuWallet/Views`.
- Shared product/design references: `PRODUCT.md`, `README.md`, `DESIGN.md`, `ICLOUD_RECOVERY.md`.
- Android implementation target: feature and UX parity with iOS, expressed through native Android and Material 3 patterns rather than iOS Liquid Glass controls.
- Android plan owner: `ANDROID_UPDATE_PLAN.md`.
- Android UI rules: `android/UI_ACCEPTANCE_RULES.md`.
- Android route inventory: `android/ROUTE_PARITY_INVENTORY.md`.

## Milestone Tracker

| Milestone | Area | Status | Implementation PR/commit | Acceptance evidence |
| --- | --- | --- | --- | --- |
| 0 | Source of truth and parity guardrails | Complete | `afa21c7` | Tracker/docs exist, stale parity notes are corrected, route inventory exists, misleading settings are hidden or relabeled, fixtures exist. |
| 1 | Security, App Lock, Backup, and Wallet Lifecycle | Complete | Milestone 1 commit on `codex/android-update-plan-implementation` | App Lock, authenticated secret reveal, documented no-cloud backup MVP, startup maintenance, stale quote sync, security tests. |
| 2 | Onboarding and Restore Parity | Complete | Milestone 2 commit on `codex/android-update-plan-implementation` | iOS-equivalent create/restore/no-cloud placeholder/staged mint restore flows, row-level add/restore progress, retry, and focused Gradle validation. |
| 3 | Home, Shell, and Foreground Behavior | Complete | Milestone 3 commit on `codex/android-update-plan-implementation` | Received-delta event stream/chip, sat-only gating, foreground quote metadata, recents dedupe, unit pager tests, and TalkBack labels. |
| 4 | Unified Send, Pay Flows, and Contactless | Complete | Milestone 4 commit on `codex/android-update-plan-implementation` | Cashu Request route model, add-mint/top-up recovery, amountless/non-sat notices, P2PK scan quick-fill/chip, NFC route parity, focused tests. |
| 5 | Receive Ecash and Cashu Request Parity | Complete | Milestone 5 commit on `codex/android-update-plan-implementation` | Shared receive status, stable pending ids, unknown/locked token review, editable NUT-18 requests, quote-backed receive intents, focused tests. |
| 6 | Receive Lightning, BOLT12, and On-chain | Complete | Milestone 6 commit on `codex/android-update-plan-implementation` | Material method picker, BOLT11 expiry, reusable BOLT12 reuse/editing, on-chain reuse/new address/explorer, shared status, focused tests. |
| 7 | Locked Ecash and P2PK | Complete | Milestone 7 commit on `codex/android-update-plan-implementation` | Seed-derived primary key, NUT-10/NUT-18 requests, locked receive, authenticated key reveal, focused tests. |
| 8 | Mints and Mint Metadata | Complete | Milestone 8 commit on `codex/android-update-plan-implementation` | Full NUT-06 mint detail, discovery polish, refresh behavior, rich metadata model, focused tests. |
| 9 | History and Transaction Detail | Complete | Milestone 9 commit on `codex/android-update-plan-implementation` | Stale sync, swipe/delete, row/detail parity, large-ledger behavior, focused tests. |
| 10 | Settings, Integrations, and Privacy | Complete | Milestone 10 commit on `codex/android-update-plan-implementation` | App Lock/no-cloud backup decision, accurate privacy toggles, authenticated secrets, Nostr/Lightning parity, focused tests. |
| 11 | Protocol, CDK, Storage, and Runtime Hardening | Not started | TBD | Latest CDK feature parity, sagas, keysets, multi-unit, privacy-safe errors/logging, tests. |
| UI bug sweep | Android back/layout/jank/interaction bugs | Not started | TBD | Back gesture tests, large-font screenshots, settings performance benchmark, duplicate-action tests. |
| 12 | Material UI, Accessibility, and Motion Polish | Not started | TBD | Material polish, TalkBack, dark/contrast, reduce motion, screenshot checks. |
| 13 | Android Test Coverage Parity | Not started | TBD | JVM, Compose UI, instrumentation, integration, accessibility, screenshot, and CI coverage. |
| 14 | Release Readiness and Manual Acceptance | Not started | TBD | Full manual parity pass, live mint/CDK validation, release build, docs. |

## PR Discipline

- One milestone can span multiple commits, but the branch must have a clear commit boundary before starting the next milestone.
- Each milestone commit must include any `ANDROID_UPDATE_PLAN.md` checkbox updates that are backed by code/docs/tests in the same commit.
- Commit messages and PR descriptions must not include local paths, device identifiers, seeds, tokens, private keys, or other personal/user data.
- Claims such as "parity complete" require the acceptance evidence listed above, not just implementation files.

## Current Milestone 0 Evidence

- Source-of-truth rules: this file and `android/UI_ACCEPTANCE_RULES.md`.
- Route ownership: `android/ROUTE_PARITY_INVENTORY.md`.
- Stale migration-plan cleanup: the outdated Kotlin migration plan was removed so `ANDROID_UPDATE_PLAN.md` is the single authoritative Android parity plan.
- Misleading settings cleanup: Privacy settings copy now scopes startup checks to sent-token claim checks.
- Preview/test fixtures: `PreviewWalletFixtures` provides deterministic wallet, settings, mint, history, and request data.

## Current Milestone 1 Evidence

- App Lock state and manager: `AppLockManager`.
- Full-screen gate and privacy behavior: `AppLockGate`, `PrivacyCover`, `SecureWindowEffect`, and shell lifecycle wiring.
- App Lock setting: `settings.appLockEnabled` and Settings → Backup & Security row.
- Secret reveal authentication: `BackupScreen` and `NostrScreen` use the shared wallet authentication launcher before reveal/copy.
- Backup product decision: `android/SECURITY_BACKUP_MODEL.md` keeps Android cloud seed backup hidden until a restorable encryption/account model exists.
- Startup maintenance: `WalletManager.performBestEffortWalletStartupMaintenance`, `performForegroundMaintenance`, and `syncPendingMintQuotesIfStale`.
- Logging audit: `AppLogger` redaction coverage expanded and Sentry breadcrumbs are sanitized.

## Current Milestone 2 Evidence

- Onboarding state machine: `OnboardingScreen` now uses welcome, seed reveal, first mint, first-mint progress, restore method, cloud restore placeholder, restore input, restore mints, and restore progress steps.
- Create flow: seed phrase reveal requires acknowledgement before continuing; the old mnemonic quiz was removed.
- First mint setup: recommended mint multi-select, pasted/custom mint candidates, skip, and row-level add progress/results with retry.
- Restore flow: restore method chooser, no-cloud Android backup placeholder, mnemonic validation through `MnemonicInput`, staged mint paste parsing, preview metadata/avatar loading, reorder/remove, and row-level restore progress/results with retry.
- Settings restore ergonomics: restore launched from an existing wallet can be exited back to the wallet.
- Privacy-safe telemetry: onboarding emits only generic opt-in Sentry breadcrumbs via `SentryService`.

## Current Milestone 3 Evidence

- Receive events: `WalletReceiveEvent` and `WalletManager.receiveEvents` cover direct ecash, quote minting, pending quote sync, Cashu Request claims, and NPC claims.
- Home delta: `HomeScreen` shows a short Material received chip only for positive sat events and exposes a polite live-region announcement.
- Foreground maintenance: pending quote sync now returns event metadata while preserving existing minted-count behavior.
- Timeline parity: Home recent rows continue to suppress Cashu Request-attached transactions; JVM coverage locks this behavior.
- Unit pager parity: `HomeBalanceTest` verifies the active-mint and held non-sat balance gate.
- Accessibility: active mint, balance toggle, Receive, Send, Scan, unit pager dots, transaction rows, and Cashu Request rows expose explicit semantic descriptions.

## Current Milestone 4 Evidence

- Cashu Request routing: `CashuPaymentRequestRoute` distinguishes pay-with-ecash, BOLT11 fallback, add requested mint, target-mint top-up, unsupported unit, and missing amount.
- Unified Send recovery: Cashu Request confirm surfaces route rows, add-mint bottom sheet, top-up QR sheet, amountless BOLT11/BOLT12 warnings, non-sat unsupported notices, and compatible-mint recovery CTAs.
- Wallet operations: `WalletManager` exposes target-mint quote creation and add-mint-then-pay helpers.
- Send Ecash P2PK: dedicated scanner target, scanned-key quick-fill, and locked-key chip sit above the raw public-key field.
- Contactless: NFC preparation uses the shared route model and keeps Lightning fallback handoff to Unified Send.
- Tests: focused Gradle coverage includes Cashu Request routing, NFC decoding, and P2PK scan normalization.

## Current Milestone 5 Evidence

- Receive ecash status: receive token now uses the shared `PaymentStatusScreen`, minimum processing dwell, and system back handling for review/status states.
- Receive token review: unknown mints show a caution; P2PK locked tokens distinguish stored "Your key" from unknown keys and block unknown-key receive.
- Receive later: pending receive ids are SHA-256 hashes of the full normalized token, with JVM collision regression coverage.
- New Request: Android now creates any-mint NUT-18 requests by default, matching iOS's `mints: []` behavior.
- Cashu Request store: `CashuRequestStore` supports upsert, update, quote-intent upsert, attach-by-quote, reload, and reset.
- Request detail: NUT-18 request rows for amount, mint, unit, and memo are editable through Material dialogs/sheets and regenerate the encoded payload with the same request id.
- Quote intents: BOLT11, BOLT12, and on-chain receive quotes are stored as quote-backed request rows, labeled in Home/History, and attach payment by quote id after minting succeeds.
- Tests: focused Gradle coverage includes payment request encoding/model behavior, pending receive id hashing, and quote-intent row titles.

## Current Milestone 6 Evidence

- Method picker: Receive Lightning now uses a Material bottom-sheet picker for Lightning invoice, reusable invoice, and on-chain address options.
- Reusable BOLT12: amountless reusable invoices are auto-created/reused, use "Reusable invoice" copy, and expose an editable amount row that creates a fresh fixed-amount reusable invoice.
- On-chain receive: existing on-chain quotes are reused, "Use new address" forces a fresh address, and the display includes block explorer address/transaction links when available.
- Quote lifecycle: BOLT11 expiry countdown/expired state, quote polling policy, on-chain observation, and paid/issued quote handling feed the shared `PaymentStatusScreen`.
- Wallet operations: `WalletManager` can find existing active amountless BOLT12 and active on-chain mint quotes before creating duplicates.
- Tests: focused Gradle coverage includes mint quote domain/polling policy, pending quote history rows, and Home quote-intent duplicate suppression.

## Current Milestone 7 Evidence

- NUT-10 request encoding: `PaymentRequestBuilder` writes `nut10` P2PK data into NUT-18 `creqA` requests and `PaymentRequestDecoder` has a structured fallback summary decoder for extension-bearing requests.
- Locked receive: `LockedReceiveRequest` builds Nostr-routed requests locked to the seed-derived primary P2PK key, and `ReceiveLockedEcashScreen` exposes QR/copy/share/regenerate actions.
- Primary key model: `SettingsManager` exposes primary P2PK public/private/nsec helpers from the active Nostr identity and includes available local P2PK keys in signing lookups.
- Known-key receive: receive-token review checks `isKnownP2PKPublicKey`, so primary-key locked tokens show "Your key" and unknown-key tokens remain blocked.
- P2PK settings: Locked Ecash settings now use lazy Material sections for Your key, Quick lock, Advanced keys, detail QR/copy, private reveal/copy behind authentication, rename/remove, import, and explainer copy.
- Send quick fills: Send Ecash can quick-fill "your key" when enabled and can quick-fill a copied public key from the clipboard.
- Tests: focused Gradle coverage includes locked receive NUT-10 bytes/summary parsing, P2PK normalization, and send-side P2PK scan normalization.

## Current Milestone 8 Evidence

- Mint metadata model: Android `MintInfo` now stores NUT-06 pubkey, version, long description, contacts, endpoint URLs, MOTD, ToS, NUT support flags, and mint/melt method range settings.
- Metadata fetching: CDK `fetchFullMintInfo` and raw `/v1/info` fallback populate the richer model; `WalletManager.refreshMintInfo` and `refreshAllMintInfo` refresh safely while preserving local balances.
- Mints tab: opening the tab refreshes mint info and exposes a top-bar refresh action.
- Mint detail: renders checking/online/offline state, About/read-more/MOTD, capabilities, NUT support, method min/max rows, contacts as Android intents or copy fallback, software/ToS, top share, and URL copy feedback.
- Multi-unit safety: Mint Detail reads non-sat unit balances with `unitBalanceIfExists` so detail inspection does not create/register unit wallets.
- Discovery: search, Added/Discovered sections, pull and explicit refresh, error notice, session-added state, and WebSockets-disabled empty state are all present.
- Tests: focused Gradle coverage includes rich mint model defaults, discovery parsing/state tests, and currency formatting/registry coverage.

## Current Milestone 9 Evidence

- History entry: opening History loads transactions immediately and runs throttled stale pending mint-quote sync through `syncPendingMintQuotesIfStale`.
- Large ledgers: the merged transaction/request timeline renders in 30-row windows, resets on filter/search changes, and prefetches the next window near the visible end with a fallback Show More action.
- Request deletion: Cashu Request rows support Material end-to-start swipe remove plus the existing long-press path, both feeding the same confirmation dialog.
- Empty/search parity: search remains visible when there are no results, request search includes received amount, and empty copy now matches iOS: No Results, Nothing Here, No History Yet.
- Transaction detail: canonical rows now include Payment Proof for non-on-chain preimages, drop Android-only Memo, and keep on-chain addresses QR/share/copy-capable after settlement.
- Tests: focused Gradle coverage includes duplicate suppression, request received-amount search, date buckets, visible windowing, QR/share/copy rules, and explorer URL generation.

## Current Milestone 10 Evidence

- Settings root: section order matches iOS and exposes App Lock, Backup & Restore, Lightning, Locked Ecash, Nostr, Privacy, About, and Danger while intentionally omitting cloud backup until a real Android product exists.
- Backup & Restore: restore copy now opens the staged restore wizard from Milestone 2 and warns that restoring can replace local wallet data; seed reveal/copy remains authenticated.
- Nostr: settings include active key status, signer switching, public identity rows, authenticated nsec reveal sheet/copy, generate/import/reset confirmations, relay add/remove/reset, and `ws://` / `wss://` validation with dedupe.
- Privacy: dependent toggles are disabled when their runtime path is off; inert payment-request toggles remain hidden; Sentry copy is explicit, opt-in, and excludes secrets/tokens/invoices/addresses/amounts/screenshots/view hierarchy.
- Lightning Address: Android now follows the iOS enable/address/preferences/check structure, hides operational rows until enabled and initialized, labels the receiving mint, and shows last-check status.
- Danger: delete wallet confirmation explains local wallet/mint/request/Nostr/P2PK deletion and the lack of Android cloud backup.
- Tests: focused Gradle coverage includes settings relay validation, Nostr service, NPC service, and Sentry service behavior.
