# NIP-44 CDK boundary coverage

Both apps pin CDK 0.18.2. Its generated Swift `Cdk` module and Android
`org.cashudevkit:cdk-android` sources expose `nip44Encrypt` and `nip44Decrypt`,
with string parameters `nostrSecretKey`, `recipientPubkey`/`senderPubkey`, and
`plaintext`/`payload`. The NIP-17 decoder calls `nip44Decrypt` directly for both
layers. No app crypto adapter is needed. Android request construction and inbox
identity parsing use the existing `NostrService.hexToBytes` utility.

The focused tests use three fixed payloads from the [NIP-44 v2
vectors](https://github.com/paulmillr/nip44/blob/main/nip44.vectors.json), including
non-ASCII text. They also exercise encryption/decryption across padding
boundaries and the pinned encryption size limit, invalid keys and sizes, malformed
base64, wrong recipients, and corrupted version, nonce, ciphertext and MAC.
NIP-17 integration tests preserve the nested request fixture and exercise
failures at the outer and inner decrypt boundaries.

Run iOS `CashuWalletTests/NIP44Tests` with the project's simulator test scheme.
Run Android's `com.cashu.me.Core.NIP44AndNIP17Test` through
`:app:connectedDebugAndroidTest` with the instrumentation runner class filter.
Android coverage lives in `androidTest` because CDK ships Android native
libraries; a host JVM test cannot exercise that binding by itself.

## Coordination with #410

This is the crypto-only portion of [#408](https://github.com/cashubtc/wallet/issues/408)
and [#409](https://github.com/cashubtc/wallet/issues/409). The separate
[NostrInbox migration](https://github.com/cashubtc/wallet/issues/410) owns relay
subscriptions, callbacks and lifecycle integration. When it removes local
NIP-17 decoding, remove its direct CDK decrypt calls and the decoder-specific
tests together. Keep the independent CDK vector and error-boundary tests;
replace nested decoder coverage with signed gift-wrap delivery tests through
CDK's inbox. Do not introduce another NIP-44 wrapper during that migration.

## Pinned encryption limit

Native tests on both platforms confirm that CDK 0.18.2 encrypts 65,408 bytes
but rejects 65,409 and 65,535 bytes. The removed implementation allowed
65,535 bytes. This is a CDK encryption boundary, not an app-side workaround;
production callers here only decrypt. Keep the explicit boundary checks when
updating CDK and reassess this limit before adding an encryption caller.

Validation: iOS simulator, 5 focused tests passed; Android API 37 emulator,
6 focused tests passed through the direct instrumentation runner. The initial
Gradle connected-test invocation aborted before discovery because the emulator
could not load the orchestrator's `ShellMain`; running the same test APK directly
completed successfully.
