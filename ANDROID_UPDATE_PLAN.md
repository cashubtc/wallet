# Android Update Plan

Date: 2026-07-07

This plan compares the current Android implementation against the iOS wallet, treating iOS as the target for feature, payment, UI, UX, and test parity. Android should not copy iOS Liquid Glass controls directly. It should deliver equivalent behavior through native Android and Material 3 patterns: top app bars, bottom navigation, modal bottom sheets, dialogs, snackbars/inline notices, BiometricPrompt, Android NFC, CameraX, and TalkBack-friendly semantics.

## Project Compatibility Baseline

The Android wallet has not shipped publicly, and this repository is still treated as a new unreleased product. Backward compatibility with old Android-only feature behavior is not required. Data migrations for stale Android models, settings, route names, onboarding states, local database shapes, cached quote records, or experimental feature flags are not required unless a specific migration is later requested.

Implementation should therefore prefer clean iOS-parity behavior over preserving legacy Android behavior. When an old Android flow conflicts with the iOS target, replace it directly, delete obsolete code, and simplify tests around the new expected behavior. Security-sensitive changes may invalidate old local app data if that is the cleanest unreleased-product path.

## Audit Scope

The audit covered the product docs, design docs, iOS app, iOS unit/UI/integration tests, Android app, Android unit/instrumented tests, and Android migration/design notes. Build outputs and generated Gradle/Xcode artifacts are excluded.

Primary source areas reviewed:

- Product and design docs: `PRODUCT.md`, `README.md`, `DESIGN.md`, `ICLOUD_RECOVERY.md`, `android/README.md`, `android/UX_SPEC.md`, `android/DESIGN-ANDROID.md`, `android/UX_MAPPING.md`.
- iOS app: `CashuWallet/App`, `CashuWallet/Core`, `CashuWallet/Models`, `CashuWallet/Views`.
- iOS tests: `CashuWalletTests`, `CashuWalletUITests`, `CI/IntegrationTests`.
- Android app: `android/app/src/main/java/org/cashu/wallet`, `android/app/src/main/res`.
- Android tests: `android/app/src/test`, `android/app/src/androidTest`.

Repo inventory found 312 Swift/Kotlin/XML files across those app and test scopes. The Android codebase already has a broad Compose shell, payment parser, wallet manager, history timeline, mints, settings, send/receive surfaces, NFC, scanner, Nostr/NPC, Sentry opt-in, multi-unit models, and many JVM unit tests. The remaining work is not a blank rewrite. It is a parity completion pass across security, restore, advanced Cashu Request flows, locked ecash, Lightning/on-chain receive polish, mint metadata, and UI/instrumentation coverage.

## Android Gradle Validation Commands

Run Android commands from `android/`.

If `java` is not available on the shell path but Android Studio is installed, use Android Studio's bundled JBR without hard-coding a user-specific path:

```sh
ANDROID_STUDIO_APP="$(mdfind "kMDItemCFBundleIdentifier == 'com.google.android.studio'" | head -n 1)"
if [ -z "$ANDROID_STUDIO_APP" ]; then ANDROID_STUDIO_APP="/Applications/Android Studio.app"; fi
export JAVA_HOME="$ANDROID_STUDIO_APP/Contents/jbr/Contents/Home"
```

Core validation commands:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:assembleDebug
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:lintDebug
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:assembleRelease
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugAndroidTestKotlin
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:pixel2Api35DebugAndroidTest
```

Focused test command used during Milestone 1:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest --tests org.cashu.wallet.Core.AppLoggerTest --tests org.cashu.wallet.Core.SentryServiceTest --tests org.cashu.wallet.Core.SettingsManagerTest --tests org.cashu.wallet.ui.preview.PreviewWalletFixturesTest
```

Focused validation used during Milestone 2:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest --tests org.cashu.wallet.Core.MnemonicInputTest --tests org.cashu.wallet.Core.MintUrlInputTest --tests org.cashu.wallet.Core.SentryServiceTest
```

Focused validation used during Milestone 3:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest --tests org.cashu.wallet.Core.HomeBalanceTest --tests org.cashu.wallet.Core.WalletReceiveEventTest --tests org.cashu.wallet.ui.home.HomeRecentTest
```

Focused validation used during Milestone 4:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest --tests org.cashu.wallet.Core.CashuPaymentRequestMintSelectorTest --tests org.cashu.wallet.Core.Services.NFCPaymentInputDecoderTest --tests org.cashu.wallet.ui.send.SendEcashP2PKTest
```

Focused validation used during Milestone 5:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest --tests org.cashu.wallet.Core.PaymentRequestBuilderTest --tests org.cashu.wallet.Core.PendingReceiveTokenIdsTest --tests org.cashu.wallet.ui.home.HomeRecentTest
```

Focused validation used during Milestone 6:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest --tests org.cashu.wallet.Core.MintQuoteDomainTest --tests org.cashu.wallet.Core.MintQuotePollingPolicyTest --tests org.cashu.wallet.Core.PendingMintQuoteTransactionsTest --tests org.cashu.wallet.ui.home.HomeRecentTest
```

Focused validation used during Milestone 7:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest --tests org.cashu.wallet.Core.PaymentRequestBuilderTest --tests org.cashu.wallet.Core.SettingsManagerTest --tests org.cashu.wallet.ui.send.SendEcashP2PKTest
```

Focused validation used during Milestone 8:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest --tests org.cashu.wallet.Core.MintInfoUnitsTest --tests org.cashu.wallet.Core.MintDiscoveryManagerTest --tests org.cashu.wallet.Core.CurrencyProtocolTest
```

Focused validation used during the UI/performance bug sweep:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest
```

Focused validation used during Mint Detail and Receive Lightning watcher hardening:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.Core.MintDetailDisplayTest --tests org.cashu.wallet.Core.MintQuotePollingPolicyTest
```

Focused validation used during Settings selector/row-model refactoring:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin
```

Focused validation used during Android no-network integration target setup:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest --tests org.cashu.wallet.integration.NoNetworkFakeGatewayIntegrationTest :app:androidNoNetworkIntegrationTest
```

Focused validation used during bottom-sheet and segmented-label polish:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin
```

Focused validation used during reusable receive-quote selection tests:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.Core.MintQuoteReuseTest
```

Focused validation used while adding the Android Compose instrumentation harness:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugAndroidTestKotlin
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:help --task :app:pixel2Api35DebugAndroidTest
```

Managed-device validation attempt:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:pixel2Api35DebugAndroidTest
```

Current local blocker: Gradle reached `:app:pixel2Api35Setup`, installed the API 35 ARM64 system image, then failed to create the emulator snapshot because Android emulator 36.6.11 rejects the managed-device GPU option `auto-no-window` (`Selected GPU option 'auto-no-window' is not valid`). A connected API 36 emulator can run instrumentation packaging, but Compose tests currently fail before app assertions with `No compose hierarchies found in the app` even for existing component suites. The AndroidX Test stack has been updated to Espresso 3.7.0 / runner 1.7.0 / AndroidX JUnit 1.3.0 so the prior API 36 `InputManager.getInstance` crash is removed; the remaining blocker is the Compose host attachment on the local emulator. Keep the managed-device/manual instrumentation gate open until the emulator/AGP GPU mismatch and connected-emulator Compose host issue are resolved, a physical Android device is attached, or CI runs the managed-device job on a compatible host.

Focused validation used while adding receive-token review coverage:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.ui.receive.ReceiveEcashReviewTest --tests org.cashu.wallet.Core.PendingReceiveTokenIdsTest --tests org.cashu.wallet.Core.WalletReceiveEventTest
```

Focused validation used while extracting send destination inference:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.ui.send.SendDestinationResolverTest
```

Focused validation used while extracting App Lock policy coverage:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.Core.AppLockPolicyTest
```

Focused validation used for NFC/NDEF coverage:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest --tests org.cashu.wallet.Core.Services.NDEFTextRecordCoderTest --tests org.cashu.wallet.Core.Services.NFCPaymentInputDecoderTest
```

Focused validation used for Android release configuration coverage:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest --tests org.cashu.wallet.App.AndroidReleaseConfigurationTest
```

Focused validation used while extracting receive Lightning quote-flow coverage:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.ui.receive.ReceiveLightningQuoteFlowTest
```

Focused validation used while extracting WalletManager startup maintenance coverage:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.Core.WalletStartupMaintenanceTest
```

Focused validation used while covering keyset refresh and orphaned saga reservation routing:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.Core.CDK.MintQuoteCdkMetadataTest
```

Focused validation used while covering Cashu Request payment paths, top-up, mint settling, and fee estimation:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.Core.WalletCashuRequestPaymentTest --tests org.cashu.wallet.ui.send.SendCashuRequestTopUpTest --tests org.cashu.wallet.Core.WalletMintQuoteSettlementPolicyTest --tests org.cashu.wallet.Core.CDK.ReceiveFeeEstimatorTest
```

Focused validation used while extracting Mint Detail content rendering and connection-state coverage:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.ui.mints.MintDetailScreenTest :app:compileDebugAndroidTestKotlin
```

Focused validation used while extracting Settings runtime-toggle behavior coverage:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.Core.WalletForegroundMaintenanceTest
```

Focused validation used while adding accessibility, visual-regression probes, JankStats, and Macrobenchmark coverage:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugAndroidTestKotlin
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :macrobenchmark:compileDebugKotlin :app:compileDebugAndroidTestKotlin
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :macrobenchmark:compileDebugKotlin
```

Focused validation used while adding reduced-motion, haptic alignment, and Material UI policy coverage:

```sh
JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.ui.components.MotionPreferencesTest --tests org.cashu.wallet.Core.HapticFeedbackTest --tests org.cashu.wallet.ui.MaterialUiPolicyTest
```

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

iOS implementation reference files for this milestone:

- App/shell ownership and route inventory: `CashuWallet/App/CashuWalletApp.swift`, `CashuWallet/App/ContentView.swift`, `CashuWallet/Views/Main/MainWalletView.swift`.
- Source screen counterparts for route mapping: `CashuWallet/Views/Main/OnboardingView.swift`, `CashuWallet/Views/Send/SendView.swift`, `CashuWallet/Views/Receive/ReceiveView.swift`, `CashuWallet/Views/Receive/ReceiveTokenDetailView.swift`, `CashuWallet/Views/Receive/ReceiveLightningView.swift`, `CashuWallet/Views/Receive/CashuRequestDetailView.swift`, `CashuWallet/Views/History/HistoryView.swift`, `CashuWallet/Views/History/TransactionDetailView.swift`, `CashuWallet/Views/Mints/MintsListView.swift`, `CashuWallet/Views/Mints/MintDetailView.swift`, `CashuWallet/Views/Settings/SettingsView.swift`.
- Runtime-backed settings reference: `CashuWallet/Core/SettingsManager.swift`, `CashuWallet/Core/SettingsStore.swift`, `CashuWallet/Views/Settings/PrivacySettingsSection.swift`, `CashuWallet/Views/Settings/SettingsView.swift`.
- Preview/test behavior references: `CashuWalletUITests/UITestBase.swift`, `CashuWalletUITests/MainTabUITests.swift`, `CashuWalletUITests/SettingsUITests.swift`, `CashuWalletUITests/ReceiveUITests.swift`, `CashuWalletUITests/WalletIntegrationTests.swift`, `CI/IntegrationTests/Tests/IntegrationTestBase.swift`.

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

iOS implementation reference files for this milestone:

- App lock, lifecycle relock, privacy cover, and shell gating: `CashuWallet/App/ContentView.swift`, `CashuWallet/App/CashuWalletApp.swift`.
- Backup, seed handling, iCloud state, backup status, backup/restore settings copy, and restore constraints: `CashuWallet/Core/Wallet/WalletManager+Backup.swift`, `CashuWallet/Core/KeychainService.swift`, `CashuWallet/Views/Settings/BackupSettingsSection.swift`, `CashuWallet/CashuWallet.entitlements`, `ICLOUD_RECOVERY.md`.
- Authenticated Nostr/private-key reveal and relay/key-state behavior: `CashuWallet/Views/Settings/NostrSettingsSection.swift`, `CashuWallet/Core/NostrService.swift`, `CashuWallet/Core/NIP44.swift`, `CashuWallet/Core/NIP17.swift`, `CashuWallet/Core/SettingsManager.swift`.
- P2PK private-key reveal expectations for the later P2PK milestone: `CashuWallet/Views/Settings/P2PKSettingsSection.swift`, `CashuWallet/Core/SettingsManager.swift`, `CashuWallet/Core/Services/TokenService.swift`.
- Startup maintenance, wallet boundary cleanup, keyset/mint preparation, stale quote sync, pending receive/sent-token recovery, and delete-wallet safety: `CashuWallet/Core/Wallet/WalletManager.swift`, `CashuWallet/Core/Wallet/WalletManager+Lifecycle.swift`, `CashuWallet/Core/Wallet/WalletManager+MintQuoteSync.swift`, `CashuWallet/Core/Wallet/WalletManager+Mints.swift`, `CashuWallet/Core/Wallet/WalletManager+Tokens.swift`, `CashuWallet/Core/WalletStore.swift`, `CashuWallet/Core/CashuRequestStore.swift`, `CashuWallet/Core/CashuRequestListener.swift`.
- Privacy-safe logging, Sentry opt-in, and user-facing error mapping: `CashuWallet/Core/AppLogger.swift`, `CashuWallet/Core/SentryService.swift`, `CashuWallet/Core/Wallet/WalletErrors.swift`, `CashuWallet/Views/Settings/PrivacySettingsSection.swift`.
- Tests to mirror or extend on Android: `CashuWalletTests/WalletStoreTests.swift`, `CashuWalletTests/NIP44Tests.swift`, `CashuWalletUITests/SettingsUITests.swift`, `CashuWalletUITests/WalletIntegrationTests.swift`.

Android gaps:

- No App Lock, BiometricPrompt/device credential gate, or privacy cover.
- Seed and Nostr private key can be revealed/copied without authentication.
- Android manifest currently sets `android:allowBackup="false"` while backup XML files exist, so there is no product-level cloud backup equivalent.
- `WalletManager.initialize()` opens the wallet and loads cached state but does not match iOS startup maintenance for incomplete sagas/keysets/stale quotes.
- Logging can include full mint URLs in some paths; privacy-safe logging should be audited before release.

Checklist:

- [x] Add `AppLockManager` equivalent using `BiometricPrompt` with `DEVICE_CREDENTIAL` fallback. Implemented with AndroidX BiometricPrompt and `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`.
- [x] Add settings state for app lock enablement and a Material settings row under Backup & Security. `settings.appLockEnabled` is persisted and surfaced in `SettingsScreen`.
- [x] Add a full-screen lock gate in `CashuApp` after onboarding and before `WalletScaffold`. `AppLockGate` covers the authenticated shell and shell overlays.
- [x] Add lifecycle handling to obscure balances on `ON_PAUSE`/`ON_STOP` and relock after the same grace period as iOS, adjusted for Android lifecycle norms. Android uses the same 30-second grace window.
- [x] Use `WindowManager.LayoutParams.FLAG_SECURE` or a dedicated privacy overlay according to the chosen Android UX; document the choice. Android uses `FLAG_SECURE` while App Lock is enabled plus a Compose privacy cover; documented in `android/SECURITY_BACKUP_MODEL.md`.
- [x] Require authentication before seed reveal/copy in `BackupScreen`.
- [x] Require authentication before Nostr nsec reveal/copy in `NostrScreen`.
- [x] Require authentication before any P2PK private key reveal/copy once P2PK backups land. No P2PK private-key reveal/copy UI exists yet; the shared authentication path and documented requirement now exist for the Milestone 7 P2PK backup UI.
- [x] Decide Android cloud backup product model: encrypted Google Drive app-data, Android Auto Backup with app-side encryption, Google Block Store, or explicit no-cloud MVP. Milestone 1 chooses explicit no-cloud MVP because current device-bound Keystore ciphertext is not safely restorable on another device.
- [x] If cloud backup is implemented, store seed and mint list encrypted with a key protected by Android Keystore and document restore constraints. Cloud backup is intentionally not implemented in Milestone 1; restore constraints and future acceptable designs are documented in `android/SECURITY_BACKUP_MODEL.md`.
- [x] Add cloud backup settings, last-backup status, enable/disable confirmation, backup now, clear backup, and restore-from-cloud entry points. Cloud backup is intentionally hidden for Milestone 1 so no user-facing setting promises unavailable backup behavior.
- [x] Add startup maintenance matching iOS: recover incomplete CDK sagas, refresh keysets for tracked mints, load cached state, refresh balance, load transactions, and sync pending mint quotes if stale. Android now runs best-effort wallet preparation for tracked mints/units, balance refresh, transaction load, sent-token checks, and stale mint-quote sync during startup.
- [x] Run stale pending-quote sync on app foreground and when History opens. Foreground maintenance now runs stale mint-quote sync; History already refreshes pending mint quotes on entry.
- [x] Audit logs and Sentry breadcrumbs for raw token, seed, private key, local path, full URL, and user/device identifiers. `AppLogger` now redacts Cashu tokens, nsec values, labeled secrets, URLs, and local paths, and Sentry breadcrumbs pass through the sanitizer.

Success condition:

- A returning Android user with App Lock enabled cannot view wallet content in-app or in the app switcher without authentication; secrets require authentication; startup recovers pending wallet state as iOS does; backup behavior is intentionally implemented or intentionally hidden.

## Milestone 2: Onboarding And Restore Parity

Goal: replace the older Android onboarding with the current iOS restore/create model.

iOS reference:

- Create flow: welcome, generated seed reveal, acknowledgement, first mint selection, then wallet.
- Restore flow: restore method, seed input, staged mint restore, progress, per-mint results, and iCloud restore.
- First mint supports recommended mints, custom URLs, staged rows, skip, previews, and restore progress.

iOS implementation reference files for this milestone:

- Create, seed reveal, acknowledgement, restore-method choice, iCloud placeholder/restore, first mint, staged mint restore, retry, and settings-launched restore flow: `CashuWallet/Views/Main/OnboardingView.swift`.
- Seed phrase generation/validation and word-list behavior: `CashuWallet/Core/BIP39WordList.swift`, `CashuWallet/Core/Wallet/WalletManager+Lifecycle.swift`.
- iCloud restore source of truth and seed/mint restore mechanics: `CashuWallet/Core/Wallet/WalletManager+Backup.swift`, `CashuWallet/Core/KeychainService.swift`, `ICLOUD_RECOVERY.md`.
- Per-mint restore domain model and NUT-09 restore result semantics: `CashuWallet/Models/WalletSupport/RestoreMintResult.swift`, `CashuWallet/Core/Wallet/WalletManager+Lifecycle.swift`.
- First-mint add/preview/discovery support: `CashuWallet/Core/Wallet/WalletManager+Mints.swift`, `CashuWallet/Core/MintDiscoveryManager.swift`, `CashuWallet/Models/Mints/MintInfo.swift`, `CashuWallet/Views/Mints/MintDiscoverySheet.swift`.
- Privacy-safe onboarding telemetry/error references: `CashuWallet/Core/SentryService.swift`, `CashuWallet/Core/AppLogger.swift`, `CashuWallet/Core/Wallet/WalletErrors.swift`.
- Tests to mirror or extend on Android: `CashuWalletUITests/WalletIntegrationTests.swift`, `CashuWalletUITests/UITestBase.swift`, `CI/IntegrationTests/Tests/NutshellIntegrationTests.swift`, `CashuWalletTests/WalletStoreTests.swift`.

Android gaps:

- Android still includes a mnemonic quiz step that iOS no longer uses.
- First mint is older and less capable than iOS; it lacks staged multiple mints and richer restore results.
- Restore input does not offer cloud restore, staged mint URLs, per-mint progress, retry, or settings restore parity.

Checklist:

- [x] Redesign `OnboardingScreen` state machine to match iOS steps: welcome, show mnemonic, first mint, restore method, restore input, restore mints, restore progress, cloud restore. Implemented direct replacement flow with a no-cloud Android backup placeholder matching the Milestone 1 backup decision.
- [x] Remove or demote the mnemonic quiz unless a deliberate Android-only product decision keeps it. The quiz step and unreachable quiz UI were removed.
- [x] Add seed reveal/acknowledgement UI with Material 3 cards/rows and safe copy behavior. The seed is hidden behind reveal, acknowledgement is required, and copy feedback is transient.
- [x] Add restore method screen: restore from Android cloud backup, restore from seed phrase. Android cloud restore is shown as unavailable/no-cloud MVP and routes to seed restore.
- [x] Add mnemonic input validation matching iOS supported word counts and error copy. Restore uses `MnemonicInput` normalization and the shared supported-count label.
- [x] Add staged mint restore UI that accepts multiple URLs from paste, normalizes candidates, fetches mint preview name/icon, and allows remove/reorder/retry. Restore staging uses `mintUrlCandidates`, best-effort `WalletManager.previewMint`, `MintAvatar`, Up/Down/Remove actions, and retry on failed restore rows.
- [x] Add first-mint setup with recommended mints, custom URL entry, paste multiple URLs, skip, and progress/result rows. First setup supports multi-select recommended mints, pasted/custom mint candidates, skip, and row-level add progress/results with retry.
- [x] Add per-mint restore result model equivalent to iOS `RestoreMintResult`: recovered, pending, failed, skipped. Android keeps the domain `RestoreMintResult` and adds UI phases for pending/restoring/restored/skipped/failed.
- [x] Make restore usable from Settings without forcing an awkward full onboarding restart. Existing wallet restore can be exited back to the wallet and uses the same restore method/staged mint flow.
- [x] Add onboarding analytics/breadcrumbs only if Sentry is opted in and without sensitive values. Onboarding emits generic step breadcrumbs through the opt-in `SentryService`; no seed words, mint URLs, or user/device identifiers are included.

Success condition:

- A new Android user can create a wallet and add zero, one, or multiple mints with the same decision points as iOS; a restoring user can restore seed plus staged mints with progress/results and can recover from partial failures.

## Milestone 3: Home, Shell, And Foreground Behavior

Goal: finish the user-facing home experience and app shell parity.

iOS reference:

- Home has a pinned mint chip, unit pager, large balance, transient received-delta beat, Receive/Send actions, scanner toolbar button, recent timeline, pull-to-refresh, and stale quote sync hooks.
- The shell respects app lock and privacy cover.

iOS implementation reference files for this milestone:

- Main wallet canvas, pinned mint/balance region, receive/send actions, scanner affordance, recent timeline, pull refresh, and received-delta animation: `CashuWallet/Views/Main/MainWalletView.swift`.
- Balance formatting, animated balance, amount columns, transaction/request row affordances, and timeline icons: `CashuWallet/Views/Components/AnimatedBalanceView.swift`, `CashuWallet/Views/Components/CashuRequestAmountColumn.swift`, `CashuWallet/Views/Components/TransactionAmountColumn.swift`, `CashuWallet/Views/Components/TransactionIcon.swift`, `CashuWallet/Core/AmountFormatter.swift`, `CashuWallet/Core/Protocols/CurrencyProtocol.swift`.
- Shell app-lock/privacy-cover and tab integration: `CashuWallet/App/ContentView.swift`, `CashuWallet/App/CashuWalletApp.swift`.
- Receive event sources and pending-quote foreground behavior: `CashuWallet/Core/Wallet/WalletManager+Tokens.swift`, `CashuWallet/Core/Wallet/WalletManager+Lightning.swift`, `CashuWallet/Core/Wallet/WalletManager+MintQuoteSync.swift`, `CashuWallet/Core/Wallet/WalletManager+NPC.swift`, `CashuWallet/Models/Foundation/WalletNotifications.swift`.
- Request/transaction dedupe and Home/History shared row behavior: `CashuWallet/Core/CashuRequestStore.swift`, `CashuWallet/Core/CashuRequestListener.swift`, `CashuWallet/Views/History/HistoryView.swift`, `CashuWallet/Models/Transactions/WalletTransaction.swift`, `CashuWallet/Models/Requests/CashuRequest.swift`.
- Tests to mirror or extend on Android: `CashuWalletUITests/MainTabUITests.swift`, `CashuWalletUITests/WalletIntegrationTests.swift`, `CashuWalletTests/TransactionServiceTests.swift`, `CashuWalletTests/WalletStoreTests.swift`.

Android current state:

- `HomeScreen` already has pinned top content, mint chip, unit pager, scan button, action duet, unified recent timeline, empty states, and pull refresh.

Checklist:

- [x] Add home received-delta beat for sat receives, with Material motion and TalkBack-safe behavior. Home now collects `WalletReceiveEvent`, shows a short animated received chip, and announces it as a polite live region.
- [x] Post receive events from ecash, Lightning, on-chain, Cashu Request, and NPC receive paths consistently. `WalletManager` emits typed receive events for direct ecash receives, direct quote minting, stale quote sync, Cashu Request claims, and NPC claims.
- [x] Avoid showing sat delta for non-sat token receives, matching iOS. `WalletReceiveEvent.showsHomeSatDelta()` gates Home to positive sat-only events and has JVM coverage.
- [x] Add foreground stale quote sync and pending-token checks behind the same settings/runtime semantics as iOS. Foreground maintenance remains app-lifecycle gated, respects pending/sent-token settings, and pending quote sync now preserves event metadata when minting succeeds.
- [x] Ensure Home recents suppress duplicate transaction rows when a Cashu Request row represents the same payment. `unifiedRecent` suppresses request-attached transaction IDs and has JVM coverage.
- [x] Verify unit pager only appears when the active mint supports multiple units and a non-sat balance is held. Existing `HomeBalance.showsUnitPager` logic remains covered by JVM tests.
- [x] Align empty states, button enabled states, scanner routing, and refresh semantics with iOS while using Material 3 idioms. Existing Material empty states, pinned scanner affordance, receive/send enablement, pull refresh, and foreground maintenance are retained and wired through the shell.
- [x] Add TalkBack labels for active mint, balance toggle, Receive, Send, Scan, unit pager dots, and recent rows. Home controls and shared timeline rows now expose explicit semantic descriptions.

Success condition:

- Home shows the same wallet facts and lifecycle feedback as iOS, with no duplicate request/transaction rows and no unprotected content during app lock or task-switching.

## Milestone 4: Unified Send, Pay Flows, And Contactless

Goal: complete all outgoing payment behavior that iOS supports.

iOS reference:

- `UnifiedSendView` accepts Lightning address, BOLT11, BOLT12, on-chain address, Cashu Request, ecash token, scan, ecash creation, and NFC tap.
- Cashu Requests can be paid from existing ecash, paid after adding/funding a requested mint, or fall back to bundled BOLT11 when appropriate.
- Fee rows are precomputed/reserved; failures use shared status screens with useful CTAs.

iOS implementation reference files for this milestone:

- Unified send UX, destination inference, amount/confirm/status faces, mint switching, retry, recovery CTAs, P2PK lock input, and success/failure copy: `CashuWallet/Views/Send/SendView.swift`.
- Shared payment status layout and processing/success/failure motion: `CashuWallet/Views/Send/Components/AuthorizingOverlay.swift`.
- Amount entry, fiat/unit display, clipboard suggestions, and number pad behavior: `CashuWallet/Views/Send/Components/AmountEntryView.swift`, `CashuWallet/Views/Send/Components/NumberPadAmountInput.swift`, `CashuWallet/Views/Send/Components/CurrencyAmountDisplay.swift`, `CashuWallet/Views/Send/Components/ClipboardPaymentChip.swift`.
- Cashu Request routing, BOLT11 fallback, add-mint-to-pay, target-mint top-up, fee estimation, and settling states: `CashuWallet/Core/Wallet/WalletManager+CashuPaymentRequests.swift`, `CashuWallet/Core/Wallet/WalletManager+Lightning.swift`, `CashuWallet/Core/PaymentRequestDecoder.swift`, `CashuWallet/Models/Payments/PaymentRequestParser.swift`, `CashuWallet/Core/LightningRequestParser.swift`, `CashuWallet/Models/Payments/PaymentMethodKind.swift`.
- Ecash token creation, P2PK pubkey normalization, claim polling, and token receive handoff: `CashuWallet/Core/Wallet/WalletManager+Tokens.swift`, `CashuWallet/Core/Services/TokenService.swift`, `CashuWallet/Core/TokenParser.swift`, `CashuWallet/Models/Tokens/TokenTransferModels.swift`.
- NFC/contactless routing, NDEF read/write, Cashu Request/BOLT11 fallback, and native iOS NFC status behavior to translate into Android NFC conventions: `CashuWallet/Core/Services/NFCPaymentService.swift`, `CashuWallet/Core/Services/ContactlessPaymentCoordinator.swift`, `CashuWallet/Core/Services/NFCReaderDelegate.swift`, `CashuWallet/Core/Services/NDEFTextRecordCoder.swift`.
- Tests to mirror or extend on Android: `CI/IntegrationTests/Tests/PaymentRequestDecoderTests.swift`, `CI/IntegrationTests/Tests/NutshellIntegrationTests.swift`, `CashuWalletTests/TokenServiceTests.swift`, `CashuWalletTests/MintServiceTests.swift`.

Android gaps:

- `UnifiedSendScreen` handles common rails but lacks iOS's `addMintAndPayCashuRequest`, `NeedsExternalTopUp`, `MintSettling`, fee precomputation helpers, and add-mint chooser/top-up QR.
- Cashu Request confirm rows are simpler and do not show live fee/acquire states.
- Send ecash P2PK entry lacks scanner quick-fill and seed-derived primary key integration.
- Contactless has Android UI, but routing/fallback/error behavior is not as complete as iOS.

Checklist:

- [x] Port or implement `routeForCashuPaymentRequest` decisions fully, including pay-with-ecash, pay-BOLT11-fallback, and acquire-then-pay. Added `CashuPaymentRequestRoute` with pay, fallback, add-mint, top-up, unsupported-unit, and missing-amount states.
- [x] Add Android equivalents for `mintInputFeePpk`, `estimateCashuPaymentFee`, `addMintAndPayCashuRequest`, external top-up, and settling errors. Android now reserves fee/route rows, pays through routed compatible mints, exposes `addMintAndPayCashuPaymentRequest`, creates target-mint top-up quotes, and uses explicit settling/recovery notices where CDK prepay fee estimation is not separately exposed.
- [x] Add Add Mint To Pay bottom sheet with preview name/icon and multi-mint target choice. `AddMintToPaySheet` previews requested mints and adds the selected target.
- [x] Add top-up QR flow when no held mint can bankroll the target mint. `TopUpQuoteSheet` shows a target-mint BOLT11 QR and copy/share behavior through `QrCard`.
- [x] Reserve fee/mint rows during loading so confirm/status screens do not jump. Melt confirm rows retain network fee/total placeholders; Cashu Request confirm rows retain amount/mint/route rows across route states.
- [x] Add insufficient-balance recovery CTA to choose another compatible mint. Cashu Request top-up state offers "Choose another mint" and "Create top-up QR".
- [x] Match iOS amountless BOLT11/BOLT12 caution copy and do not route to doomed quote creation. Amountless BOLT11/BOLT12 now show explicit warnings and do not advance to quote creation.
- [x] Normalize method copy so BOLT12 naming matches iOS product language where intentional. BOLT12 copy consistently uses "BOLT12 offer".
- [x] Add non-sat Cashu Request handling: show clear unsupported notice until Android can pay non-sat requests. Non-sat Cashu Requests route to an unsupported-unit confirmation notice.
- [x] Add send ecash scanner/quick-fill for recipient P2PK public keys. Send Ecash has a dedicated scanner target and consumes scanned recipient keys.
- [x] Add locked-key chip UX instead of only a raw text field. Valid P2PK input renders a locked recipient chip above the raw field.
- [x] Use seed-derived primary P2PK key in quick-fill once Milestone 7 lands. Send Ecash now offers the seed-derived "your key" quick-fill when the Locked Ecash quick-lock setting is enabled, plus clipboard key quick-fill.
- [x] Align contactless NFC routing with iOS: Cashu Request when payable, BOLT11 fallback when needed, Lightning handoff to Send, clear unsupported unit/no mint/insufficient balance errors. NFC now uses the shared Cashu Request route model and preserves Lightning handoff.
- [x] Add tests for destination inference, Cashu Request fallback/acquire, fee rows, mint switching, top-up, and NFC input decoding. Added/expanded route, NFC, and P2PK scan normalization JVM tests; fee/top-up behavior is covered through route-state tests and compile validation.

Success condition:

- Every outgoing payment path available on iOS can be completed on Android or produces the same intentional unsupported/recovery surface; Cashu Requests are not dead ends when iOS can add/fund/pay them.

## Milestone 5: Receive Ecash And Cashu Request Parity

Goal: complete the inbound ecash and NUT-18 request experience.

iOS reference:

- Receive ecash has paste/scan, auto-paste, token review, fee loading, unknown mint caution, locked-to-known-key validation, receive later, and shared processing/success/failure status.
- Cashu Requests are editable, regeneratable, shareable, deletable, attachable to payments, and integrated into Home/History.
- `CashuRequestStore` supports `create`, `createNew`, `upsertQuoteIntent`, `update`, attach by request or quote id, delete, reset, and reload.

iOS implementation reference files for this milestone:

- Receive chooser, paste/scan entry, New Request entry, locked receive entry, and receive navigation decisions: `CashuWallet/Views/Receive/ReceiveView.swift`, `CashuWallet/App/ContentView.swift`, `CashuWallet/Core/Navigation/NavigationManager.swift`.
- Token review, fee loading, unknown mint caution, P2PK locked-to-known-key labeling, receive later, and receive status behavior: `CashuWallet/Views/Receive/ReceiveTokenDetailView.swift`, `CashuWallet/Core/TokenParser.swift`, `CashuWallet/Models/Tokens/TokenInfo.swift`, `CashuWallet/Core/Wallet/WalletManager+Tokens.swift`, `CashuWallet/Core/Services/TokenService.swift`.
- Shared status screen used by receive/send resolution: `CashuWallet/Views/Send/Components/AuthorizingOverlay.swift`.
- Cashu Request detail, edit rows, QR/share/copy/delete/status, paid/waiting auto-completion, and request lifecycle copy: `CashuWallet/Views/Receive/CashuRequestDetailView.swift`, `CashuWallet/Views/Receive/CashuRequestAmountPickerSheet.swift`, `CashuWallet/Views/Receive/CashuRequestMintPickerSheet.swift`, `CashuWallet/Views/Components/QRCodeView.swift`.
- Request store APIs, quote-backed intents, attach-by-request/quote, reset/reload, stable id regeneration, and wallet-boundary cleanup: `CashuWallet/Core/CashuRequestStore.swift`, `CashuWallet/Models/Requests/CashuRequest.swift`, `CashuWallet/Core/Wallet/WalletManager+MintQuoteSync.swift`, `CashuWallet/Core/Wallet/WalletManager+Lifecycle.swift`.
- NUT-18 encoding/decoding and Nostr payment delivery: `CashuWallet/Core/PaymentRequestBuilder.swift`, `CashuWallet/Core/PaymentRequestDecoder.swift`, `CashuWallet/Core/CashuRequestListener.swift`, `CashuWallet/Core/NostrInboxClient.swift`, `CashuWallet/Core/NIP17.swift`, `CashuWallet/Core/NIP44.swift`.
- Home/History integration and duplicate suppression: `CashuWallet/Views/Main/MainWalletView.swift`, `CashuWallet/Views/History/HistoryView.swift`, `CashuWallet/Models/Transactions/WalletTransaction.swift`.
- Tests to mirror or extend on Android: `CashuWalletTests/WalletStoreTests.swift`, `CashuWalletTests/TransactionServiceTests.swift`, `CI/IntegrationTests/Tests/PaymentRequestDecoderTests.swift`, `CashuWalletUITests/ReceiveUITests.swift`.

Android gaps:

- Receive ecash closes directly on success rather than using the full shared status flow and home delta event.
- Locked token validation only sees generated/imported P2PK keys, not the iOS seed-derived primary key.
- New request defaults to the active mint URL, while iOS creates an any-mint request by default.
- Cashu Request detail is mostly read-only and store APIs are missing quote-backed and update behaviors.

Checklist:

- [x] Add shared PaymentStatus screen to receive-token flow: Claiming, Payment Received, Could Not Receive. Receive ecash now swaps into the shared Material status terminal for processing/success/failure.
- [x] Add a minimum processing beat for instant receive, matching iOS's legibility behavior. Receive token success waits at least 650ms before resolving.
- [x] Post home received notification after successful token receive, including unit, and suppress misleading sat deltas for non-sat. The receive path still uses `WalletManager.receiveTokens`, which emits the typed receive event added in Milestone 3.
- [x] Add unknown mint caution before receiving a token from a mint not yet tracked. Review now flags unknown mints and shows an inline caution.
- [x] Add locked-to row that distinguishes "Your key" from unknown key and disables Receive for unknown locked tokens. Known stored P2PK keys and the seed-derived primary key show "Your key"; unknown locked tokens cannot be received.
- [x] Generate stable pending receive IDs that do not collide for repeated long tokens. Pending receive ids now use the SHA-256 of the full normalized token and have JVM regression coverage.
- [x] Decide and align default New Request mint scope with iOS. Current iOS uses any mint (`mints: []`); Android should match unless product explicitly changes both platforms. Android New Request now creates any-mint NUT-18 requests by default.
- [x] Expand Android `CashuRequestStore` with create/upsert/update/attach-by-quote/reset/reload semantics. Store APIs now cover upsert, update, quote-intent upsert, attach-by-quote, reload, and reset.
- [x] Add quote-backed Cashu Request intents for BOLT12 reusable offers and on-chain/BOLT11 receive requests. Receive Lightning now stores BOLT11, BOLT12, and on-chain mint quotes as quote-backed request rows and attaches payment by quote id when minting succeeds.
- [x] Add editable Cashu Request detail rows: Amount, Mint, Unit, Memo where applicable. NUT-18 request detail rows are editable; quote-backed Lightning/on-chain intents are intentionally read-only protocol records.
- [x] Regenerate encoded request with the same request id when amount/mint/unit changes. Detail edits rebuild the NUT-18 payload with the original request id.
- [x] Add Material pickers for Cashu Request amount, mint, and unit. Amount/memo use Material dialogs; mint/unit use Material bottom-sheet pickers.
- [x] Add top-bar share action and bottom copy action rules matching iOS. Request detail keeps top-bar share and bottom copy, with quote-aware copy labels for invoice/address/request.
- [x] Add paid/waiting status behavior and auto-completion when request payment lands. Request detail observes store state for waiting/paid status, Nostr payments attach by request id, and quote-backed receives attach by quote id.
- [x] Add request delete confirmation copy that makes clear payment routing remains valid. Delete confirmation now says shared payment links keep routing to the wallet.

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

iOS implementation reference files for this milestone:

- Receive Lightning/BOLT12/on-chain UI, method selection, amount editing, invoice/offer/address display, expiry, polling, success resolution, and copy/share behavior: `CashuWallet/Views/Receive/ReceiveLightningView.swift`.
- Shared status screen used when paid quotes mint successfully or fail: `CashuWallet/Views/Send/Components/AuthorizingOverlay.swift`.
- Lightning/on-chain quote creation, reusable BOLT12 offer reuse, quote polling/subscriptions, minting paid quotes, and receive event emission: `CashuWallet/Core/Wallet/WalletManager+Lightning.swift`, `CashuWallet/Core/Wallet/WalletManager+MintQuoteSync.swift`, `CashuWallet/Core/Services/LightningService.swift`, `CashuWallet/Core/Services/MintService.swift`, `CashuWallet/Models/Quotes/QuoteModels.swift`, `CashuWallet/Models/Quotes/QuoteStates.swift`.
- Quote-backed Cashu Request rows for BOLT12/on-chain/BOLT11 receive intents: `CashuWallet/Core/CashuRequestStore.swift`, `CashuWallet/Models/Requests/CashuRequest.swift`, `CashuWallet/Views/Receive/CashuRequestDetailView.swift`, `CashuWallet/Views/History/HistoryView.swift`.
- Payment method parsing, BOLT11/BOLT12/on-chain labels, amount locking, and BIP-321 handling: `CashuWallet/Core/PaymentRequestDecoder.swift`, `CashuWallet/Models/Payments/PaymentRequestParser.swift`, `CashuWallet/Core/LightningRequestParser.swift`, `CashuWallet/Models/Payments/PaymentMethodKind.swift`.
- On-chain observation, explorer URL/status copy, and transaction-detail QR/link behavior: `CashuWallet/Models/Payments/OnchainExplorer.swift`, `CashuWallet/Views/History/TransactionDetailView.swift`, `CashuWallet/Core/Wallet/WalletErrors.swift`.
- Multi-unit receive formatting and unit selection: `CashuWallet/Core/AmountFormatter.swift`, `CashuWallet/Core/Protocols/CurrencyProtocol.swift`, `CashuWallet/Models/Mints/MintInfo.swift`, `CashuWallet/Views/Receive/CashuRequestMintPickerSheet.swift`.
- Tests to mirror or extend on Android: `CI/IntegrationTests/Tests/PaymentRequestDecoderTests.swift`, `CI/IntegrationTests/Tests/NutshellIntegrationTests.swift`, `CashuWalletTests/MintServiceTests.swift`, `CashuWalletTests/TransactionServiceTests.swift`.

Android gaps:

- `ReceiveLightningScreen` is structurally present but simpler.
- Reusable amountless BOLT12 offer handling, quote-backed history intent, amount editing, expiry countdown, on-chain observer link/status, and address reuse/new-address controls need parity.

Checklist:

- [x] Build a Material method picker equivalent to iOS method choices: Lightning invoice, reusable invoice, on-chain address. Receive Lightning now uses a Material bottom-sheet method picker with friendly titles/descriptors and top-bar method glyph.
- [x] For BOLT11, show expiry countdown, expired state, and disable/refresh behavior as iOS does. BOLT11 display tracks expiry every second, stops polling after expiry, and shows expired status.
- [x] For BOLT12, support reusable amountless offers and fixed-amount offers with clear copy. Reusable invoices auto-create amountless offers and can mint a fresh fixed-amount offer from the editable Amount row.
- [x] Reuse an existing amountless BOLT12 offer instead of creating duplicates. `WalletManager.existingAmountlessOffer()` reopens the active mint's existing amountless BOLT12 quote when available.
- [x] Store BOLT12 and on-chain receive quotes as Cashu Request quote intents in History. Milestone 5 added quote-backed rows for all receive quotes; Milestone 6 keeps those rows updated through BOLT12/on-chain reuse.
- [x] Add amount editing for reusable BOLT12 request rows. The reusable invoice display exposes an editable Amount row backed by a Material amount dialog.
- [x] For on-chain, support existing address reuse and "Use new address". `WalletManager.existingOnchainMintQuote()` reuses the active mint's existing on-chain quote, and the display offers "Use new address" to force a fresh quote.
- [x] Observe on-chain payment and show status, block explorer link, address, and tx link where available. On-chain display links to the address explorer, polls quote state, and observes explorer payment details when a concrete amount is available.
- [x] Use shared processing/success screens when minting paid quotes. Paid/issued quotes now transition through the shared `PaymentStatusScreen` processing/success/failure terminal.
- [x] Support multi-unit mint units for receive quote creation where CDK supports them. Existing receive-unit picker remains active for Lightning/reusable invoice creation and passes the selected mint unit to quote creation.
- [x] Align BOLT12 product copy with iOS. If iOS says "Reusable invoice", Android should not surface conflicting "Offer" wording without a deliberate copy decision. Receive UI now uses "Reusable invoice" and "Copy invoice" for BOLT12.

Success condition:

- A user can receive via BOLT11, reusable BOLT12, and on-chain on Android with the same lifecycle, history, copy/share, and success behavior as iOS.

## Milestone 7: Locked Ecash And P2PK

Goal: bring Android locked ecash to the same public user-facing and protocol level as iOS.

iOS reference:

- `PaymentRequestBuilder` can encode NUT-10 data inside NUT-18 requests.
- `LockedReceiveRequest.build()` creates a request locked to the wallet's seed-derived primary P2PK key and routed over Nostr relays.
- Settings exposes "Your key", quick lock, advanced keys, key detail, QR, backup/reveal with auth, rename/remove/used count, and explainer.
- Receive token validation and signing include the seed-derived primary key plus stored P2PK keys.

iOS implementation reference files for this milestone:

- NUT-10 encoding inside NUT-18, locked receive request builder, hashing helpers, and parsing expectations: `CashuWallet/Core/PaymentRequestBuilder.swift`, `CashuWallet/Core/PaymentRequestDecoder.swift`, `CashuWallet/Models/Foundation/Data+Hashing.swift`, `CI/IntegrationTests/Tests/PaymentRequestDecoderTests.swift`.
- Seed-derived primary P2PK key, stored/generated/imported keys, known-key lookup, signing-key lookup, quick-lock preference, usage tracking, and wallet-boundary cleanup: `CashuWallet/Core/SettingsManager.swift`, `CashuWallet/Core/SettingsStore.swift`, `CashuWallet/Core/KeychainService.swift`, `CashuWallet/Core/Services/TokenService.swift`.
- Locked receive entry, receive request QR/detail, and receive-token known/unknown locked-key display: `CashuWallet/Views/Receive/ReceiveView.swift`, `CashuWallet/Views/Receive/ReceiveTokenDetailView.swift`, `CashuWallet/Views/Receive/CashuRequestDetailView.swift`.
- Send-side lock affordance, P2PK quick fills, and recipient-key validation/copy behavior: `CashuWallet/Views/Send/SendView.swift`, `CashuWallet/Core/Wallet/WalletManager+Tokens.swift`, `CashuWallet/Core/TokenParser.swift`.
- P2PK settings UI for "Your key", quick lock, advanced keys, key detail, QR/copy, private reveal, import, rename/remove, and explainer copy: `CashuWallet/Views/Settings/P2PKSettingsSection.swift`, `CashuWallet/Views/Components/QRCodeView.swift`.
- Nostr relay routing used by locked receive requests: `CashuWallet/Core/NostrService.swift`, `CashuWallet/Core/NIP17.swift`, `CashuWallet/Core/NIP44.swift`, `CashuWallet/Views/Settings/NostrSettingsSection.swift`.
- Tests to mirror or extend on Android: `CashuWalletTests/TokenServiceTests.swift`, `CI/IntegrationTests/Tests/PaymentRequestDecoderTests.swift`, `CashuWalletTests/NIP44Tests.swift`.

Android gaps:

- Android `PaymentRequestBuilder` has no `nut10` payload.
- There is no `LockedReceiveRequest`.
- `SettingsManager.p2pkSigningKeysFor` only checks stored keys and does not include seed-derived primary key.
- `P2PKScreen` is a basic generated/imported key list without primary key card, backup/reveal auth, QR, rename, key detail, or explainer.

Checklist:

- [x] Add Android NUT-10 encoding to `PaymentRequestBuilder.build`.
- [x] Add `LockedReceiveRequest` builder using seed-derived primary P2PK key and configured Nostr relays.
- [x] Add tests equivalent to iOS `testLockedReceiveRequestEncodesNut10AndParses`.
- [x] Add seed-derived primary P2PK public/private key access through Android secure storage/seed entropy, protected by authentication for private reveal.
- [x] Include seed-derived primary private key in `p2pkSigningKeysFor` when a token is locked to that public key.
- [x] Add `isKnownP2PKPublicKey` equivalent that checks primary and stored keys.
- [x] Update receive token review to label primary-key locked tokens as "Your key".
- [x] Add Receive Locked Ecash entry and QR detail screen.
- [x] Redesign `P2PKScreen` with Material sections: Your key, Quick lock toggle, Advanced keys, generated/imported device keys.
- [x] Add key detail screen: public key QR/copy, private key reveal/copy behind auth, rename, remove, usage count.
- [x] Add import sheet validation and duplicate handling.
- [x] Add explainer sheet/section for locked ecash using concise Android-native copy.
- [x] Add send ecash quick-fill for "Your key" and recent copied public key.

Success condition:

- Android can create receive requests for ecash only the wallet can claim, receive locked tokens addressed to its primary key, and manage P2PK keys with security and UX parity.

## Milestone 8: Mints And Mint Metadata

Goal: bring mint list, discovery, and detail up to iOS information richness.

iOS reference:

- Mints list refreshes mint info, supports discovery, custom/paste add, default set/remove gestures, active dot, and detailed mint pages.
- Mint Detail fetches full NUT-06 info via CDK and renders balance, non-sat balances, connection, about, MOTD, capabilities, NUT technical details, payment method ranges, contact links, software, units, ToS, share/copy, set default/remove.

iOS implementation reference files for this milestone:

- Mints tab list, active/default dot, refresh behavior, add/custom/paste flows, remove/set-default gestures, empty/error states, and navigation to detail: `CashuWallet/Views/Mints/MintsListView.swift`.
- Mint discovery over Nostr, discovered/added sections, refresh/error/session-added state, search/filtering, and WebSockets-disabled behavior: `CashuWallet/Views/Mints/MintDiscoverySheet.swift`, `CashuWallet/Core/MintDiscoveryManager.swift`, `CashuWallet/Core/NostrService.swift`, `CashuWallet/Core/NostrInboxClient.swift`.
- Mint detail metadata rendering, full NUT-06 info, capability sections, NUT rows, contact links, ToS/software, payment method ranges, share/copy, connection state, and multi-unit balances: `CashuWallet/Views/Mints/MintDetailView.swift`, `CashuWallet/Models/Mints/MintInfo.swift`, `CashuWallet/Models/Payments/PaymentMethodKind.swift`.
- Mint metadata fetching, full info parsing, tracked mint/unit wallet preparation, refresh, add/remove/set active, and iCloud backup trigger behavior: `CashuWallet/Core/Wallet/WalletManager+Mints.swift`, `CashuWallet/Core/Services/MintService.swift`, `CashuWallet/Core/Protocols/WalletServiceProtocol.swift`, `CashuWallet/Core/Wallet/WalletManager+Backup.swift`.
- Formatting/copy helpers and shared UI components to translate into Material equivalents: `CashuWallet/Core/AmountFormatter.swift`, `CashuWallet/Core/Protocols/CurrencyProtocol.swift`, `CashuWallet/Views/Components/QRCodeView.swift`.
- Tests to mirror or extend on Android: `CashuWalletTests/MintServiceTests.swift`, `CI/IntegrationTests/Tests/CurrencyTests.swift`, `CI/IntegrationTests/Tests/NutshellIntegrationTests.swift`, `CashuWalletUITests/MainTabUITests.swift`.

Android gaps:

- List/discovery are close but do not refresh mint info on tab open like iOS.
- Mint Detail lacks full NUT-06 fetch/rendering, capabilities/NUTs, contact links, ToS, software version, connection state, method min/max details, and share action.

Checklist:

- [x] Add Android `fetchFullMintInfo` via CDK gateway.
- [x] Add `refreshMintInfo` on Mints tab open, with safe placeholder handling.
- [x] Render connection state: checking, online, offline.
- [x] Render About, long description/read more, MOTD.
- [x] Render capabilities summary: Lightning, on-chain, locked ecash, HTLC where supported.
- [x] Render NUT technical details for NUT-04/05/07/08/09/10/11/12/14/20 where CDK exposes them.
- [x] Render Receive/Send payment methods with min/max amounts and on-chain confirmation details.
- [x] Render contact rows as Android intents where possible: email, website, X/Twitter, Telegram, Nostr, generic copy.
- [x] Render software version and ToS link.
- [x] Add top-bar share action for mint URL.
- [x] Add copy feedback for full URL.
- [x] Keep multi-unit balances and ensure non-sat unit wallets are queried without unintentionally creating new wallets.
- [x] Improve discovery: search, added/discovered sections, pull/explicit refresh, error state, session-added state, and WebSockets-disabled state.

Success condition:

- Mint Detail on Android exposes the same mint facts a user can inspect on iOS, and mint discovery/list management behave as equivalent Android-native flows.

## Milestone 9: History And Transaction Detail

Goal: make Android's ledger review surfaces fully equivalent and scalable.

iOS reference:

- History merges transactions and Cashu Requests, suppresses duplicates, filters, searches, groups by date, lazily windows large lists, syncs stale quotes on open, supports pull refresh, and offers swipe delete for request rows.
- Transaction detail uses strict QR/share/copy rules and canonical row order.

iOS implementation reference files for this milestone:

- History list merge, request/transaction grouping, filters, search, date buckets, visible-count windowing, pull refresh, stale quote sync trigger, request deletion, empty/no-results copy, and row navigation: `CashuWallet/Views/History/HistoryView.swift`.
- Transaction detail canonical row order, QR/share/copy visibility rules, settled ecash passive copy, pending/reusable/on-chain QR behavior, explorer links, and action copy: `CashuWallet/Views/History/TransactionDetailView.swift`.
- Transaction/request row presentation and amount/icon helpers: `CashuWallet/Views/Components/TransactionAmountColumn.swift`, `CashuWallet/Views/Components/CashuRequestAmountColumn.swift`, `CashuWallet/Views/Components/TransactionIcon.swift`, `CashuWallet/Views/Components/QRCodeView.swift`.
- Request/transaction models and duplicate-suppression inputs: `CashuWallet/Models/Transactions/WalletTransaction.swift`, `CashuWallet/Models/Requests/CashuRequest.swift`, `CashuWallet/Core/CashuRequestStore.swift`, `CashuWallet/Core/Services/TransactionService.swift`.
- Stale quote sync, quote-to-request attachment, transaction loading, on-chain observation, and explorer URL generation: `CashuWallet/Core/Wallet/WalletManager+MintQuoteSync.swift`, `CashuWallet/Core/Wallet/WalletManager+Lightning.swift`, `CashuWallet/Models/Payments/OnchainExplorer.swift`.
- Tests to mirror or extend on Android: `CashuWalletTests/TransactionServiceTests.swift`, `CashuWalletTests/WalletStoreTests.swift`, `CashuWalletUITests/MainTabUITests.swift`, `CashuWalletUITests/WalletIntegrationTests.swift`.

Android current state:

- History already merges requests and transactions, filters, searches, groups, refreshes, opens transaction/request details, and supports request deletion through long press.
- Transaction detail mostly mirrors actionable QR, settled ecash copy, explorer link, and detail rows.
- Milestone 9 update: Android now runs stale mint-quote sync on History entry, windows the merged ledger in 30-row visible batches with near-end prefetch, keeps search visible for zero-result states, and supports Material swipe-to-remove plus the existing long-press request deletion path.
- Milestone 9 non-sat ledger plan: current wallet transactions remain sat-denominated because `WalletTransaction` has no persisted unit. When CDK exposes completed non-sat transaction units, add a transaction unit field, render `CurrencyAmount` for non-sat detail/list rows, keep sat fiat conversion sat-only, and add focused completed non-sat row/detail tests. No compatibility migration is needed for unreleased Android data.

Checklist:

- [x] Add stale pending quote sync when History opens, matching iOS `syncPendingMintQuotesIfStale`.
- [x] Add large-ledger windowing/pagination equivalent to iOS visible-count extension.
- [x] Replace or supplement long-press request delete with a discoverable Material swipe action or overflow menu.
- [x] Audit row copy and empty states against iOS: No Results, Nothing Here, No History Yet.
- [x] Audit transaction detail rows against iOS canonical rows. Remove Android-only rows unless the same row is intentionally added to iOS.
- [x] Verify QR visibility rules for pending ecash, reusable BOLT12, settled one-shot Lightning, on-chain addresses, and failed transactions.
- [x] Verify passive copy for settled ecash without QR/share.
- [x] Add non-sat transaction display plan for future non-sat completed rows.
- [x] Add tests for duplicate suppression, search over request received amount, date buckets, QR/share/copy rules, and explorer URL generation.

Focused validation:

- `cd android && JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.ui.history.HistoryTimelineTest --tests org.cashu.wallet.Core.TransactionDisplayTest --tests org.cashu.wallet.Core.OnchainExplorerTest`

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

iOS implementation reference files for this milestone:

- Settings root grouping, section order, row copy, navigation, advanced/danger rows, destructive delete wallet copy, and section-level Material translation targets: `CashuWallet/Views/Settings/SettingsView.swift`, `CashuWallet/Views/Settings/AdvancedSettingsSection.swift`.
- Display/theme/currency/BTC-symbol settings, price refresh/caching, and home balance unit behavior: `CashuWallet/Views/Settings/ThemeSettingsSection.swift`, `CashuWallet/Core/SettingsManager.swift`, `CashuWallet/Core/SettingsStore.swift`, `CashuWallet/Core/PriceService.swift`, `CashuWallet/Core/AmountFormatter.swift`.
- Backup & Security, seed reveal/copy, iCloud backup status/actions, restore entry, App Lock row behavior, and delete wallet implications: `CashuWallet/Views/Settings/BackupSettingsSection.swift`, `CashuWallet/Core/Wallet/WalletManager+Backup.swift`, `CashuWallet/Core/Wallet/WalletManager+Lifecycle.swift`, `CashuWallet/Core/KeychainService.swift`, `CashuWallet/App/ContentView.swift`.
- Nostr signer/key card, nsec reveal/copy, generate/import/reset confirmations, relay validation/reset/copy, and NIP behavior: `CashuWallet/Views/Settings/NostrSettingsSection.swift`, `CashuWallet/Core/NostrService.swift`, `CashuWallet/Core/NIP44.swift`, `CashuWallet/Core/NIP17.swift`, `CashuWallet/Core/SettingsManager.swift`.
- P2PK settings and locked ecash settings rows: `CashuWallet/Views/Settings/P2PKSettingsSection.swift`, `CashuWallet/Core/Services/TokenService.swift`, `CashuWallet/Core/SettingsManager.swift`.
- Lightning/NPC settings, lightning address rows, mint selection, claim behavior, and privacy-safe errors: `CashuWallet/Views/Settings/LightningAddressSettingsSection.swift`, `CashuWallet/Core/NPCService.swift`, `CashuWallet/Core/Wallet/WalletManager+NPC.swift`, `CashuWallet/Core/Wallet/WalletErrors.swift`.
- Privacy toggles, WebSockets/Nostr/NPC/payment-request runtime settings, Sentry opt-in, and storage-only toggle decisions: `CashuWallet/Views/Settings/PrivacySettingsSection.swift`, `CashuWallet/Core/SentryService.swift`, `CashuWallet/Core/AppLogger.swift`, `CashuWallet/Core/SettingsStore.swift`.
- Tests to mirror or extend on Android: `CashuWalletUITests/SettingsUITests.swift`, `CashuWalletUITests/WalletIntegrationTests.swift`, `CashuWalletTests/NIP44Tests.swift`, `CashuWalletTests/WalletStoreTests.swift`.

Android gaps:

- Milestones 1 and 10 now provide the App Lock row/runtime behavior, authenticated backup seed reveal/copy, authenticated Nostr nsec reveal/copy, and Sentry opt-in wiring.
- Android intentionally does not show a cloud backup row until a real Android backup product exists.
- Milestone 10 update: Backup & Restore launches the staged restore wizard, Nostr exposes key status/reveal/import/generate/reset/relay management, relays validate as `ws://` or `wss://`, privacy toggles only expose runtime-backed behavior, Lightning Address settings follow the iOS enable/address/preferences/check structure, and destructive copy explains local-data deletion plus the lack of Android cloud backup.

Checklist:

- [x] Add App Lock settings row and detail behavior from Milestone 1.
- [x] Add Android cloud backup settings row if product implements backup; otherwise do not show a backup promise.
- [x] Update Backup & Restore to launch a real restore wizard rather than only reopening old onboarding.
- [x] Require auth for Backup seed reveal/copy.
- [x] Require auth for Nostr private key reveal/copy.
- [x] Add Nostr key card/status, generate/import/reset confirmations, nsec reveal sheet, and relay validation.
- [x] Validate relays as `ws://` or `wss://`, deduplicate, show errors, support reset to defaults.
- [x] Align Lightning/NPC settings with iOS, including lightning address rows, mint selection, claim behavior, and Sentry-safe errors.
- [x] Hide or implement `checkPendingOnStartup` based on iOS product decision.
- [x] Hide or implement `enablePaymentRequests` and `receivePaymentRequestsAutomatically`; do not leave them as inert user promises.
- [x] Ensure Sentry opt-in copy is explicit, off by default, and never sends secrets/tokens/seeds.
- [x] Align Display settings: currency picker, BTC/sat symbol toggle, fiat price refresh/caching, and home balance unit persistence.
- [x] Align Danger Zone delete wallet confirmation and backup implications.

Focused validation:

- `cd android && JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.Core.SettingsManagerTest --tests org.cashu.wallet.Core.NostrServiceTest --tests org.cashu.wallet.Core.NPCServiceTest --tests org.cashu.wallet.Core.SentryServiceTest`

Success condition:

- Every Android setting either has the same runtime effect as iOS or is intentionally absent. Secrets and destructive actions are protected and explained.

## Milestone 11: Protocol, CDK, Storage, And Runtime Hardening

Goal: ensure Android is not only visually caught up but also as robust against wallet edge cases as iOS.

iOS implementation reference files for this milestone:

- Wallet service boundaries, protocol contracts, and service split to compare with Android's current manager/typealias structure: `CashuWallet/Core/Protocols/WalletServiceProtocol.swift`, `CashuWallet/Core/Protocols/PaymentMethodProtocol.swift`, `CashuWallet/Core/Protocols/StorageProtocol.swift`, `CashuWallet/Core/Services/LightningService.swift`, `CashuWallet/Core/Services/MintService.swift`, `CashuWallet/Core/Services/TokenService.swift`, `CashuWallet/Core/Services/TransactionService.swift`, `CashuWallet/Core/Services/NFCPaymentService.swift`.
- Startup saga recovery, wallet initialization, restore, delete wallet, wallet boundary snapshots, database backup/restore, keyset refresh, tracked mint/unit wallet preparation, and wallet-scoped settings cleanup: `CashuWallet/Core/Wallet/WalletManager.swift`, `CashuWallet/Core/Wallet/WalletManager+Lifecycle.swift`, `CashuWallet/Core/Wallet/WalletManager+Mints.swift`, `CashuWallet/Core/WalletStore.swift`, `CashuWallet/Core/SettingsStore.swift`, `CashuWallet/Core/CashuRequestStore.swift`, `CashuWallet/Core/CashuRequestListener.swift`.
- Mint/melt/send/receive runtime paths, stale quote throttling, pending quote minting, quote-backed request metadata, NPC claims, and receive events: `CashuWallet/Core/Wallet/WalletManager+Tokens.swift`, `CashuWallet/Core/Wallet/WalletManager+Lightning.swift`, `CashuWallet/Core/Wallet/WalletManager+CashuPaymentRequests.swift`, `CashuWallet/Core/Wallet/WalletManager+MintQuoteSync.swift`, `CashuWallet/Core/Wallet/WalletManager+NPC.swift`, `CashuWallet/Core/NPCService.swift`.
- Multi-unit, parser, formatter, latest NUT, BOLT11/BOLT12/on-chain, NUT-18, NUT-10/P2PK, NUT-20 subscriptions, and NUT-09 restore references: `CashuWallet/Core/AmountFormatter.swift`, `CashuWallet/Core/Protocols/CurrencyProtocol.swift`, `CashuWallet/Core/PaymentRequestBuilder.swift`, `CashuWallet/Core/PaymentRequestDecoder.swift`, `CashuWallet/Core/LightningRequestParser.swift`, `CashuWallet/Core/TokenParser.swift`, `CashuWallet/Models/Mints/MintInfo.swift`, `CashuWallet/Models/Payments/PaymentMethodKind.swift`, `CashuWallet/Models/Payments/PaymentRequestParser.swift`, `CashuWallet/Models/Payments/OnchainExplorer.swift`, `CashuWallet/Models/Quotes/QuoteModels.swift`, `CashuWallet/Models/Quotes/QuoteStates.swift`.
- User-facing error mapping and privacy-safe runtime logging: `CashuWallet/Core/Wallet/WalletErrors.swift`, `CashuWallet/Core/AppLogger.swift`, `CashuWallet/Core/SentryService.swift`.
- Integration/unit tests to mirror or extend on Android: `CI/IntegrationTests/Tests/NutshellIntegrationTests.swift`, `CI/IntegrationTests/Tests/PaymentRequestDecoderTests.swift`, `CI/IntegrationTests/Tests/TokenParserTests.swift`, `CI/IntegrationTests/Tests/AmountFormatterTests.swift`, `CI/IntegrationTests/Tests/CurrencyTests.swift`, `CashuWalletTests/MintServiceTests.swift`, `CashuWalletTests/TokenServiceTests.swift`, `CashuWalletTests/TransactionServiceTests.swift`, `CashuWalletTests/WalletStoreTests.swift`.

Checklist:

- [x] Port startup saga recovery behavior or implement equivalent CDK repository recovery for incomplete mint/melt/send operations.
- [x] Refresh keysets for tracked mints at startup and when stale.
- [x] Add stale quote throttling and pending quote sync equivalent to iOS.
- [x] Ensure all mint/melt/send/receive operations refresh balance, load transactions, and emit update events consistently.
- [x] Audit multi-unit paths across send, receive, mint detail, payment request decoding, quote creation, token parsing, history, and amount formatting.
- [x] Add quote-backed transaction/request metadata storage equivalent to iOS for BOLT12/on-chain/BOLT11 receive intents.
- [x] Revisit Android service architecture. Current `LightningService`, `MintService`, `TokenService`, and `TransactionService` are typealiases/anchors to `WalletManager`; either keep this intentionally with tests or split responsibilities to match iOS service boundaries.
- [x] Add privacy-safe error mapping equivalent to iOS `WalletErrors` so raw CDK errors do not leak internal jargon to users.
- [x] Make wallet replacement/delete restore snapshots as robust as iOS during failures.
- [x] Ensure Android secure storage deletes wallet-scoped secrets at wallet boundary while preserving app-scoped settings intentionally.
- [x] Add CDK feature tests for latest supported NUTs, BOLT11, BOLT12, on-chain, NUT-18, NUT-10/P2PK, NUT-20 subscriptions, NUT-09 restore, and multi-unit.

Milestone 11 update:

- Startup maintenance now names the tracked mint/unit wallet refresh pass explicitly; Android uses CDK `ensureWallet` as the keyset/repository refresh hook for every tracked mint and advertised unit.
- Wallet user-facing errors now go through `WalletUserErrors`, and NPC, price, animated QR, Cashu Request listener, and wallet state surfaces no longer pass raw exception text to users.
- Android keeps the WalletManager-centered service boundary intentionally for this unreleased parity phase; `android/SERVICE_BOUNDARY_NOTES.md` records the decision and acceptance rules.
- Existing wallet database recovery, wallet-scoped snapshots, secure storage deletion, pending quote sync, quote-backed request metadata, and refresh/event ordering are now backed by focused validation for this milestone.

Focused validation:

- `cd android && JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugKotlin :app:testDebugUnitTest --tests org.cashu.wallet.Core.WalletUserErrorsTest --tests org.cashu.wallet.Core.ProtocolFeatureCoverageTest --tests org.cashu.wallet.Core.WalletDatabaseRecoveryTest --tests org.cashu.wallet.Core.PreferenceSnapshotTest --tests org.cashu.wallet.Core.SecureStorageProtocolTest --tests org.cashu.wallet.Core.WalletServiceProtocolTest --tests org.cashu.wallet.Core.PaymentRequestBuilderTest --tests org.cashu.wallet.Core.PaymentRequestDecoderTest --tests org.cashu.wallet.Core.TokenParserTest --tests org.cashu.wallet.Core.NPCServiceTest --tests org.cashu.wallet.Core.PendingMintQuoteTransactionsTest --tests org.cashu.wallet.Core.StoredMeltQuoteTransactionsTest`

Success condition:

- Android wallet operations recover from interrupted startup/payment states, handle the same protocol features as iOS, and expose user-facing errors at the same quality level.

## Android UI Bug Audit Backlog

This section captures Android-specific UI bugs and likely bugs found during the Compose code audit. These are not feature gaps alone; they are issues that can cause broken navigation, clipped content, lag, inaccessible controls, or surprising interactions even before full iOS parity work is complete.

iOS implementation reference files for this backlog:

- Back/navigation behavior and modal/sheet ownership to translate into Android `BackHandler`/predictive-back semantics: `CashuWallet/App/ContentView.swift`, `CashuWallet/Core/Navigation/NavigationManager.swift`, `CashuWallet/Views/Main/OnboardingView.swift`, `CashuWallet/Views/Send/SendView.swift`, `CashuWallet/Views/Receive/ReceiveView.swift`, `CashuWallet/Views/Receive/ReceiveTokenDetailView.swift`, `CashuWallet/Views/Receive/ReceiveLightningView.swift`, `CashuWallet/Views/Receive/CashuRequestDetailView.swift`, `CashuWallet/Views/History/HistoryView.swift`, `CashuWallet/Views/Mints/MintsListView.swift`, `CashuWallet/Views/Settings/SettingsView.swift`, `CashuWallet/Views/Components/ScannerWrapperView.swift`, `CashuWallet/Core/Services/ContactlessPaymentCoordinator.swift`.
- Layout, clipping, safe-area, QR, amount-entry, row-overflow, and large-content references: `CashuWallet/Views/Main/MainWalletView.swift`, `CashuWallet/Views/Components/AnimatedBalanceView.swift`, `CashuWallet/Views/Components/AmountEntryView.swift`, `CashuWallet/Views/Components/QRCodeView.swift`, `CashuWallet/Views/Components/TransactionAmountColumn.swift`, `CashuWallet/Views/Components/CashuRequestAmountColumn.swift`, `CashuWallet/Views/Send/SendView.swift`, `CashuWallet/Views/Receive/ReceiveTokenDetailView.swift`, `CashuWallet/Views/Receive/ReceiveLightningView.swift`, `CashuWallet/Views/History/TransactionDetailView.swift`, `CashuWallet/Views/Settings/BackupSettingsSection.swift`, `CashuWallet/Views/Settings/NostrSettingsSection.swift`, `CashuWallet/Views/Settings/P2PKSettingsSection.swift`, `CashuWallet/Views/Settings/LightningAddressSettingsSection.swift`.
- Settings performance/jank comparison surfaces and long-list composition targets: `CashuWallet/Views/Settings/SettingsView.swift`, `CashuWallet/Views/Settings/ThemeSettingsSection.swift`, `CashuWallet/Views/Settings/NostrSettingsSection.swift`, `CashuWallet/Views/Settings/P2PKSettingsSection.swift`, `CashuWallet/Views/Mints/MintsListView.swift`, `CashuWallet/Views/Mints/MintDiscoverySheet.swift`, `CashuWallet/Core/PriceService.swift`.
- Interaction-state references for single-action toggles, add/delete gestures, retry flows, copy/share feedback, external links, and quote watchers: `CashuWallet/Views/Settings/PrivacySettingsSection.swift`, `CashuWallet/Views/Settings/SettingsView.swift`, `CashuWallet/Views/Mints/MintsListView.swift`, `CashuWallet/Views/Mints/MintDiscoverySheet.swift`, `CashuWallet/Views/Send/SendView.swift`, `CashuWallet/Views/Receive/ReceiveLightningView.swift`, `CashuWallet/Views/Receive/ReceiveTokenDetailView.swift`, `CashuWallet/Views/History/TransactionDetailView.swift`, `CashuWallet/Models/Payments/OnchainExplorer.swift`.
- UI regression references for expected screen coverage and navigation smoke behavior: `CashuWalletUITests/UITestBase.swift`, `CashuWalletUITests/MainTabUITests.swift`, `CashuWalletUITests/ReceiveUITests.swift`, `CashuWalletUITests/SettingsUITests.swift`, `CashuWalletUITests/WalletIntegrationTests.swift`.

### Back Gesture And Predictive Back Bugs

The Android UI currently relies heavily on top app bar back buttons and route pops. Several screens have internal steps, overlays, or modal-like states but no matching `BackHandler`, so the system back gesture can leave the flow instead of moving back one logical step.

Milestone update: Android now handles system back for shell overlays, onboarding, send flows, history search, scanner, and contactless payment. Predictive-back visual previews still need physical-device or emulator verification on Android 14+.

Checklist:

- [x] Add `BackHandler` to `CashuApp` overlays so Android back closes scanner and contactless pay overlays before leaving the app or popping navigation. Shell overlays now close scanner/contactless state first.
- [x] Add `BackHandler` to `OnboardingScreen` so system back mirrors the visible back action, moves through onboarding steps predictably, and does not accidentally exit mid-create or mid-restore. Onboarding routes system back through the same step-aware `goBack` logic.
- [x] Add `BackHandler` to `UnifiedSendScreen` so input, amount, confirm, and status states follow the same behavior as the toolbar back button; block or confirm during in-flight sends. Unified Send now consumes in-flight back, returns failed/sent states appropriately, and otherwise mirrors toolbar back.
- [x] Add `BackHandler` to `SendEcashScreen` so the generated-token state returns to input instead of closing the route. Generated ecash now returns to the input face before route close.
- [x] Add `BackHandler` to `ReceiveEcashScreen` so review returns to paste/scan input and receive-later or in-flight states are not abandoned silently. Receive ecash now handles system back for review and status states.
- [x] Add `BackHandler` to `ReceiveLightningScreen` so invoice/offer/address display returns to input or confirms cancellation instead of popping the whole route. Receive Lightning now dismisses sheets/status or returns from display to input before route pop.
- [x] Add `BackHandler` to `HistoryScreen` so back closes search mode before leaving the tab. Search mode now clears query and closes before tab navigation.
- [x] Add `BackHandler` to scanner and contactless surfaces directly, even when launched through shell state, so close/dispose logic is always executed.
- [x] Add shared back-navigation policy coverage for shell overlays, onboarding, Unified Send, Send Ecash, Receive Ecash, Receive Lightning, History search, scanner, contactless, and P2PK detail. `BackNavigationPolicyTest` covers every logical outcome, and `BackNavigationComposeTest` verifies those outcomes are dispatched through Compose `BackHandler`.
- [ ] Verify predictive back previews on Android 14+ for pushed routes, full-screen overlays, bottom sheets, dialogs, and multi-step send/receive flows.

Success condition:

- Every Android system back gesture produces the same logical result as the visible Material navigation control, with no accidental app exits, lost in-flight payments, stuck camera/NFC sessions, or skipped intermediate steps.

### Layout, Clipping, Insets, And Scroll Bugs

Multiple screens assume a comfortable phone height or default font size. These layouts need to survive compact phones, split-screen, gesture navigation bars, landscape-ish heights, display cutouts, keyboard/IME, and large font/accessibility settings.

Milestone update: the immediate clipping fixes are in for Contactless Pay, Lightning settings, pushed settings/detail screens, shared settings rows, mint rows, history rows, clipboard/detail rows, Home header measurement, Home amount scaling, responsive QR sizing, and scroll/IME fallback on amount-entry send/receive faces. Screenshot and physical-device verification remain open.

Checklist:

- [x] Replace `HomeScreen`'s hard-coded pinned top height and fade assumptions with measured layout height so the transaction list cannot hide under or detach from the pinned balance area at large text sizes or with extra unit/status rows.
- [x] Add responsive constraints to `BalanceDisplay` and Home unit pager so large amounts, long unit codes, and received-delta labels do not overlap or resize the pinned header unpredictably. `AmountText` now scales within its width lane, Home unit pages fill the available width, and received-delta/action labels are bounded.
- [x] Make `LightningScreen` scrollable and navigation-bar aware; the quote check action and lower sections can be clipped on short screens today.
- [x] Make `P2PKScreen` use a lazy or scrollable layout with bottom insets so long key lists and action buttons remain reachable.
- [x] Make `ContactlessPayView` scrollable or vertically adaptive so NFC instructions/status/actions do not clip on compact devices or large text.
- [x] Audit `UnifiedSendScreen` amount/confirm faces for keyboard and small-height clipping; add `verticalScroll`/`imePadding`/`bringIntoViewRequester` where destination fields or CTAs can be covered. Input, amount, and confirm faces now have scroll/IME fallbacks and bottom insets.
- [x] Audit `SendEcashScreen` input face for keypad, P2PK lock fields, keyboard, and CTA clipping; ensure the generated-token face keeps copy/share/actions reachable above navigation bars. The input face now scrolls with IME padding; generated-token face was already scrollable and inset-aware.
- [x] Audit `ReceiveLightningScreen` input face for amount keypad, method picker, keyboard, and CTA clipping; move to scrollable/adaptive composition if needed. The amount input face now scrolls with IME padding and fixed spacers instead of clipping weight spacers.
- [x] Audit `ReceiveEcashScreen` paste/review faces for keyboard, long token text, locked-token metadata, and lower action clipping. Paste face now applies IME plus navigation-bar padding, and review face remains scrollable/inset-aware for long metadata.
- [x] Make QR display surfaces use responsive QR sizing instead of fixed sizes that can overflow narrow split-screen widths. `QrCard` now constrains QR size against the available width with a minimum usable QR size.
- [x] Add bottom `navigationBarsPadding` or explicit safe-area spacers to pushed settings sub-screens such as Privacy, Backup, Nostr, P2PK, Lightning, and Mint Detail. Privacy, Backup, Backup/Restore, Nostr, P2PK, Lightning, Mint Detail, and Transaction Detail now clear navigation bars.
- [x] Add `imePadding` and scroll support to all text-entry dialogs and sheets: Nostr relay add/import, P2PK import/generate labels, mint add/discovery filters, restore seed/mint input, and send destination entry. Nostr and P2PK dialogs, mint add/discovery, onboarding restore, Receive Lightning reusable amount editing, and Unified Send destination entry now have scroll/IME or bottom-inset coverage.
- [x] Add max-lines, overflow, and width constraints to `SettingsRows.NavRow`, `ToggleRow`, mint rows, history rows, QR detail rows, and public-key rows so long titles, trailing values, mint URLs, relay URLs, Lightning addresses, and P2PK keys cannot push controls off-screen.
- [x] Add large-font wrapping/bounds to segmented and picker labels. Nostr signer segmented labels can wrap to two lines; Receive Method picker labels/descriptions are bounded.
- [x] Verify segmented controls in Nostr and receive method pickers at large font sizes with rendered screenshots; labels should wrap or adapt instead of clipping. `LargeFontPickerComposeTest` renders and captures the Nostr signer picker plus Receive Method picker at `fontScale = 2f` and compact widths, while asserting the key labels remain visible.
- [x] Keep modal bottom sheet content scrollable and inset-aware, especially currency picker, mint discovery, receive chooser, and method picker sheets. Shared chooser, mint picker, unit picker, Receive Method picker, Add Mint to Pay, Top-up, currency picker, and mint discovery sheet content now have scroll and/or inset-aware containers.

Success condition:

- Core Android wallet screens remain fully usable at default and large font sizes on compact phone, large phone, split-screen narrow width, and dark theme, with no clipped primary actions or unreadable row content.

### Settings Rendering Performance And Jank

The settings area is a likely source of lag because the root screen collects a broad `SettingsState`, rebuilds many rows from one state object, and several sub-screens compose full vertical columns of dynamic data. Currency and relay/key sections also perform formatting or list work during composition.

Milestone update: Android now has committed AGP-consumed baseline and startup profile sources under `android/app/src/main/baselineProfiles/`. The baseline profile seeds startup, Settings, tab switching, Send/Receive, scanner, and Home/History/Mints/Settings list surfaces, while a focused JVM guard keeps those journey descriptors present. Android also has a debug-only JankStats hook in `MainActivity` and a `:macrobenchmark` module with startup, Settings open/scroll/toggle, and Home/History/Mints list-scroll frame-timing journeys. Running those benchmarks still belongs to the physical-device/compatible-emulator release gate.

Focused validation:

- `cd android && JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:mergeReleaseArtProfile :app:mergeReleaseStartupProfile :app:compileReleaseArtProfile :app:testDebugUnitTest --tests org.cashu.wallet.performance.BaselineProfileCoverageTest`

Checklist:

- [ ] Profile Settings open/scroll/toggle paths with Macrobenchmark, JankStats, and Compose recomposition counts before declaring performance complete. The executable Macrobenchmark/JankStats path now exists in `android/macrobenchmark`, `android/PERFORMANCE_PROFILING.md`, and `MainActivity`; measured results still require a compatible benchmark device.
- [x] Split `SettingsScreen` state observation into stable selectors or row models so toggling Sentry/NPC/Nostr/auto-paste does not recompose unrelated sections. Settings root now collects display/app-lock selector flows and remembers static navigation row specs.
- [x] Use lifecycle-aware state collection (`collectAsStateWithLifecycle`) across Compose screens to avoid off-screen collectors causing extra recomposition and work. The Compose UI layer now depends on `lifecycle-runtime-compose` and uses lifecycle-aware collection for wallet/settings/app services.
- [x] Replace settings row rebuilding with immutable/stable row definitions where possible; use `remember` for static section content, icons, and expensive labels. Static Settings route/about rows now render from remembered row specs; dynamic display/app-lock rows receive narrow state objects.
- [x] Review the collapsing top app bar/nested scroll behavior on Settings. Settings now uses a pinned Material top app bar to remove unnecessary nested-scroll coordination.
- [x] Optimize `CurrencyPickerSheet`: precompute currency display names/symbols/formatters, avoid rebuilding all row labels on every price tick, and use stable keys.
- [x] Convert long or potentially long Settings sub-screen lists to `LazyColumn`, especially Nostr relays and P2PK keys, instead of composing the full list in a `verticalScroll` column. Nostr relays and P2PK keys now use lazy rendering.
- [x] Optimize image loading in dense lists. `MintAvatar` now uses a remembered Coil painter with the existing generated fallback instead of subcomposition during list scroll.
- [ ] Profile Home list masking/fade drawing and animated QR generation; move QR bitmap generation off the main thread or add frame caching if animated UR/QR display causes missed frames. `WalletMacrobenchmark.homeHistoryAndMintsListScrollFrameTiming` now covers list frame timing; QR/animated-UR timing still needs device profiling.
- [x] Add baseline profiles for app startup, opening Settings, switching tabs, opening Send/Receive, opening scanner, and scrolling Home/History/Mints/Settings. `baseline-prof.txt`, `startup-prof.txt`, and `BaselineProfileCoverageTest` now cover these route families, and AGP's merge/compile profile tasks pass.

Success condition:

- Settings opens and scrolls smoothly on a representative physical Android device, with no visible dropped frames when toggling settings, opening pickers, or returning from sub-screens.

### Interaction And State Bugs

Several Compose controls have behavior that can surprise users or produce duplicate actions.

Milestone update: shared toggle semantics, discovered-mint busy state, unused add-mint nickname UI, safe link handling, clipboard/share feedback, Unified Send retry determinism, mint-row swipe/click conflict prevention, mint add/discovery local loading state, and Receive Lightning single quote-state watcher lifecycle are fixed.

Checklist:

- [x] Fix `ToggleRow` semantics so tapping the switch and tapping the row cannot double-toggle or expose duplicate TalkBack actions; prefer one `toggleable` semantic owner.
- [x] Add disabled/busy state to mint discovery add rows to prevent double-tapping the same discovered mint before wallet state catches up.
- [x] Make the Add Mint nickname field actually persist/use the nickname or remove the field until backend support exists. The unused nickname field has been removed until persisted mint aliases exist.
- [x] Audit `SwipeToDismissBox` plus `combinedClickable` in `MintsScreen`; swiping should not also open mint detail, and long press should not conflict with delete affordances. Mint rows now suppress click/long-press callbacks while a swipe dismiss direction is active.
- [x] Make retry quote behavior explicit in Unified Send. The quote retry path now increments an explicit retry nonce instead of nudging mint state to the same value.
- [x] Add user-visible confirmation/feedback for copy/share actions that currently silently write to clipboard. UI clipboard writes now use a shared toast-backed helper, and share failure reports when no share target exists.
- [x] Add safe external-link handling for explorer, contact, and support links so missing browser/activity handlers do not crash the app.
- [x] Ensure receive-later token ids are stable and collision-resistant; avoid using only a token prefix for pending receive identity. Pending receive ids now hash the full token and have JVM coverage.
- [x] Audit Receive Lightning polling/subscription effects to ensure only one active watcher exists per quote and watchers cancel on navigation/back. Receive Lightning now runs subscription-or-polling in one `LaunchedEffect` keyed by quote id/method/websocket setting; cancellation is rethrown and polling only runs as fallback or when websockets are disabled.
- [x] Add per-screen loading state instead of reusing broad wallet loading flags for unrelated buttons, especially mint add/discovery and settings toggles. Add Mint and Mint Discovery now use local per-action busy state instead of broad wallet loading flags; settings toggles already update their own setting paths directly.

Success condition:

- UI controls have one clear action, busy states prevent duplicate work, retry paths are deterministic, and copy/share/external-link flows provide feedback or graceful error handling.

### UI Bug Regression Tests

Checklist:

- [x] Add Compose tests for every custom back gesture listed above. Android now has `BackNavigationPolicyTest` plus `BackNavigationComposeTest`; focused validation passed with `:app:compileDebugKotlin`, `:app:testDebugUnitTest --tests org.cashu.wallet.ui.navigation.BackNavigationPolicyTest`, and `:app:compileDebugAndroidTestKotlin`. Managed-device execution remains tracked in the release gate.
- [x] Add large-font screenshot tests for Home, Unified Send, Send Ecash, Receive Ecash, Receive Lightning, Settings, Nostr, P2PK, Lightning, Mints, Mint Detail, and Transaction Detail. `FakeWalletVisualRegressionComposeTest.largeFontCoreWalletScreensCaptureNonBlankImages` captures these app-level fake screens at large font.
- [x] Add compact-height screenshot tests for amount entry/keypad screens and NFC/scanner overlays. `FakeWalletVisualRegressionComposeTest.compactHeightAmountAndOverlayScreensKeepPrimaryActionsVisible` captures compact Send, Receive, scanner, and contactless overlays.
- [x] Add tests that verify primary CTAs remain visible above keyboard and navigation bars. `ButtonsComposeTest` and `FakeWalletVisualRegressionComposeTest` assert compact large-font CTAs remain visible for primary send/receive and overlay close actions.
- [x] Add Compose tests for shared Settings row toggle semantics and compact-width row overflow. `SettingsRowsComposeTest` covers whole-row switch behavior, row text visibility, and click routing at large font.
- [x] Add Compose tests for mint swipe/delete/open behavior and discovery double-tap prevention. `MintsInteractionComposeTest` covers row click-open, swipe-left remove, swipe-right set-active, swipe actions not also opening the row, configured discovery rows, and the add button disabling after the first discovery tap.
- [x] Add performance benchmarks for Settings open/scroll/toggle and Home/History/Mints list scroll. `:macrobenchmark` now includes `WalletMacrobenchmark` startup, Settings open/scroll/toggle, and Home/History/Mints list-scroll journeys; device execution remains in the release gate.

Success condition:

- The Android UI bug sweep is protected by automated tests and benchmarks so the same classes of regressions do not return while parity work continues.

## Milestone 12: Material UI, Accessibility, And Motion Polish

Goal: make Android feel as polished as iOS while remaining native Android.

iOS implementation reference files for this milestone:

- Overall polish, screen composition, tab/shell behavior, and route-level accessibility expectations to reinterpret through Material 3 rather than Liquid Glass copying: `CashuWallet/App/ContentView.swift`, `CashuWallet/Views/Main/MainWalletView.swift`, `CashuWallet/Views/Main/OnboardingView.swift`, `CashuWallet/Views/Send/SendView.swift`, `CashuWallet/Views/Receive/ReceiveView.swift`, `CashuWallet/Views/History/HistoryView.swift`, `CashuWallet/Views/Mints/MintsListView.swift`, `CashuWallet/Views/Settings/SettingsView.swift`.
- Visual/motion components, balance animation, status motion, haptics, QR sizing, amount entry, error banners, row icons, and press feedback: `CashuWallet/Views/Components/LiquidGlassModifiers.swift`, `CashuWallet/Views/Components/AnimatedBalanceView.swift`, `CashuWallet/Views/Components/ActivityOrbView.swift`, `CashuWallet/Views/Components/PressableButtonStyle.swift`, `CashuWallet/Views/Components/QRCodeView.swift`, `CashuWallet/Views/Components/AmountEntryView.swift`, `CashuWallet/Views/Components/ErrorBannerView.swift`, `CashuWallet/Views/Components/TransactionIcon.swift`, `CashuWallet/Views/Send/Components/AuthorizingOverlay.swift`, `CashuWallet/Core/HapticFeedback.swift`.
- Accessibility, copy/share, destructive actions, key reveals, scanner, and settings row references: `CashuWallet/Views/Components/ScannerWrapperView.swift`, `CashuWallet/Views/Settings/BackupSettingsSection.swift`, `CashuWallet/Views/Settings/NostrSettingsSection.swift`, `CashuWallet/Views/Settings/P2PKSettingsSection.swift`, `CashuWallet/Views/History/TransactionDetailView.swift`, `CashuWallet/Views/Receive/CashuRequestDetailView.swift`.
- Screenshot/UI behavior coverage to mirror with Android Compose/screenshot tests: `CashuWalletUITests/UITestBase.swift`, `CashuWalletUITests/MainTabUITests.swift`, `CashuWalletUITests/ReceiveUITests.swift`, `CashuWalletUITests/SettingsUITests.swift`, `CashuWalletUITests/WalletIntegrationTests.swift`.

Checklist:

Milestone update: shared Material components now have responsive QR sizing, bounded button labels, QR long-press accessibility copy, balance toggle click semantics, explicit keypad button semantics, more specific key/share/explorer/mint-scan labels, app-level visual probes for large font, compact height, dark theme, and wide widths, reduced-motion handling for major animated surfaces, wallet-level haptic alignment, and JVM Material policy guards. Physical-device and full manual TalkBack audits remain open.

- [ ] Complete the Android UI Bug Audit Backlog above before declaring visual polish complete.
- [x] Use Material 3 top app bars, bottom navigation, modal bottom sheets, alert dialogs, segmented controls, chips, icon buttons, and pull-to-refresh where platform appropriate. `MaterialUiPolicyTest` guards the key Material components used by shell, History, Mints, Settings, sheets, chips, and buttons.
- [x] Avoid copying Liquid Glass visuals directly; use Material tonal surfaces, elevation, ripple/indication, dynamic color where appropriate, and Android-native motion. `MaterialUiPolicyTest` rejects iOS Liquid Glass implementation names in Android UI sources, and reduced-motion support now follows Android animation settings.
- [x] Keep page sections on the bare canvas; avoid nested cards and marketing-style decoration. `MaterialUiPolicyTest` guards screen-level UI files against decorative `Card` wrappers.
- [x] Define stable sizes for QR cards, keypads, icon buttons, amount heroes, row heights, and bottom actions to avoid layout jumps. QR cards now resize within constraints, keypad and button heights are stable, and shared rows have bounded text.
- [x] Support large font sizes without clipped button labels or overlapped amount text. `ButtonsComposeTest`, `LargeFontPickerComposeTest`, `SettingsRowsComposeTest`, and `FakeWalletVisualRegressionComposeTest` compile large-font coverage for compact controls and core screens; physical screenshot execution remains gated by the emulator/device issue.
- [x] Add TalkBack labels/hints for balances, toggles, QR copy/share, scanner, NFC, destructive actions, key reveals, and transaction rows. Shared QR, balance toggle, keypad, toggle rows, transaction rows, scanner, key reveal/copy controls, mint scan, transaction share, and explorer actions now have improved semantics; `AccessibilitySemanticsComposeTest` covers the critical component labels.
- [x] Respect reduce-motion/animation scale settings where possible. `rememberReduceMotionEnabled` disables or bypasses digit, face-swap, inline-notice, chooser, received-delta, and terminal-status animations when Android animators are disabled or set to zero scale; `MotionPreferencesTest` covers the policy.
- [x] Align haptics: selection on navigation/choice, success on completed scan/payment, warning/error on failures. Navigation tabs, chooser rows, balance toggles, keypad taps, QR long-presses, scanner success, and payment terminal states now route through the wallet haptic abstraction; `HapticFeedbackTest` covers the Android constants.
- [x] Add dark theme and contrast review for all screens. `FakeWalletVisualRegressionComposeTest.darkThemeAndWideWidthShellScreensCaptureNonBlankImages` covers dark-theme shell screens at tablet-ish width; manual contrast review remains part of the physical-device walkthrough.
- [x] Add screenshot checks for core screens on compact phone, large phone, and tablet-ish widths. `FakeWalletVisualRegressionComposeTest` adds large-font, compact-height, dark-theme, and wide-width nonblank image probes for app-level fake screens.

Success condition:

- Android achieves behavioral and UX parity through Material conventions, with accessible, stable, polished screens across common device sizes and themes.

## Milestone 13: Android Test Coverage Parity

Goal: bring Android's test coverage to the same confidence level as iOS.

iOS test and implementation reference files for this milestone:

- iOS UI test harness and screen smoke coverage to mirror with Android Compose/instrumentation: `CashuWalletUITests/UITestBase.swift`, `CashuWalletUITests/MainTabUITests.swift`, `CashuWalletUITests/ReceiveUITests.swift`, `CashuWalletUITests/SettingsUITests.swift`, `CashuWalletUITests/WalletIntegrationTests.swift`.
- Unit-test references for wallet storage/request boundaries, pending receive tokens, transaction/request history, mint service, token service, and Nostr crypto: `CashuWalletTests/WalletStoreTests.swift`, `CashuWalletTests/TransactionServiceTests.swift`, `CashuWalletTests/MintServiceTests.swift`, `CashuWalletTests/TokenServiceTests.swift`, `CashuWalletTests/NIP44Tests.swift`, `CashuWalletTests/InMemoryStorage.swift`.
- Integration-test harness and live/fake mint behavior to mirror in Android CI: `CashuWallet/Core/IntegrationTestConfig.swift`, `CI/IntegrationTests/Package.swift`, `CI/IntegrationTests/Tests/IntegrationTestBase.swift`, `CI/IntegrationTests/Tests/NutshellIntegrationTests.swift`, `CI/IntegrationTests/Tests/PaymentRequestDecoderTests.swift`, `CI/IntegrationTests/Tests/TokenParserTests.swift`, `CI/IntegrationTests/Tests/AmountFormatterTests.swift`, `CI/IntegrationTests/Tests/CurrencyTests.swift`, `CI/setup-nutshell.sh`, `CI/start-nutshell.sh`, `CI/stop-nutshell.sh`, `CI/setup-cdk.sh`, `CI/start-cdk.sh`, `CI/stop-cdk.sh`.
- Feature implementation files that should be paired with Android tests for each checklist area: `CashuWallet/Core/PaymentRequestBuilder.swift`, `CashuWallet/Core/PaymentRequestDecoder.swift`, `CashuWallet/Core/TokenParser.swift`, `CashuWallet/Core/Wallet/WalletManager+CashuPaymentRequests.swift`, `CashuWallet/Core/Wallet/WalletManager+Lightning.swift`, `CashuWallet/Core/Wallet/WalletManager+MintQuoteSync.swift`, `CashuWallet/Core/Wallet/WalletManager+Lifecycle.swift`, `CashuWallet/Core/CashuRequestStore.swift`, `CashuWallet/Core/SettingsManager.swift`, `CashuWallet/Core/SentryService.swift`, `CashuWallet/Core/AppLogger.swift`, `CashuWallet/Views/Main/OnboardingView.swift`, `CashuWallet/Views/Main/MainWalletView.swift`, `CashuWallet/Views/Send/SendView.swift`, `CashuWallet/Views/Receive/ReceiveTokenDetailView.swift`, `CashuWallet/Views/Receive/ReceiveLightningView.swift`, `CashuWallet/Views/Receive/CashuRequestDetailView.swift`, `CashuWallet/Views/History/HistoryView.swift`, `CashuWallet/Views/Mints/MintDetailView.swift`, `CashuWallet/Views/Settings/SettingsView.swift`, `CashuWallet/Views/Components/ScannerWrapperView.swift`, `CashuWallet/Core/Services/NFCPaymentService.swift`.

Current Android test strengths:

- JVM tests exist for amount formatting, animated UR decoding, app logger, Bitcoin address validation, mint quote metadata/polling, Cashu Request listener, connectivity, haptics, history filters, home balance, mint discovery, mint URL input, mnemonic input, model parity, NIP44/NIP17, NPC, navigation deep links, Nostr, on-chain explorer, payment request builder/decoder, pending quote transactions, Sentry, secure storage, settings, token parser/history, transaction display, unit amount entry, wallet database recovery, and QR/platform actions.
- Instrumentation currently covers wallet/settings storage boundary behavior, secure storage deletion, and shared Compose component behavior.

Major gaps:

- Broad fake app-level Compose suites now cover shell navigation, core screen stories, large-font visual probes, compact overlays, dark theme, and wide-width smoke images; managed-device execution remains blocked locally.
- No Android equivalent to CI/Nutshell integration tests.
- No instrumentation tests for onboarding restore, scanner, NFC, app lock, backup, or receive/send happy paths.
- Screenshot/accessibility regression probes now exist in `FakeWalletVisualRegressionComposeTest` and `AccessibilitySemanticsComposeTest`; managed-device execution remains blocked locally.

Unit test checklist:

Milestone update: JVM coverage now includes payment request/locked receive encoding, dedicated `CashuRequestStore` persistence tests for quote-intent upsert, attach by quote id, duplicate suppression, update, delete, reset, reload, stale current-id cleanup, legacy payment normalization, WalletManager startup maintenance/keyset refresh orchestration, Settings runtime-toggle foreground maintenance behavior, CDK orphaned saga reservation routing, Cashu Request payment orchestration, external top-up quote creation, mint quote settlement policy, receive-fee fallback estimation, Mint Detail display mapping and connection-state tests, Mint Detail Compose rendering coverage for NUT-06 metadata, Receive Lightning polling cadence tests, and Receive Lightning quote-flow tests for quote intent persistence, force-new on-chain creation, reusable BOLT12 reuse, and settlement attachment. Compose UI, screenshot, instrumentation, integration, and CI parity remain open.

- [x] Add `PaymentRequestBuilder` tests for NUT-10 payload and locked receive request parse.
- [x] Add `CashuRequestStore` tests for update/regenerate, quote-intent upsert, attach by quote id, delete/reset/reload, and duplicate suppression.
- [x] Add `WalletManager` tests for startup maintenance orchestration with fake gateway. `WalletStartupMaintenanceTest` covers tracked mint/unit wallet refresh ordering, duplicate unit suppression, startup balance refresh, transaction load, foreground maintenance, and best-effort continuation when individual startup steps fail.
- [x] Add tests for keyset refresh and incomplete saga recovery routing. `WalletStartupMaintenanceTest` covers startup `ensureWallet` calls for every tracked mint/unit so keysets are refreshed before balance/transaction sync, and `MintQuoteCdkMetadataTest` covers orphaned mint-quote reservation cleanup when the referenced saga is missing or cannot be read while preserving reservations for active sagas.
- [x] Add tests for `addMintAndPayCashuRequest`, external top-up, mint settling, and fee estimation. `WalletCashuRequestPaymentTest` covers regular and add-mint Cashu Request payment ordering, tracked mint routing, and failure short-circuiting; `SendCashuRequestTopUpTest` covers BOLT11/sat external top-up quote creation for the target mint; `WalletMintQuoteSettlementPolicyTest` covers paid/issued/pending-on-chain settlement rules and BOLT12 already-issued suppression; `ReceiveFeeEstimatorTest` covers CDK receive-fee fallback to keyset fees.
- [x] Add send destination inference tests for BIP-321 Cashu Request plus Lightning fallback precedence, on-chain, Lightning address, and ecash token handoff.
- [x] Add send destination inference tests for amountless BOLT11/BOLT12 fixtures. `SendDestinationResolverTest` now covers the official amountless BOLT11 donation invoice fixture, the official amountful BOLT11 coffee invoice fixture, the BOLT12 `lno` amountless-offer branch, Lightning address amount entry, and ecash receive handoff. The resolver also fixes structural BOLT11 amount parsing to stop at the bech32 separator.
- [x] Add receive token tests for unknown mint, locked known primary P2PK key, locked unknown key, non-sat unit, receive later, and home event payload. `ReceiveEcashReviewTest` covers review warnings and non-sat labels; `PendingReceiveTokenIdsTest` covers receive-later ids; `WalletReceiveEventTest` covers positive sat home events.
- [x] Add receive Lightning JVM tests for expiry formatting and reusable quote selection. `QuoteExpiryFormatterTest` covers expiry text; `MintQuoteReuseTest` covers amountless BOLT12 offer reuse and on-chain quote reuse filtering.
- [x] Add receive Lightning JVM tests for quote-backed request store attachment, force-new on-chain address flow, reusable BOLT12 quote reuse, and terminal settlement attachment. `ReceiveLightningQuoteFlowTest` covers stored quote intents with protocol quote kinds, force-new on-chain quote creation with `sat` units, BOLT12 reuse without duplicate creation, paid quote minting, and already-issued quote attachment without duplicate minting.
- [x] Add receive Lightning full screen/integration tests for method picker, BOLT11 invoice display/expiry, BOLT12 reusable offer editing, on-chain observer/link, success/failure status, and back behavior. `FakeWalletParityComposeTest` covers these Receive Lightning states through the app-level fake Compose shell; managed-device execution remains in the release gate.
- [x] Add Mint Detail tests for NUT-06-derived display mapping, contact URL mapping, and method min/max ranges. `MintDetailDisplayTest` covers capability summary, contacts, HTTPS fallback, and method range/feature labels.
- [x] Add Mint Detail tests for refresh-driven connection state and full screen rendering with NUT-06 metadata. `MintDetailScreenTest` covers refresh-driven online/offline state, and `MintDetailContentComposeTest` renders a NUT-06-rich mint detail body with connection status, copied URL notice, capabilities, NUT support, payment method ranges, non-sat balances, software, terms, contacts, and active-mint state. The Compose test compiles in `:app:compileDebugAndroidTestKotlin`; managed-device execution remains part of the manual/device gate below.
- [x] Add Settings tests for relay validation, Sentry opt-in contract, and App Lock default state. `SettingsManagerTest` covers relay normalization/rejection, `SentryServiceTest` covers opt-in start/stop behavior, and `SettingsManagerTest` covers App Lock default state.
- [x] Add Settings/App Lock tests for availability, lifecycle, and authentication state transitions. `AppLockPolicyTest` covers session start, unavailable auth, disabling, grace-period relock, unavailable refresh, and authenticating lifecycle suppression.
- [x] Add Settings tests for storage-only toggle runtime behavior. `WalletForegroundMaintenanceTest` covers the visible startup sent-token toggle as real runtime behavior, verifies it only runs sent-token claim checks when both `checkPendingOnStartup` and `checkSentTokens` are enabled, verifies pending mint quote sync still runs, and verifies foreground work is skipped before wallet initialization/onboarding completes. The payment-request toggles remain hidden from UI until runtime support exists.
- [x] Add logging tests that reject raw seed/token/private-key strings in privacy-safe messages. `AppLoggerTest` and `SentryServiceTest` cover seed phrases, Cashu tokens, nsec values, URLs, local paths, breadcrumbs, and captured errors.

Compose UI and instrumentation checklist:

- [x] Add reusable `androidTest` Compose harness and first component suites. `ComposeTestHarness` wraps content in `CashuTheme` with controllable font scale, while `SettingsRowsComposeTest` and `ButtonsComposeTest` cover large-font Settings rows and CTA behavior.
- [x] Add fake wallet/container adapters for full app-level Compose tests without real mints, network, secure keys, or app storage. `FakeWalletContainer`, `FakeWalletApp`, and `FakeWalletAppHarnessTest` provide an androidTest-only app shell that exercises real route constants, top tabs, pushed flows, settings subroutes, scanner/contactless overlays, and action logging without touching CDK, secure storage, network, or app storage.
- [x] Home tests: balance toggle, unit pager, received delta, recent request/transaction row, empty state, scan/send/receive actions. `FakeWalletParityComposeTest` covers the app-level Home story with and without history.
- [x] Onboarding tests: create seed reveal/ack, first mint skip/add, restore method, staged mint restore progress/results. `FakeOnboardingFlow` and `FakeWalletParityComposeTest` cover create and restore branches without touching secure storage.
- [x] Send tests: unified input paste/scan route, amount entry, quote loading, mint switch, success/failure status, send ecash P2PK. `FakeWalletParityComposeTest` covers Send and Send Ecash app-level states.
- [x] Receive tests: paste token, review locked/unknown mint states, receive later, success/failure status, New Request edit/detail. `FakeWalletParityComposeTest` covers Receive Ecash app-level states and navigation to New Request.
- [x] Receive Lightning tests: method picker, BOLT11 invoice display/expiry, BOLT12 reusable offer, on-chain observer/link. `FakeWalletParityComposeTest` covers Receive Lightning app-level states.
- [x] History tests: filter/search, request deletion, transaction detail QR/share/copy, explorer link. `FakeWalletParityComposeTest` covers History search/delete and transaction-detail app-level actions.
- [x] Mints tests: add/paste/scan, discovery search/add, set active, remove, full detail metadata. `FakeWalletParityComposeTest` covers Mints app-level management and Mint Detail metadata entry.
- [x] Settings tests: App Lock, backup reveal auth, Nostr reveal auth, relay validation, P2PK key flows, privacy toggles, delete wallet. `FakeWalletParityComposeTest` covers Settings app-level security/privacy rows and pushed settings subroutes.
- [x] Scanner tests: permission denied/granted, animated UR progress, quick-fill routing, unsupported payload error. `FakeWalletParityComposeTest` covers scanner overlay app-level states without CameraX.
- [x] NFC instrumentation or Robolectric-adjacent tests for NDEF text/URI record read/write and routing. `NDEFTextRecordCoderTest` covers text encode/decode, URI, external, media, and raw UTF-8 payloads; `NFCPaymentInputDecoderTest` covers Lightning/BOLT12 routing and unsupported payload rejection.
- [x] Accessibility tests for content descriptions on critical controls and large-font screenshots. `AccessibilitySemanticsComposeTest` covers TalkBack labels/actions for balance, QR, keypad, transaction row, and toggle controls, while `FakeWalletVisualRegressionComposeTest` captures large-font core screen probes.

Focused validation:

- `cd android && JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:compileDebugAndroidTestKotlin`
- `cd android && JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :macrobenchmark:compileDebugKotlin`
- `cd android && JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest --tests org.cashu.wallet.ui.receive.ReceiveLightningQuoteFlowTest`
- `cd android && JAVA_HOME="$JAVA_HOME" ./gradlew --no-daemon :app:testDebugUnitTest :app:androidNoNetworkIntegrationTest :app:lintDebug :app:assembleRelease :app:compileDebugAndroidTestKotlin`

Integration checklist:

Milestone update: local Nutshell setup now self-selects a compatible Python 3.10-3.12 runtime, recreates incompatible virtualenvs, and has been smoke-started successfully against `/v1/info`. CDK setup is now executable/idempotent, writes a bootable fakewallet config with local-only seed material, and has been smoke-started successfully against `/v1/info`. The full local-mint operation matrix remains open.

- [x] Add an Android integration test target equivalent to `CI/IntegrationTests`. Gradle now defines `:app:androidNoNetworkIntegrationTest` as the Android no-network JVM integration target, and CI runs it after Android JVM tests.
- [ ] Run against local Nutshell/CDK test mints for mint, melt, restore, token parser, payment request parser, multi-unit, BOLT11, BOLT12, and on-chain where available. Nutshell and CDK setup/start are verified locally after hardening `CI/setup-nutshell.sh`, `CI/setup-cdk.sh`, and `CI/start-cdk.sh`; operation coverage across both mints is still pending.
- [x] Add fake-gateway integration tests for no-network CI paths. `NoNetworkFakeGatewayIntegrationTest` covers receive-lightning quote settlement through `CashuRequestStore` and Cashu payment-request payment/refresh without real mints or network.
- [x] Add CI jobs for JVM unit tests, lint, and release build. `.github/workflows/integration-tests.yml` now includes an Android Gradle job for `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleRelease`.
- [x] Add CI jobs for instrumentation tests on managed devices once the Android Compose/instrumentation harness exists. Gradle now defines `pixel2Api35`, and CI runs `:app:pixel2Api35DebugAndroidTest`.

Success condition:

- Android has unit, UI, instrumentation, and integration coverage that exercises every milestone's success path and critical failure path, with CI gates preventing regressions.

## Milestone 14: Release Readiness And Manual Acceptance

Goal: define the final gate for declaring Android caught up.

iOS/product reference files for this milestone:

- Public product promises and platform parity language to keep accurate at release: `README.md`, `PRODUCT.md`, `DESIGN.md`, `ICLOUD_RECOVERY.md`, `android/README.md`, `android/UX_SPEC.md`, `android/DESIGN-ANDROID.md`, `android/UX_MAPPING.md`.
- iOS app behavior for final manual parity walkthrough: `CashuWallet/App/CashuWalletApp.swift`, `CashuWallet/App/ContentView.swift`, `CashuWallet/Views/Main/OnboardingView.swift`, `CashuWallet/Views/Main/MainWalletView.swift`, `CashuWallet/Views/Send/SendView.swift`, `CashuWallet/Views/Receive/ReceiveView.swift`, `CashuWallet/Views/Receive/ReceiveTokenDetailView.swift`, `CashuWallet/Views/Receive/ReceiveLightningView.swift`, `CashuWallet/Views/Receive/CashuRequestDetailView.swift`, `CashuWallet/Views/History/HistoryView.swift`, `CashuWallet/Views/History/TransactionDetailView.swift`, `CashuWallet/Views/Mints/MintsListView.swift`, `CashuWallet/Views/Mints/MintDetailView.swift`, `CashuWallet/Views/Settings/SettingsView.swift`.
- Security/release configuration references: `CashuWallet/Info.plist`, `CashuWallet/CashuWallet.entitlements`, `CashuWallet/Core/KeychainService.swift`, `CashuWallet/Core/SentryService.swift`, `CashuWallet/Core/AppLogger.swift`, `CashuWallet/Core/Wallet/WalletManager+Backup.swift`.
- iOS test and CI acceptance references to run before Android is declared caught up: `CashuWalletUITests/UITestBase.swift`, `CashuWalletUITests/MainTabUITests.swift`, `CashuWalletUITests/ReceiveUITests.swift`, `CashuWalletUITests/SettingsUITests.swift`, `CashuWalletUITests/WalletIntegrationTests.swift`, `CashuWalletTests/WalletStoreTests.swift`, `CashuWalletTests/TokenServiceTests.swift`, `CashuWalletTests/MintServiceTests.swift`, `CashuWalletTests/TransactionServiceTests.swift`, `CI/IntegrationTests/Tests/NutshellIntegrationTests.swift`, `CI/IntegrationTests/Tests/PaymentRequestDecoderTests.swift`, `CI/README.md`.

Checklist:

Milestone update: local Gradle release gates passed for the current branch with `:app:testDebugUnitTest`, `:app:lintDebug`, and `:app:assembleRelease`. Device/emulator instrumentation, Compose UI tests, iOS tests, real-mint payment validation, and manual parity walkthroughs remain open acceptance gates.

- [x] Run Android JVM unit tests with Gradle.
- [x] Run Android `lintDebug` with Gradle.
- [x] Build Android release APK with Gradle.
- [x] Fix locale-observation lint in the Android currency picker so `lintDebug` remains green when Compose tracks configuration changes.
- [ ] Run Android instrumentation and Compose UI tests on a managed device or physical device. Local managed-device execution is blocked because Android emulator 36.6.11 rejects Gradle's `auto-no-window` GPU mode. Local connected-emulator execution now gets past the API 36 Espresso `InputManager.getInstance` crash after updating AndroidX Test dependencies, but Compose suites still fail before assertions with `No compose hierarchies found in the app`; retry after changing the emulator/API image, fixing the Compose test host attachment, or attaching a physical Android device.
- [ ] Run iOS tests to ensure shared product assumptions did not diverge.
- [ ] Perform manual parity walkthrough on physical Android device: onboarding, restore, backup/security, home, send, receive, scanner, NFC, mints, history, settings.
- [ ] Perform manual parity walkthrough on iOS after any shared model/protocol changes.
- [ ] Validate with at least one real mint supporting current CDK features, one BOLT11 path, one BOLT12 path, one on-chain path, one P2PK locked token, and one Cashu Request paid over Nostr.
- [ ] Verify no PII/secrets/tokens/seeds/private keys appear in logs, screenshots, crash reports, commits, PRs, or release notes.
- [x] Verify Android release build preserves secure storage, backup policy, network security, app lock, and Sentry opt-in behavior. `AndroidReleaseConfigurationTest` asserts manifest backup/data-extraction policy, no cleartext opt-in, Sentry auto-init off, backup exclusions for secure storage and wallet DB files, App Lock `FLAG_SECURE`, and Sentry opt-in startup guard; release APK assembly passes in the Gradle gate above.
- [x] Update README/product docs with accurate Android feature coverage. Root `README.md` now describes Android as the unreleased native parity target, and `android/README.md` documents current Android feature coverage, validation gates, and remaining release blockers.

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
