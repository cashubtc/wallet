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
| 2 | Onboarding and Restore Parity | Not started | TBD | iOS-equivalent create/restore/cloud/staged mint restore flows and UI/instrumentation tests. |
| 3 | Home, Shell, and Foreground Behavior | Not started | TBD | Home parity, received-delta beat, foreground sync, scanner/contactless shell correctness, UI tests. |
| 4 | Unified Send, Pay Flows, and Contactless | Not started | TBD | Full Cashu Request pay/acquire/top-up, richer status/error flows, NFC parity, tests. |
| 5 | Receive Ecash and Cashu Request Parity | Not started | TBD | Editable requests, quote-backed intents, locked/unknown mint handling, status parity, tests. |
| 6 | Receive Lightning, BOLT12, and On-chain | Not started | TBD | BOLT11 expiry, reusable BOLT12, on-chain address reuse/observer, quote-backed history, tests. |
| 7 | Locked Ecash and P2PK | Not started | TBD | Seed-derived primary key, NUT-10/NUT-18 requests, locked receive, authenticated key reveal, tests. |
| 8 | Mints and Mint Metadata | Not started | TBD | Full NUT-06 mint detail, discovery polish, refresh behavior, nickname handling, tests. |
| 9 | History and Transaction Detail | Not started | TBD | Stale sync, swipe/delete, row/detail parity, large-ledger behavior, tests. |
| 10 | Settings, Integrations, and Privacy | Not started | TBD | App Lock/cloud backup settings, accurate privacy toggles, Nostr/P2PK/Lightning parity, tests. |
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
