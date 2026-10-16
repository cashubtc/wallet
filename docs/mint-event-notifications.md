# Mint event notifications with released CDK

Both apps use the published CDK 0.18.2 bindings. No source pin, custom SDK build,
or API from CDK PR #2698 is required. That PR remains a separate follow-up.

Receive screens, quote details, top-up invoice waiting and replaced on-chain
addresses share one watcher per observed quote. Each watcher requests an ordinary
CDK subscription before starting its own fallback checks, respecting the app's
WebSocket setting. Subscription events are coalesced and passed to the existing
check → mint → verify reconciler. A notification alone never credits a payment.
Reusable BOLT12 offers keep watching after issuance.

The released SDK chooses the subscription transport and may fall back internally
to HTTP polling. A returned handle is **not** an acknowledged WebSocket. The app
therefore keeps a 60-second safety reconciliation even while a subscription exists.
It never labels a handle as proof of working push. Reading the local event queue
every 250 ms does not itself perform an HTTP request.

| Situation | App behavior |
| --- | --- |
| New watcher | Request a CDK subscription; reconcile when created |
| Subscription exists | Reconcile on events; retain a 60-second safety check |
| Subscription creation fails / sockets disabled | Allow initial 10-second grace; checks at 5, 10, 20, 40, then 60-second intervals |
| Subscription creation or queue failure | Retry subscription creation at 2, 4, 8, 16, then 30 seconds |
| Persisted reconciliation failure | Preserve the existing 5, 15, 30, 60, 300-second retry schedule |
| Last observer leaves | Release the subscription; return the quote to ordinary passive recovery |
| App background / wallet replacement | Release watches; foreground resume resubscribes for remaining observers |

Events arriving during a failure backoff stay pending. They cannot shorten its
persisted deadline. A due issuance retry does not require another event. Explicit
retry/refresh retains its existing ability to bypass the deadline.

Unobserved quotes keep the existing bounded, age-aware passive recovery sweep,
including reusable BOLT12 and late on-chain deposits. Passive sweeps skip quotes
with active observers; explicit refresh can still reconcile them. Subscribing all
historical quotes would allow the SDK to create excessive hidden HTTP polling on
unsupported mints, so this release-compatible split deliberately avoids that.

Sent-token screens similarly request proof-state subscriptions and verify claims
through the existing token check. Their ten-check bound remains. On-chain explorer
checks, manual refresh and bounded in-flight payment confirmation remain separate.

Subscription cleanup uses released APIs: Android closes the native handle; Swift
releases it. Queue reads use `tryRecv`, avoiding an indefinitely blocked `recv`
that would retain the subscription. The SDK owns underlying connection cleanup.

## Limits reserved for the CDK follow-up

CDK #2698 adds socket-only subscriptions, acknowledged readiness, explicit native
cancellation and changes to internal HTTP retry behavior. Without those changes,
the app cannot enforce socket-only transport or apply its persisted backoff to
SDK-owned status polling. The app's issuance/recovery retry deadlines are still
preserved. The periodic outgoing-payment sweep uses released
`finalizePendingMelts()` instead of general saga recovery: the latter can replay
incoming issuance before its retry deadline. Startup/general crash recovery remains
in place. Balance and reserved-proof changes are checked so compensation is reflected
in the UI. This split does not modify the SDK's internal pending-melt waiter or capability fields.

## Validation

`MintEventLocalMintTests` (iOS) and `MintEventLocalMintTest` (Android) exercise
production wallet managers with released native CDK, isolated SQLite wallets,
a real Nutshell ledger and a manually paid fake Lightning backend. The fixture
records HTTP timing, WebSocket attempts/acknowledgements and notification delivery.
It can duplicate notifications, disconnect sockets and reject issuance requests.

Start the CDK fake mint with `CI/start-cdk.sh` and the controlled Nutshell fixture
with `CI/start-payment-fixtures.sh`. For iOS, pass
`TEST_RUNNER_PAYMENT_FIXTURE_URL=http://127.0.0.1:3341` to `xcodebuild test` and
select `CashuWalletTests/MintEventLocalMintTests`. For Android, select
`com.cashu.me.liveintegration.MintEventLocalMintTest` using the connected test task,
with `cashu.paymentFixtures=true` and the emulator-accessible fixture URL.
The tests compare the previous focused polling monitor with the new shared watcher,
verify exactly-once credit, exercise unsupported sockets and missed-update recovery,
and check real failed issuance attempts retain the 5- and 15-second retry delays.
The schedule unit tests cover the longer capped retry intervals.
