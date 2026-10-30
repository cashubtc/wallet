# CDK 0.18.3 wallet upgrade

Both native apps and the standalone Swift integration package now pin CDK
0.18.3. The shared mint fixture also uses the official 0.18.3 binaries, with
SHA-256 checksums pinned for macOS arm64 and Linux arm64/x86_64. The opt-in
Lightning Address harness now requires matching 0.18.3 source and binding
artifacts; its earlier [validation report](../../CI/lightning-address-tests/validation.md)
remains historical evidence for 0.18.2.

## Changes used by the wallet

The [upstream release](https://github.com/cashubtc/cdk/releases/tag/v0.18.3)
contains three reliability fixes:

| Fix | Wallet integration |
| --- | --- |
| FFI runtime teardown is safe from an async context; synchronous teardown still waits for work. | iOS now constructs `WalletSqliteDatabase` directly. The extra native reference and dispatch-worker release in `LifecycleSafeWalletDatabase` are removed, including from tests. Fixture teardown releases native handles in async teardown, and the BOLT12 restart test releases the original handles before reopening the store. Android receives the same native fix through its dependency update. |
| SQL transaction cleanup retains its connection until completion and discards it on failed, cancelled, or timed-out cleanup. SQLite rollback is skipped when no transaction is active. | Both apps already use CDK's file-backed SQLite store, so existing wallet operations receive the fix without a new app-level transaction layer. |
| A lost SQLite in-memory database reports an error instead of being replaced with an empty database. | Both production apps continue using durable database files. No switch to in-memory storage or automatic empty-store replacement is needed. |

## Feature and compatibility review

The generated Swift source and the generated Kotlin source in Maven Central's
published source artifacts are byte-for-byte identical between 0.18.2 and
0.18.3. There are no newly exposed wallet methods to adopt. The core patch adds
no wallet schema migration or protocol changes.

Useful existing APIs remain in use on **both** platforms:

- `getTokenData` supplies receive previews and fee estimates.
- Quote status checks return the persisted mint/melt quote after recovery.
- `fetchMintQuoteWithKeySearch` recovers a known deposit quote's signing key
  from the wallet seed within a bounded search. It does not discover unknown
  quote IDs or recover randomly generated keys.

These APIs were already available in 0.18.2. This upgrade does not add new UI or
change payment confirmation, recovery policy, or supported payment methods.

## Validation

Validation uses the official released Swift XCFramework and Android AAR, with
isolated local CDK 0.18.3 and Nutshell 0.21.0 fake-payment mints. It does not
establish public-mint or physical NFC interoperability.

- iOS app, unit test bundle, and UI test bundle build successfully.
- iOS storage, checkpoint/teardown, token, deposit, pending-send, and receipt
  recovery suites: 123 passed; 2 opt-in tests skipped.
- Standalone native Swift integration suite: 53 passed; 11 opt-in/tier tests
  skipped. Includes BOLT11/BOLT12/on-chain operations, cancellation, duplicate
  token rejection, quote recovery across database reopening, and payment-fault
  fixtures with async handle cleanup.
- Android app and instrumentation APKs build successfully. JVM suite:
  984 passed; 6 skipped. Android lint and the release APK build pass.
- Android native matrix: BOLT12 recovery after reopening, foreign-token change
  persistence, and spent-token recovery rejection pass. All 8 additional
  receipt-recovery, persisted npub.cash quote, and receive-preview tests pass.
- The broader Android `nativeCdkWalletMatrixAgainstLocalMints` case fails at
  its CDK P2PK step with `PendingSendRecovery`. The same failure reproduces
  using the same Android app/test source with forced 0.18.2 bindings and an isolated
  0.18.2 CDK mint, so it predates this dependency update. Both APKs' native
  libraries were checked against their corresponding released AARs.
- CI script tests and payment coverage checker tests: 12 passed.

The opt-in Lightning Address source-rebuild harness was updated for matching
0.18.3 inputs but was not rerun as part of this upgrade.
