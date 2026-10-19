# Wallet-only mint events: validation report

Date: 2026-10-16. Status: implementation and validation approved for publication
in the wallet pull request.

The implementation is on `feat/wallet-only-mint-events`, based on wallet commit
`d01ac228`. Both applications retain **published CDK 0.18.2**. No custom binding
build, source revision pin, or API from the unmerged CDK PR is required.
This report accompanies the wallet-only revision of
[Wallet #396](https://github.com/cashubtc/wallet/pull/396).
[CDK #2698](https://github.com/cashubtc/cdk/pull/2698) remains unchanged as a
separate follow-up and is not required to ship these improvements.

## What changes

- Receive, quote detail and top-up flows share one subscription per observed quote.
  Replaced on-chain addresses remain observed while their receive flow is active.
  Sent-token claim detection also requests a subscription before app polling.
- Events wake the existing authoritative check → mint → verify reconciliation.
  Duplicate events cannot establish payment success or credit money themselves.
- A subscription handle does not imply an acknowledged socket. The app retains a
  60-second safety check. If subscription creation fails or sockets are disabled,
  app fallback checks back off through 5, 10, 20, 40 and 60 seconds after initial
  subscription grace. The existing 5/15/30/60/300-second persisted issuance retry
  schedule takes precedence over notifications.
- Observers share subscriptions; leaving the last observer or backgrounding the
  app releases the handle. Foreground observers resubscribe. Historical/unobserved
  quotes retain bounded passive recovery instead of acquiring SDK polling loops.
- Passive selection preserves watched quotes' retry records while selecting other
  quotes. Explicit refresh retains its existing override.
- The periodic outgoing-payment sweep calls the released melt-specific recovery
  API. General saga recovery was able to replay an incoming issuance before its
  retry deadline. Startup/general crash recovery remains intact, and outgoing
  compensation still refreshes balances when spendable/reserved proofs change.

## Executed validation

All results below are from the final code, built against released bindings.

| Suite | Passed | Failed / skipped |
| --- | ---: | ---: |
| iOS simulator: scheduling, coordinator, reconciliation, melt waiting and native end-to-end | 33 | 0 / 0 |
| Android unit: scheduling, coordinator, reconciliation and melt recovery | 48 | 0 / 0 |
| Android emulator: production wallet end-to-end | 5 | 0 / 0 |
| Python fixture and coverage-gate regressions | 13 | 0 / 0 |

Both applications compiled successfully. Diff whitespace and fixture shell syntax
checks passed. The five new native scenarios per platform are included in the
required payment coverage manifest so skipped/missing cases cannot silently count
as CI coverage after the change is published. Hosted CI has not been run for this
local branch.

The native tests use isolated production wallet managers, native CDK and SQLite.
Android launches the production activity/application container. iOS runs the
production wallet manager inside the simulator test host. Incoming payments use
Nutshell's real ledger and cryptographic proofs with a manually paid fake Lightning
backend. Outgoing recovery uses the local CDK fake backend; Nutshell's stock fake
external-payment status has a documented timing limitation.

## Measured improvement

The comparison runs the existing focused quote polling monitor and the new shared
watcher against the same unpaid quote. After subscription establishment, each idle
measurement window lasts 6.5 seconds.

| Observation | iOS | Android |
| --- | --- | --- |
| Previous monitor status GETs | At least 3 | 2 |
| Shared watcher idle status GETs | At most 1 | 0 |
| Observers sharing one subscription | 2 observers, 1 subscribe request | 2 observers, 1 subscribe request |
| Payment settlement after explicit fake payment | Within 5 seconds | 3.166 seconds |

The iOS values are the bounds enforced by the passing test; exact console metrics
were not retained by the local Xcode result bundle. These are controlled test
samples, not a claim about production battery use or all network conditions.

## End-to-end scenarios passed on both platforms

1. Unpaid wallet stays uncredited; acknowledged push reduces idle status requests.
   Duplicate notifications result in one successful issuance and one receipt.
2. Rejected issuance persists retry deadlines. Background/resume and further
   events cannot bypass them. The first two delays remain approximately 5 and 15
   seconds; settlement succeeds once the fault is removed. Android measured
   remaining deadlines of 4.979 and 14.951 seconds when inspected. Unit tests cover
   the longer retry schedule and cap.
3. Forced disconnect causes reconnection. A payment made while watchers are stopped
   is recovered on resume without another payment or duplicate credit.
4. A rejected WebSocket endpoint still settles using released CDK fallback.
5. Outgoing recovery handles rejected requests and a response lost after mint
   acceptance; balances are conserved and repeated recovery cannot debit twice.

CDK may retry NUT-20 signature serialization after Nutshell rejects one format.
The fixture explicitly classifies that rejection; tests still require exactly one
successful issuance. CDK's existing immediate saga replay within a reconciliation
round is preserved. Backoff assertions concern the persisted deadline between
rounds, including the foreground maintenance path that originally bypassed it.

## Remaining limits

The released SDK can poll internally and does not expose acknowledged socket
readiness or socket-only subscriptions. The wallet cannot enforce its backoff on
those SDK-owned status GETs. CDK #2698 remains relevant for that stronger guarantee.

Native incoming end-to-end coverage here is BOLT11. Reusable BOLT12 and late on-chain
behavior retain their existing domain/scheduling regression coverage, but were not
newly validated against live payment networks. These tests exercise real wallet
instances with fake payment backends; they are not a full manual UI/device, Bitcoin
confirmation, public Lightning, or OS-process-death acceptance run.

## Review artifacts and publication scope

- Behavior and local run instructions: `docs/mint-event-notifications.md`.
- iOS result bundle: `.build/wallet-only-ios-final` (33 passed).
- Android native report: `android/app/build/reports/androidTests/connected/debug/`.
- Android unit report: `android/app/build/reports/tests/testDebugUnitTest/`.

The approved publication updates wallet #396 with this released-SDK split and
removes its dependency on merging CDK #2698. The CDK PR remains separate for later
review. The results above describe local validation; hosted CI is reported
separately on the wallet PR.
