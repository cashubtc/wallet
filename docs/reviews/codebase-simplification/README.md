# Native app simplification audit

The supplied cleanup plan was checked against the current implementation before
removing code. Several proposed deletions have live callers; retaining those
paths is required to preserve wallet behavior.

## iOS disposition

| Plan area | Implementation |
| --- | --- |
| Dead views | Removed unused amount-entry file, advanced-settings file, activity/loading/lock overlay views, obsolete mint row, unused glass modifiers and scanner helpers. Removed dangling Xcode source references. |
| Legacy services | Removed superseded file-backup implementation and its obsolete tests, unused NIP-98 signing, unused NPC errors, notification and keypair helpers. Kept durable wallet replacement and file-move recovery tests. |
| Restore | Shared input/staging state, seed paste/checksum handling, mint URL input policy, staged mint rows and progress rows. Each host retains its navigation, presentation and backup notices. |
| Transaction rows | Shared row rendering, date formatting, amount accessibility and status labels. Hosts retain receipt selection, search focus and pending-token swipe actions. |
| Pickers | Shared mint rows/avatar and unit picker content; retained selection/filter/order contracts and native presentation styles. Send sheets use content measurement. |
| Launch/recovery | Shared repository construction, recovery classification and database backup helpers between launch and instance paths. Current detached launch setup already supersedes the audit's timeout race. |
| Payment services | Shared ordered candidate loop for four melt rails, including overflow-safe affordability and last-error propagation. Shared mint matching/ranking with caller-specific preferences. |
| Storage/codecs | Shared Keychain CRUD preserves synchronizable account and accessibility policies. Shared preference migration/accessors, hex codec, Bech32 framing/bit conversion, P2PK normalization and mint URL identity helpers. |
| App lock | Window controller owns authentication lifecycle; app scene handling continues to manage payment services. |

Retained active code identified incorrectly as dead by the original report:

- BackupSettingsSection and BackupView are presented by Settings.
- ImportP2PKSheet is used by P2PK settings.
- CashuPaymentRequestPayView is presented by the home payment-request route.
- FocusedMintQuoteMonitor is owned by WalletManager and has lifecycle tests.
- FlowRowMetrics is used by SendView.
- Currency, CurrencyAmount, CurrencyRegistry and display metadata are used widely.
- Nostr getNsec is used by key export; reusableFixed is used in receive mapping.
- Melt recovery's unresolvedQuote remains useful for structured error inspection.

Checksumless BOLT12 framing remains specific to that protocol while using shared
bit conversion. URL validation, user input completion, identity and display have
different contracts; they use named shared helpers without equating HTTP with
HTTPS or changing case-sensitive paths.

## Android disposition

| Plan area | Implementation |
| --- | --- |
| Dead UI/shims | Removed unused NIP-98 signing, unreachable restore presentation branches, obsolete P2PK inline form/toolbar shim and ignored shell parameters. |
| UI sharing | Shared onboarding info sheet, amount presentation renderer, pending-token claim feedback and Context activity/external-link helpers. |
| Core sharing | Shared four CDK currency-unit mappings, mint URL identity and NDEF text/URI payload codecs. Removed the stateless metadata wrapper. NFC extraction reuses TokenParser, preserving raw crawB transport handling. |
| Settings | Settings initialization reads one coherent preference snapshot. Dynamic key removal handles every stored preference type; tests cover snapshot cleanup and typed removal. |

Retained durable P2PK deletion transaction state: existing tests verify recovery
after interruption, beyond ordinary in-process rollback. Obsolete NPC keys were removed from unrelated store boundaries; NPCService
continues to own its secure snapshot/reset behavior. Fiat preference aliases
remain for migration compatibility. The redundant wallet protocol layer is removed while concrete methods used by
callers/tests remain. Quote polling/subscription paths and integration-tested gateway
APIs are retained where deleting them would remove supported behavior.
`observeMintQuote` does not exist in this revision, so live quote subscriptions
were preserved rather than migrated to a nonexistent replacement. The unused
`unitBalanceIfExists` gateway API was removed from implementation and test fake.

## Verification

Utility regressions cover all-byte hex roundtrips, malformed input, Bech32 and
Bech32m framing/checksum failures, Nostr payload roundtrips, P2PK key comparison,
and independent restore input state/checksum handling. Existing native tests
cover wallet replacement, quote recovery, storage migrations, app lock and payment
routing.

Validation performed on the final implementation:

- iOS simulator build for app, unit tests and UI tests: passed.
- iOS unit and live-mint/payment integration suite: 779 passed, 11 conditional
  skips, zero failures (790 discovered). Local CDK, Nutshell, receipt-recovery
  proxy and controlled payment fixtures were running for this successful run.
- iOS focused restore/settings/backup/app-lock UI suite: 7 passed.
- iOS funded send/P2PK locking/USD amount-entry UI suite: 3 passed.
- iOS extended payment suite: 5 passed; the opt-in known upstream receiver-fee
  regression remained skipped.
- Android unit suite: 804 passed; all 6 opt-in local-mint integration tests
  passed separately (810 total), with zero failures.
- Android debug app, AndroidTest APK and test compilation: passed.
- Android lint: zero errors; existing warnings and hints remain.
- Android focused emulator suite: 29 passed, including storage snapshots and
  typed removal, migration, P2PK interruption/rollback/accessibility, onboarding,
  restore/cancel, new wallet and funded ecash send.
- Xcode project source references: no missing files or unregistered new sources.
- Whitespace/error checks: passed.

Conditional iOS skips include the separate Bitcoin regtest runner, opt-in live
mint checks, full-tier/known-regression payment cases, an unavailable decoder
fixture, and an empty-wallet-only restore check. That restore check passed in
the initial fresh-simulator unit run; the final run followed seeded UI fixtures.
The initial fixture-free run was superseded after local test services were
started. Physical-device NFC and biometric hardware were not exercised.


The five full-tier payment cases skipped in the main run were subsequently
executed successfully in the separate extended suite. No tests were weakened
to make the cleanup pass.
