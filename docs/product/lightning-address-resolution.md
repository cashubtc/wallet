# Lightning Address resolution

Both native apps use CDK 0.18.2's `meltLightningAddressQuote` to resolve
LUD-16/LNURL-pay and request a BOLT11 melt quote. The old metadata-hash
workaround is removed: this binding includes CDK's fix for cashubtc/cdk#2235.

Resolution remains **LNURL first**, including when the mint supports BOLT12.
The combined `meltHumanReadable` API is intentionally not used because its
BIP-353-first policy would change the payment rail for dual-purpose names.

Only HTTP 404 or 410 from initial LNURL discovery allows a subsequent
`meltBip353Quote` call, using a mint advertising sat BOLT12 melt support.
HTTP 502, rate limiting, DNS/network failures, malformed discovery data,
callback failures, invalid invoices and amount mismatches do not allow fallback.
A callback or mint 404 is not an absent discovery endpoint.

CDK's FFI exposes these origins in its error detail, rather than separate error
variants. The fallback policy matches the complete discovery error prefix and
HTTP status, never a status appearing in a response body. UI classification
handles CDK's Lightning Address and BIP-353 prefixes before generic mint network
errors. A BIP-353 failure is surfaced directly, rather than replaced by the
original missing-endpoint error. Unknown error shapes fail closed (no fallback).
Recheck these prefixes when upgrading CDK.

CDK now owns callback validation and invoice checks. In 0.18.2 it requires HTTPS
callbacks on the Lightning Address domain; delegated callback hosts accepted by
the previous wallet resolver are rejected. The binding also owns callback query
parameter handling.

`LightningAddressResolverTests` (iOS) and `LightningAddressResolverTest`
(Android) cover LNURL-first routing for both mint capabilities, missing discovery
fallback, temporary/invalid recipient failures without fallback, and distinct
recipient-service, BIP-353/DNS and mint messages. These are deterministic policy
and error-contract tests using CDK FFI errors, not live DNSSEC or payment tests.

The opt-in [native binding harness](../../CI/lightning-address-tests/README.md)
also drives the production service/gateway through real Swift/Kotlin bindings,
native CDK, SQLite, TLS recipient stubs, DNSSEC-validated cached public test
records, and mint stubs. It verifies the five requested scenarios plus DNS-failure
and unsupported-mint cases on both platforms. Tests end at the melt quote; no
real funds are paid. The harness uses the unchanged 0.18.2 resolver/payment code
with an additive test-CA loading feature, rather than the shipped native binaries.

A separate upstream limitation remains: CDK 0.18.2 rejects BIP-353 URIs containing
an `lnurl` parameter alongside `lno`. The native harness documents this observed
limitation and uses a signed BOLT12 record without `lnurl` for successful fallback.
