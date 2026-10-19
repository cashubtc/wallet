# Native wallet interoperability: Nutshell and CDK

Test date: 19 October 2026. Result: **payment-request underpayment reproduced on both platforms and both mint implementations; core mint interoperability passes.** Of 1,950 unique wallet test cases, 1,929 passed, four failed on this one shared defect, and 17 remain skipped for external DNS/TLS or Bitcoin-backend fixtures.

## Exact software under test

| Component | Version / revision | Source |
| --- | --- | --- |
| iOS wallet | Official `main`, `d7bea8f8cec14925fc54c2ed5b26e4918ac5d5d5` | [Wallet revision](https://github.com/cashubtc/wallet/commit/d7bea8f8cec14925fc54c2ed5b26e4918ac5d5d5) |
| Android wallet | Same official `main` revision | Same source tree, native Android implementation |
| Swift native binding | Released CDK Swift 0.18.2 | SwiftPM dependency and verified release artifact |
| Android native binding | Released CDK Android 0.18.2 | Gradle dependency; no native-library replacement |
| Nutshell mint | Official stable 0.21.0 | [Official release](https://github.com/cashubtc/nutshell/releases/tag/0.21.0), `cashu==0.21.0` |
| CDK mint | Official stable 0.18.2 | [Official release](https://github.com/cashubtc/cdk/releases/tag/v0.18.2) |
| iOS runtime | iOS 26.5 simulator, arm64 | Isolated test device |
| Android runtime | Android 16 / API 36 emulator, arm64 | Native app and Android Test Orchestrator |

Both repository remotes agreed on the wallet revision when main was refreshed. The initial checkout and the harness's old CDK release-candidate/Nutshell pins were superseded before the reported wallet matrix ran. Production wallet source was not patched to make tests pass. Added source changes are test cases, test-fixture configuration, and test documentation.

The CDK Apple Silicon executable matched the release `SHA256SUMS` value:
`3e93c4735c265c8079acbb62c2d8f111cade1d737fa1e797ed846c3a656f78f8`.

## Environment and interpretation

The two primary mints use FakeWallet, zero input fees, and isolated local databases. Nutshell advertises BOLT11/SAT. CDK advertises BOLT11, BOLT12, and simulated on-chain payments for SAT and USD. Unsupported Nutshell methods are capability differences, not failed payment operations.

Additional **test fixtures**, separate from the primary mints, provide manually paid Nutshell invoices, a 1000-ppk Nutshell keyset, and a 1000-ppk official CDK mint. A scenario-scoped HTTP proxy drops/rejects actual mint responses; a local signed Nostr relay serves request/NWC cases. Proofs and balances come from real mint ledgers. No service fabricates Cashu signatures or wallet credits.

Every native wallet owner gets a fresh random seed and a separate SQLite store. Android reverse mappings give both platforms the same encoded mint URLs. The receipt-loss proxy is separate per platform, preventing cross-platform fault-state races.

Native integration tests use the released bindings, actual proof issuance/redemption, and durable wallet stores. The two live UI payment journeys on each platform use real mints. The broader UI suite also includes deterministic application fixtures; a passing fixture-driven settings/navigation test does not establish live mint behavior for that screen.

FakeWallet auto-settlement demonstrates wallet/mint protocol behavior. It does not demonstrate actual Lightning routing, transfer of value between Lightning nodes, or Bitcoin confirmation. Reopen tests demonstrate durable store recovery; only tests explicitly terminating/relaunching the app demonstrate that lifecycle boundary.

## Execution totals

| Test layer | Passed | Failed | Skipped | Unique cases |
| --- | ---: | ---: | ---: | ---: |
| iOS unit/native tests, including paired exchanges | 791 | 2 | 8 | 801 |
| iOS UI journeys | 61 | 0 | 1 | 62 |
| Android instrumentation: native, component and UI | 270 | 2 | 8 | 280 |
| Android JVM tests, including six explicit live-mint host cases | 807 | 0 | 0 | 807 |
| **Total** | **1,929** | **4** | **17** | **1,950** |

These are distinct test identifiers, not 1,950 full E2E journeys. Later targeted executions replace earlier skipped results for the same case; the per-run ledger retains earlier failures and skips. Assertions, JUnit XML, and Xcode test trees determine counts. Android's console progress double-counted skipped events, so its final progress line is not the authoritative case total.

Both expanded payment coverage gates passed **24/24** required cases. Overall acceptance is still **failed** because the four receiver-fee regression cases are deliberately enabled and fail. The 11 fixture/coverage Python checks and six startup/shard checks also passed.

The main Android instrumentation run took 24 minutes 26 seconds. The complete iOS UI run took about 26 minutes 24 seconds. Supplemental runs closed local-fixture skips, exercised added mint counterparts, and verified the paired driver.

The machine-readable result ledger lists individual test identifiers, outcomes, and failures. Repeated diagnostic executions are recorded separately from unique test coverage.

## Direct iOS / Android interoperability

For each primary mint, the paired native suites executed this sequence:

1. iOS creates a fresh wallet and mints 128 sats.
2. iOS sends Android an ordinary 31-sat token and a 7-sat token locked to Android's public key.
3. Android receives the ordinary token, rejects an incorrect private key for the locked token, then redeems it with the correct key. Replaying the ordinary token is rejected.
4. Android reopens its database and verifies 38 sats. It sends iOS an ordinary 13-sat token and a 5-sat token locked to iOS's public key.
5. iOS performs the same wrong-key/correct-key and replay checks, then reopens its database.
6. Final balances are **iOS 108 sats + Android 20 sats = 128 sats**. Android reopens again and confirms its 20 sats.

Both directions passed against both mint implementations. The exchange mailbox only transports encoded tokens and public keys; the native wallets create, verify, and redeem the tokens. Neither seeds nor private spending keys cross the mailbox.

## Operation comparison

| Operation / invariant | iOS + Nutshell | Android + Nutshell | iOS + CDK | Android + CDK |
| --- | --- | --- | --- | --- |
| Mint discovery and keysets | Passed | Passed | Passed | Passed |
| BOLT11 quote, paid issuance, balance | Passed | Passed | Passed | Passed |
| BOLT11 melt and debit/history conservation | Passed | Passed | Passed | Passed |
| Ordinary token send and receive | Passed | Passed | Passed | Passed |
| Direct native peer exchange, both directions | Passed | Passed | Passed | Passed |
| P2PK correct-key receive / wrong-key rejection | Passed | Passed | Passed | Passed |
| Replayed token rejection | Passed | Passed | Passed | Passed |
| Seed restore / stale counter recovery | Passed | Passed | Passed | Passed |
| Cancel send / insufficient funds / revoke | Passed in native safety scenarios | Passed in native safety scenarios | Passed in native safety scenarios | Passed in native safety scenarios |
| Paid but unissued quote recovery | Controlled Nutshell fixture | Controlled Nutshell fixture | Passed, including reusable BOLT12 | Passed, including reusable BOLT12 |
| Lost mint response after issuance | Passed on controlled fixture | Passed on controlled fixture | Passed | Passed |
| Lost melt response after payment | Not a separate Nutshell fault case | Not a separate Nutshell fault case | Passed | Passed |
| Backend payment failure restores spendability | Not a separate Nutshell backend-failure case | Not a separate Nutshell backend-failure case | Passed | Passed |
| Concurrent token redemption, one winner | Passed on controlled fixture | Passed on controlled fixture | Passed | Passed |
| Fee-bearing send-max and recipient fee accounting | Passed on 1000-ppk fixture | Passed on 1000-ppk fixture | Passed on fee fixture | Passed on fee fixture |
| Cashu Request via HTTP, zero fees | Passed on controlled fixture | Passed on controlled fixture | Passed | Passed |
| Cashu Request recipient fee, fixed / amountless | **Underpays: 19 / 21** | **Underpays: 19 / 21** | **Underpays: 19 / 21** | **Underpays: 19 / 21** |
| BOLT12 mint, melt, reusable-offer recovery | Not advertised | Not advertised | Passed | Passed |
| BOLT12 description edit / clear / reopen / fixed amount | Not advertised | Not advertised | Passed with delayed-payment fixture | Passed |
| Simulated on-chain mint / melt / deposit recovery | Not advertised | Not advertised | Passed | Passed |
| SAT/USD isolation | SAT only | SAT only | Passed | Passed |
| Native quote subscription followed by issuance | Passed | Passed | Passed | Passed |
| Live UI receive, pay, history after reopen | Passed | Passed | Passed | Passed |

Where a row names one fixture or one implementation, it must not be interpreted as a four-way proof. This table distinguishes executed comparisons from remaining symmetry gaps.

## Confirmed defect: recipient-fee payment requests underpay

**Severity: high.** A 21-sat Cashu Request can complete delivery while the recipient receives only 19 sats. Fixed and amountless requests reproduce the defect. Zero-fee requests pass. Ordinary send-max redemption fees pass and are not this defect.

The failure occurs with both Nutshell 0.21.0 and CDK 0.18.2 fee-charging mints, using CDK 0.18.2 wallet bindings. The native iOS and Android cases fail the intended 21-sat-credit assertion. The same released Swift binding was also run directly to capture the delivered proof denominations and planned debit.

| Measurement | Observed |
| --- | --- |
| Sender starting balance | 100 sats |
| Requested recipient credit | 21 sats |
| Delivered token face value | 23 sats |
| Delivered proofs | 1, 2, 4, 16 sats: four proofs |
| Mint redemption fee | 4 sats at 1000 ppk |
| Actual recipient credit | **19 sats** |
| Sender remaining balance | **69 sats** |
| SDK's planned total input fee / debit | 10 sats / 31 sats |

The wire payload contains valid proofs. The recipient's mint correctly charges the advertised per-input fee. This is not an incorrect wallet balance label: actual newly spendable value is 19 sats.

The released source explains the mismatch:

- [`prepare_pay_request`](https://github.com/cashubtc/cdk/blob/v0.18.2/crates/cdk/src/wallet/payment_request.rs) requests fee-inclusive sending.
- [`Amount::split_with_fee`](https://github.com/cashubtc/cdk/blob/v0.18.2/crates/cashu/src/amount.rs) derives a fee-aware denomination split. For 21 sats at this fee, the candidate split totals 24 sats in two proofs, with a 2-sat redemption fee.
- [`SendSaga::internal_prepare` and `confirm`](https://github.com/cashubtc/cdk/blob/v0.18.2/crates/cdk/src/wallet/send/saga/mod.rs) retain the two-proof fee but reconstruct the amount as `21 + 2 = 23`. The final token has four denominations, so its redemption costs four sats. The fee assumption no longer matches the actual emitted proofs.

The repair must reconcile the final denominations and their actual redemption fee with the intended recipient credit. Simply retaining the two-proof 24-sat split would credit 22 sats, so merely padding the current token is not an exact-amount fix. Keep the intended `received == 21` assertion while fixing selection/splitting and the quoted debit together. A binding upgrade should be accepted only after fixed and amountless requests pass on both platforms and both fee fixtures.

The existing required payment manifest excludes the known receiver-fee regression. Therefore its green coverage gate alone cannot establish that all payment operations are correct. This run deliberately enables the regression.

The required manifest was expanded from 19 to 24 full-tier cases per platform, adding the CDK lost-issuance-response/concurrent-redemption counterparts, a CDK zero-fee request case, and native quote subscription cases on both mints. Fee-underpayment reproductions retain failing assertions and are listed independently of the success gate.

## Setup and harness findings

| Finding | Classification / resolution |
| --- | --- |
| Harness pinned CDK 0.17.3-rc.0 and Nutshell 0.20.1 | Superseded with the official stable releases before the reported matrix. |
| CDK 0.18.2 rejects an uninitialized database and legacy `[[ln]]` configuration | Test setup compatibility issue. Use the release's `config migrate` and explicit database-backed initialization/application. Fresh and repeated setup were verified. |
| First paired run waited on different test methods | Harness scheduling error: JUnit and XCTest chose different method orders. The attempt was interrupted; the corrected exchange passed. The reusable driver pairs each mint explicitly. |
| Detached mint lifetime under the command runner | CDK was held in a managed foreground session during the reported matrix. Availability was checked before testing. |
| Diagnostic `proofsSimple()` cannot expand IDv2 short IDs without keysets | The diagnostic switched to counting delivered wire proofs. Wallet token metadata already handles this SDK limitation; native redemption succeeded. |
| Supplemental macOS native run logged a Tokio runtime-drop panic during cleanup | The recovery/concurrency assertions passed and the process completed successfully. This is a separate SDK runtime-lifecycle concern; these logs do not establish a mobile app crash. |
| Scanner fallback test used `creqApayload` and skipped | Replaced the placeholder with a request generated by the production builder and a failing assertion if it cannot decode. Supplemental result recorded below. |
| Opt-in Android history journey timed out looking for `Completed` text | Native balance, quote, attempt-count and single-receipt assertions had passed. The current receipt exposes its completed checkmark through an accessibility description. Corrected the locator and reran the full journey, including recreation and duplicate-history checks. |
| First iOS BOLT12-description UI run lost its editor | The supposedly unfunded offer auto-paid 3,296 sats; mint logs show paid/issued state and the UI hierarchy shows that balance after the receive flow closed. Repeated against an official CDK fixture with delayed fake payments: edit, clear, reopen and fixed-amount checks all passed without a wallet-source change. |
| Same-seed regression skipped after the broad UI run left a wallet | Its guard requires an empty wallet. Cleared the owned fixture through the tested wallet-deletion UI, then reran the regression successfully in clean state. |

## Skips, limits, and uncovered operations

The full initial iOS native run skipped seven opt-in Lightning Address binding cases, one Bitcoin-regtest case, one BOLT12-description case, one live history-retry case, and the scanner's invalid fixture. The description/history/scanner cases are followed up with local fixtures. Android's ordinary JVM task skips six live-mint cases; its explicit live-mint task runs those separately.

The Android full instrumentation run also skips the seven Lightning Address binding cases and three opt-in UI cases (one live BOLT12-description journey and two live history journeys). The three local UI cases are run explicitly with their required fixtures in supplemental runs.

The seven opt-in Lightning Address binding cases on each platform require the repository's separate TLS/DNS fixture and a native library rebuilt with an added test trust feature. This matrix retains the byte-for-byte released wallet bindings. Their ordinary resolver/error-mapping tests ran, but those 14 native network scenarios are not counted as passed here. Three Bitcoin-regtest cases (iOS native, iOS UI, Android UI) require an actual Bitcoin backend and remain outside this FakeWallet run. These account for all **17 remaining skips**.

Further limitations remain even after running every available ordinary test target:

- Real Lightning routing, inter-mint Lightning payment causality, and Bitcoin confirmation are not established by auto-paying fake backends.
- Not every loss/retry/concurrency scenario is repeated against both mint implementations; the operation table identifies the asymmetric fixtures.
- Database reopen is not process death during a pending saga. Pending-to-failed, prolonged unknown settlement, and repeated OS-process death still need targeted lifecycle drivers.
- Local Nostr inbox/NWC scenarios validate native encoding, decryption, event handling, replay or payment limits where asserted; they do not prove every public-relay reconnect, authorization, limit-persistence, or multi-party delivery scenario.
- BOLT12 repeated/late/expired payments, on-chain fee changes while pending, and every optional NUT capability are not exhaustive state-space coverage.
- Physical NFC and camera exchange, actual biometric enrollment, iCloud backup/restore, and external Lightning Address/NPC infrastructure need their own device/service acceptance runs.

This report is consequently a full execution record of the available suites plus targeted interoperability extensions, **not a claim of 100% coverage of every wallet operation/state combination**. The confirmed underpayment and explicit gaps prevent an unqualified interoperability sign-off.

## Reproduction and evidence

Use the setup scripts and native runner documented in [CI/README.md](../../CI/README.md). Run the full payment tier, enable known regressions, and enforce the required manifest in addition to checking overall test results. For iOS, export both `xcresulttool get test-results summary` and `tests`; for Android, retain JUnit XML before another connected test task replaces it.

The paired driver is [CI/run-native-interop.py](../../CI/run-native-interop.py). Its output directory is supplied by the caller and contains per-platform logs, per-mint Xcode bundles, and a version/exit-code summary. The report's companion ledger contains sanitized test identifiers and outcomes; raw native logs, local runtime identifiers, test seeds, and generated databases remain outside the tracked report.

The [per-test result ledger](native-mint-interop-2026-10-19-results.json) contains every unique case plus all included run attempts. The four remaining failed identifiers are:

- iOS `PaymentExtendedIntegrationTests/testCashuRequestReceiverFeeRegression`
- iOS `PaymentExtendedIntegrationTests/testCdkCashuRequestReceiverFeeRegression`
- Android `PaymentExtendedLocalMintTest/cashuRequestReceiverFeeRegression`
- Android `PaymentExtendedLocalMintTest/cdkCashuRequestReceiverFeeRegression`

Each executes fixed and amountless requests. Together they cover all eight platform/mint/request-type combinations for the confirmed underpayment. Both final paired mint runs reported zero exit codes for both native wallets. Initial setup, scheduling, invalid-locator and auto-payment-fixture attempts are explained above and retained in local diagnostics rather than counted as unresolved product failures.
