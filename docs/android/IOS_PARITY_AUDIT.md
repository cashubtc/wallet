# iOS → Android file parity audit

This is the living source-of-truth for the file-by-file comparison requested
for Android. A row is marked **Reviewed** only after the current Swift source
and all named Kotlin counterparts have been read together. Existing migration
checklists are historical input; their checkmarks are not accepted without a
fresh source review.

Android preserves feature semantics, copy intent, trust decisions, and state
transitions from iOS. Presentation is translated to first-class Android and
Material 3 patterns where the platform idioms differ.

## Batch 1 — app root and incoming NUT-18 consent

| iOS source | Android counterpart(s) | Status | Result |
| --- | --- | --- | --- |
| `App/CashuWalletApp.swift` | `ui/shell/CashuApp.kt`, `App/MainActivity.kt`, `Core/PriceService.kt`, `Core/NPCService.kt` | Reviewed | Foreground now re-arms pending-token/mint-quote checks, Cashu Request listening, npub.cash polling, and price refresh. Background stops foreground-only listeners/timers. Android uses lifecycle events and keeps the existing secure-window/app-lock implementation. NWC startup is explicitly deferred because Android has no NWC feature yet. |
| `App/ContentView.swift` | `ui/shell/CashuApp.kt`, `ui/receive/ReceiveEcashDetailScreen.kt` | Reviewed | Added item-owned approval presentation for held NUT-18 payments. It reuses the native full-screen receive review, preserves the terminal through claim completion, supports Decline, and does not interrupt an already-open receive flow. |
| `Core/CashuRequestListener.swift` | `Core/CashuRequestListener.kt`, `Core/NostrInboxClient.kt` | Reviewed | Replaced unconditional redemption with the iOS trust policy: auto-claim only for known mints; otherwise persist and ask. Added the fixed seven-day NIP-59 lookback, bounded durable event-ID de-duplication, a 50-item listener-held backlog cap, wallet-boundary invalidation, and eligible held-payment claiming. |
| `Models/Tokens/TokenTransferModels.swift` | `Models/Tokens/TokenTransferModels.kt` | Reviewed | `PendingReceiveToken` now persists `unit`, `cashuRequestId`, and `memo` with defaults that decode old Android records. |
| `Core/Wallet/WalletManager+Tokens.swift` | `Core/Wallet/WalletManager.kt`, `Core/WalletStore.kt`, `Core/TokenHistoryTransactions.kt` | Reviewed | Held claims route through Cashu Request attribution. Attribution now records the new CDK transaction ID rather than a relay event ID. Pending receive saves de-duplicate by token while preserving original identity/date. |
| `Views/Receive/ReceiveTokenDetailView.swift` | `ui/receive/ReceiveEcashDetailScreen.kt`, `ui/receive/ReceiveTokenReview.kt` | Reviewed | Added injectable claim/secondary actions so listener-held payments use the same review and status UI without bypassing attribution or queue follow-up. Both Android presentations now show the iOS trust warning before an unknown mint is added. |
| `Views/Settings/PrivacySettingsSection.swift` | `ui/settings/PrivacyScreen.kt`, `ui/components/SettingsRows.kt` | Reviewed | Added both payment-request toggles with matching help text and disabled-state behavior. |

## Coupled files inspected, targeted review still open

These were read where required by Batch 1 but have broader responsibilities;
they are not yet marked fully reviewed:

- `Core/SettingsManager.swift` ↔ `Core/SettingsManager.kt`
- `Core/SettingsStore.swift` ↔ `Core/SettingsStore.kt`
- `Core/Protocols/StorageProtocol.swift` ↔ `Core/Protocols/StorageProtocol.kt`
- `Core/Wallet/WalletManager.swift` and the remaining `WalletManager+*.swift`
  extensions ↔ `Core/Wallet/WalletManager.kt` and its Android helper files
- `Core/Services/TransactionService.swift` ↔ Android transaction helpers

## Explicit deferred features

- **Nostr Wallet Connect (NIP-47):** skip during parity adaptation until it can
  be implemented end to end. Android currently has no NWC runtime or UI.
- **Cloud seed backup:** requires an Android product/storage decision.
- **Physical NFC and camera validation:** implementation can be audited in
  source, but final verification requires real Android hardware.

## Next review order

1. Finish Settings/Storage and all wallet-domain extension files.
2. Models, parsers, services, and navigation.
3. Shared visual components.
4. Main/onboarding/history/mints.
5. Receive, send, and settings surfaces.

## Batch 1 verification

- `:app:testDebugUnitTest`: 266 tests passed.
- `:app:lintDebug`: passed with no errors (51 existing warnings and 2 hints are
  tracked for later Android-native API cleanup).
- `:app:assembleRelease`: passed with R8/resource shrinking enabled.
- `git diff --check`: passed.

The inventory currently contains 104 Swift implementation/config files and
177 Android Kotlin/XML implementation/config files. This document will grow by
individual source row; no umbrella row is considered a substitute for reading
the underlying files.
