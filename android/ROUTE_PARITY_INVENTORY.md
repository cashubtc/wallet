# Android Route Parity Inventory

Status date: 2026-07-07

This inventory maps the current Android navigation graph to the iOS screen or flow it must match. Milestone ownership points back to `ANDROID_UPDATE_PLAN.md`.

## Top-Level Tabs

| Android route | Android screen | iOS target | Owner milestone | Notes |
| --- | --- | --- | --- | --- |
| `home` | `ui/home/HomeScreen.kt` | `Views/Main/MainWalletView.swift` | Milestone 3 | Wallet home, active mint, balance/unit pager, recent timeline, scan/send/receive entry points. |
| `history` | `ui/history/HistoryScreen.kt` | `Views/History/HistoryView.swift` | Milestone 9 | Transaction/request timeline, search/filter/date sections, stale quote refresh, row actions. |
| `mints` | `ui/mints/MintsScreen.kt` | `Views/Mints/MintsListView.swift` | Milestone 8 | Mint list, active mint, discovery, add/paste/scan, remove/set-active actions. |
| `settings` | `ui/settings/SettingsScreen.kt` | `Views/Settings/SettingsView.swift` | Milestone 10, Milestone 12 | Settings root, display, backup/security, payments, integrations, privacy, about, danger. |

## Pushed Payment Routes

| Android route | Android screen | iOS target | Owner milestone | Notes |
| --- | --- | --- | --- | --- |
| `send` | `ui/send/UnifiedSendScreen.kt` | `Views/Send/SendView.swift` | Milestone 4 | Unified destination input for Lightning, BOLT12, on-chain, Cashu Requests, and ecash token handoff. |
| `send/ecash` | `ui/send/SendEcashScreen.kt` | `Views/Send/SendView.swift` ecash flow | Milestone 4, Milestone 7 | Ecash send, memo, unit, mint, P2PK lock, token display, claim polling. |
| `receive/ecash` | `ui/receive/ReceiveEcashScreen.kt` | `Views/Receive/ReceiveView.swift`, `ReceiveTokenDetailView.swift` | Milestone 5, Milestone 7 | Paste/scan token, review/receive later, Cashu Request creation entry, locked receive handling. |
| `receive/lightning` | `ui/receive/ReceiveLightningScreen.kt` | `Views/Receive/ReceiveLightningView.swift` | Milestone 6 | BOLT11/BOLT12/on-chain quote creation, QR/copy/share, expiry/status, minting paid quotes. |
| `receive/locked-ecash` | `ui/receive/ReceiveLockedEcashScreen.kt` | `Views/Receive/ReceiveView.swift` locked-key sheet | Milestone 7 | NUT-18 locked receive request QR, copy, share, regenerate, and unavailable state. |

## Detail Routes

| Android route | Android screen | iOS target | Owner milestone | Notes |
| --- | --- | --- | --- | --- |
| `mints/{mintUrl}` | `ui/mints/MintDetailScreen.kt` | `Views/Mints/MintDetailView.swift` | Milestone 8 | Full mint metadata, capabilities, contacts, ToS, software, methods, balances. |
| `history/transaction/{transactionId}` | `ui/history/TransactionDetailScreen.kt` | `Views/History/TransactionDetailView.swift` | Milestone 9 | Canonical row order, copy/share/explorer, QR conditions, settled/pending states. |
| `request/{requestId}` | `ui/receive/CashuRequestDetailScreen.kt` | `Views/Receive/CashuRequestDetailView.swift` | Milestone 5 | Request QR/detail, edit/regenerate, delete, paid attachment, quote-backed status. |

## Settings Sub-Screens

| Android route | Android screen | iOS target | Owner milestone | Notes |
| --- | --- | --- | --- | --- |
| `settings/backup-restore` | `ui/settings/BackupRestoreScreen.kt` | `Views/Settings/BackupSettingsSection.swift`, onboarding restore | Milestone 1, Milestone 2 | Backup/restore entry, restore wizard handoff, cloud backup decision. |
| `settings/backup` | `ui/settings/BackupScreen.kt` | `Views/Settings/BackupSettingsSection.swift` | Milestone 1 | Seed reveal/copy must be authenticated; cloud backup status/actions. |
| `settings/lightning` | `ui/settings/LightningScreen.kt` | `Views/Settings/LightningAddressSettingsSection.swift` | Milestone 6, Milestone 10 | NPC/npub.cash status, address copy, claim settings, manual quote checks. |
| `settings/p2pk` | `ui/settings/P2PKScreen.kt` | `Views/Settings/P2PKSettingsSection.swift` | Milestone 7, Milestone 10 | Seed-derived primary key, imported/generated keys, backup/reveal, labels, QR. |
| `settings/nostr` | `ui/settings/NostrScreen.kt` | `Views/Settings/NostrSettingsSection.swift` | Milestone 1, Milestone 10 | Seed/custom signer, authenticated nsec reveal, relay validation/reset/copy. |
| `settings/privacy` | `ui/settings/PrivacyScreen.kt` | `Views/Settings/PrivacySettingsSection.swift` | Milestone 10, UI bug sweep | Privacy/network/diagnostics toggles; no visible storage-only promises. |

## Shell Overlays And External Entrypoints

| Android surface | Android screen/service | iOS target | Owner milestone | Notes |
| --- | --- | --- | --- | --- |
| Scanner overlay | `Views/Components/ScannerView.kt`, shell scanner state | `Views/Components/ScannerWrapperView.swift` | Milestone 3, UI bug sweep, Milestone 13 | Camera permission/failure, animated UR, restricted/callback routing, Android back close, haptics. |
| Contactless overlay | `Views/Send/ContactlessPayView.kt`, NFC services | `Core/Services/NFCPaymentService.swift`, `NFCReaderDelegate.swift` | Milestone 4, UI bug sweep, Milestone 13 | Android NFC reader/writer, CReq/BOLT11 fallback routing, Android back close, hardware tests. |
| Deep links | `Core/Navigation/NavigationManager.kt`, `CashuApp.kt` | iOS URL/open-url handling | Milestone 3, Milestone 4, Milestone 5 | `cashu:` token/request routing, send/receive/mints handoff, payload clearing. |

## Backend Ownership

| Android area | iOS target | Owner milestone |
| --- | --- | --- |
| `Core/Wallet/WalletManager.kt` startup, balance, transaction load, sagas, stale quotes | iOS wallet services and startup maintenance | Milestone 1, Milestone 11 |
| `Core/CashuRequestStore.kt`, `CashuRequestListener.kt` | iOS Cashu request store/listener | Milestone 5, Milestone 11 |
| `Core/PaymentRequestBuilder.kt`, `PaymentRequestDecoder.kt` | iOS NUT-18/NUT-10 request helpers | Milestone 5, Milestone 7, Milestone 11 |
| `Core/Wallet/WalletMintMetadataFetcher.kt`, mint models | iOS mint info detail mapping | Milestone 8 |
| `Core/SettingsManager.kt`, `SettingsStore.kt` | iOS settings/app lock/secret stores | Milestone 1, Milestone 10 |
| Android tests under `app/src/test` and `app/src/androidTest` | iOS unit/UI/integration tests | Milestone 13 |
