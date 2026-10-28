# Failed Cashu request delivery recovery

## Reproduction

Tested against the wallet at `3b53e083`, with the released CDK 0.18.2 bindings
on both platforms. Tests used isolated local Nutshell controlled-mint fixtures,
real native wallets, SQLite, and mint-issued test proofs. No public-mint funds
were used.

A 100-sat wallet paid a 21-sat Cashu request whose HTTP destination rejected
delivery. The Android Send UI displayed pending ecash. The spendable balance
was 79 sats and History contained a pending outgoing transaction. The existing
Home filter excluded pending ecash. Explicit reclaim restored 100 sats.

The Swift CDK integration test independently confirms that ordinary saga
recovery and reopening the database leave the 21 sats pending. Calling
`revokeSend` restores spendability. A full mnemonic restore is not required.

## Change

Both apps now use CDK's structured delivery-failure operation ID to reclaim
only the request attempt that failed. The ID must match the prepared operation.
CDK performs the mint-side swap; the wallet never changes proof state to make
funds available without that operation. The existing recovery snapshot verifies
the result before the UI reports returned funds or permits another attempt.

Balance and history refresh on failure as well as success, including Android's
top-up-and-pay route. Pending outgoing ecash is included in Home's Recent list.
The separate iOS Cashu-request screen also presents the existing recovery UI.

Successful delivery and previously shared tokens are not reclaimed. A recipient
claim wins over a refund. If reclaim or verification is unavailable, the send
remains pending/unverified with recovery controls; the wallet does not promise
a refund. Mint fees can reduce the returned amount.

## Regression coverage

| Scenario | Required result |
| --- | --- |
| Delivery rejected | 100 available, no pending send, failed receipt |
| Reopen after refund | Balance remains 100; an independent wallet can redeem all 100 |
| Token delivered, acknowledgement fails | Unclaimed token is revoked and cannot later redeem |
| Recipient claims before reclaim | Sender 79, recipient 21; completed outcome, no refund |
| Reclaim rejected | 79 available, 21 pending; retry cannot create another send |
| Previously shared token | New request is blocked; original token remains redeemable |
| Successful request | Normal recipient redemption and sender debit |
| Unresolved send on Home | Pending outgoing row remains visible |

The native tests live in `CashuRequestDeliveryRecoveryTests` (iOS) and
`PaymentExtendedLocalMintTest` (Android). UI journeys cover automatic recovery
in both apps and the Android pending fallback. The raw CDK baseline remains in
`testCashuRequestDeliveryFailureLeavesReclaimableToken`. The payment coverage
manifest requires these cases in the appropriate CI tiers.

Run with the local fixtures documented in `CI/payment-tests/README.md`.
Android UI journeys require separate instrumentation processes or the test
orchestrator. iOS UI tests handle the system paste permission when copying the
funding invoice. Use isolated devices: these fixture journeys reset wallet state.

## Local validation

- Raw CDK Swift pending/reopen/reclaim reproduction: passed.
- iOS simulator: 52 selected native/unit tests passed, including all six
  real-CDK payment-request recovery cases; no skips.
- iOS UI: failed request automatically returns the balance; 100 sats and the
  failed receipt survive a preserving relaunch.
- Android: 21 focused JVM tests, six native integration cases, and two UI
  journeys passed. UI checks cover both automatic return and pending visibility.
- Payment fixture and coverage-checker tests: 12 passed.
- `git diff --check`: passed.

The initial UI driver needed adjustments for iOS paste permission and native
button labels, and Android's per-process fixture isolation and Home row
semantics. These setup failures are distinct from the passing financial checks.

## Limits

These are local-fixture tests, not public-mint, Nostr-relay, camera, or NFC
acceptance tests. Tests cover database reopening and UI relaunch, but not a
process kill at every point during revoke. Previously stranded tokens still
use the existing manual recovery controls; this change handles new failed
payment-request deliveries automatically.
