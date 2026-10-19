# Native validation: 2026-10-18

Validated after rebasing onto wallet main `86e438dae40205a4c667be6e0485d08bcef70f3f`.
Native source: CDK v0.18.2, `9f6d3a3734d7223d10b5553e3591d3f9d5c6c904`.
The released Swift and Kotlin generated bindings were retained.

| Scenario | iOS 26.5 simulator | Android 17 emulator |
| --- | --- | --- |
| Dual-purpose name, BOLT11-only mint: LNURL → BOLT11 quote, no DNS | Pass | Pass |
| Dual-purpose name, BOLT12-capable mint: LNURL → BOLT11 quote, no DNS | Pass | Pass |
| Recipient HTTP 502: recipient-service message, no DNS/mint quote | Pass | Pass |
| Missing LNURL endpoint: native DNSSEC verification → BOLT12 quote | Pass | Pass |
| Mint connection dropped: mint-specific message, no DNS fallback | Pass | Pass |
| DNS connection failure after LNURL 404: BIP-353 message, no mint quote | Pass | Pass |
| LNURL 404 and BOLT11-only mint: unsupported BOLT12 message, no DNS | Pass | Pass |

Both final native suites passed **7/7**, with zero failures or skips. The final
runs used the local server's `--offline` mode and cached signed DNS wire
responses; no public recipient HTTP service or public mint was contacted.
Android routing and reverse ports were verified removed after the run.

Separate focused tests against the **unmodified shipped CDK dependencies** passed
14/14 on iOS and 9/9 on Android. These cover resolver policy and UI error mapping.
Both native apps and their test targets compiled successfully.

## Qualification boundary

The native network suites exercise the production service/gateway through CDK
and SQLite to the recipient/DNS/mint network boundary. They finish at melt quote
creation; they do not execute settlement or move real funds.

Local HTTPS stubs require an additive bitreq `async-https-rustls-probe` feature
in the native test build so `SSL_CERT_FILE` can supply a test CA. Native
resolver/payment source and generated language bindings are unchanged. TLS
verification and full DNSSEC proof validation stay enabled. These runs are not
byte-for-byte validation of the shipped native binaries. Production dependency
pins are unchanged. The [harness instructions](README.md) reproduce the setup.

Two upstream behaviors remain relevant: CDK 0.18.2 requires same-domain LNURL
callbacks, and its BIP-353 URI parser rejects a URI containing `lnurl` alongside
`lno`. The successful DNS fallback test uses a signed BOLT12 record without that
unsupported parameter; the dual-purpose LNURL-first cases use a name publishing
both. Neither upstream restriction is changed in this PR.
