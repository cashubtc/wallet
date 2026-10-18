# Cashu request inbox migration

Both apps use CDK 0.18.2 `NostrInbox` for relay subscriptions, reconnects, and
verified NIP-17 unwrapping. `CDKCashuRequestInbox` is a single-use lifecycle and
queue adapter. The synchronous FFI listener only enqueues an unwrapped event.
The app still matches requests, applies the approval policy, persists held
payments, attributes receipts, and owns every wallet mutation.

Delivery retains the guarantees from [#193](https://github.com/cashubtc/wallet/pull/193):

- A fixed seven-day lookback is passed to each new CDK subscription. Reconnects
  reuse that floor; neither wrap nor rumor timestamps advance it.
- The existing persisted, bounded terminal-event history is keyed by CDK's
  `wrapId`, with unchanged storage keys. In-flight duplicates are suppressed;
  transient claim failures remain retryable. The existing 1,000-ID retention
  limit remains: older evicted IDs may be reconsidered if a relay replays them.
- Stop invalidates app callback ownership before awaiting shutdown. A wallet
  boundary closes intake and drains admitted wallet work before moving or
  replacing the database. Queued or late old-session callbacks fail ownership
  checks. Admitted redemptions finish instead of being cancelled mid-mint call.
- Shutdown waits for startup and calls CDK stop again afterwards: CDK startup
  arms a fresh cancellation token, so stopping only before startup settles can
  miss the new run. A fresh adapter is created for restart.

This also handles the receive-path cleanup in
[#409](https://github.com/cashubtc/wallet/issues/409): there are no remaining
production NIP-44 callers. Both local NIP-17/NIP-44 implementations and old relay
clients are removed. Android's request-builder hex conversion stays in the
request builder. No Nostr identity derivation changes are included; #310 is
related history only.

## Tests

`CDKCashuRequestInboxTests` (iOS) and `CDKCashuRequestInboxTest` (Android JVM)
exercise callback handoff, shutdown during startup, non-cancelling processing
drains, closed intake, reconnect duplicates, and persisted IDs across adapters.
The existing app ownership, wallet-boundary, and deduplication regressions remain.

`CDKNostrInboxInteropTests` (iOS simulator) and `CDKNostrInboxInteropTest`
(Android instrumentation) use the same `CI/fixtures/nostr-inbox.json` vectors.
A loopback WebSocket relay sends malformed ciphertext, a signed wrap with a bad
MAC, an encrypted seal with an invalid signature, and a valid payment payload.
It drops the connection, verifies the repeated fixed filter, and replays the
original wrap alongside a second valid wrap. Real CDK must deliver only the
valid unwrapped content and preserve the outer wrap IDs. NIP-44 boundary tests
also verify an independent ciphertext, wrong keys, malformed payloads, and MAC
tampering. No live public relay or mint is required.

The fixture keys are public test scalars 1 (sender), 2 (recipient), 3 and 5
(wrappers), and 4 (wrong recipient). Fixtures were generated independently using
Python `coincurve` Schnorr signatures and `cryptography` ChaCha20/HKDF, with
HMAC-SHA256 from the standard library. Their fixed nonces and timestamps are for
repeatable tests only and must never be used in production.

Run Android JVM tests with `:app:testDebugUnitTest`; select
`com.cashu.me.Core.CDKNostrInboxInteropTest` for connected instrumentation.
Run the two named iOS classes plus `CashuRequestListenerClientSlotTests`,
`CashuRequestListenerLifecycleSchedulerTests`, `CashuRequestListenerWorkGateTests`,
and `ProcessedNIP17EventTrackerTests` with the CashuWallet test scheme.
