# Lightning Address native binding scenarios

These opt-in tests exercise the production iOS `LightningService` and Android
`CdkWalletGatewayImpl`, released 0.18.2 generated bindings, native CDK, SQLite,
TLS LNURL endpoints, DNSSEC resolution, mint quote requests, and the production
user-facing error mapper. They do not replace CDK functions or fabricate FFI
exceptions. They stop at the melt quote: no real funds are paid.

## Trust and DNS isolation

The release binaries use bitreq's fixed WebPKI roots and CDK's fixed DNS-over-TCP
resolver addresses. The harness builds **the exact CDK v0.18.2 source with one
additive bitreq feature**, `async-https-rustls-probe`, to load a test CA through
`SSL_CERT_FILE`. Resolver/payment code and the released Swift/Kotlin generated
bindings are unchanged. This qualifies the native integration, not the
byte-for-byte shipped binaries. Production package pins remain 0.18.2.

All recipient HTTPS sockets are redirected to a local TLS server; TLS and invoice
validation remain enabled. On iOS, a simulator-only DYLD interposer redirects
sockets in the test host. On Android, a temporary root-owned NAT chain redirects
only the wallet test app's UID. The runner removes its NAT chain and reverse
ports in `finally`. Use a disposable rooted arm64 emulator. No host routing or
trust store is changed. Never link `redirect-dns.c` into a product target.

For BIP-353, the DNS stub obtains and caches authentic wire responses for the
public `test@twelve.cash` dual-purpose name and the documented BIP-353 example
[`matt@mattcorallo.com`](https://github.com/btc-go/bip353#live-examples), including their root/DS/DNSKEY/signature chains.
Native CDK still builds the proof and validates signatures, names and time
against the real DNS root. It does not trust an AD bit or a fake TXT string.
Only these test names and their ancestor zones can be fetched. Recipient HTTP and
mint requests are never forwarded to public services.

The first run populates the DNS cache via Google's DNS-over-HTTPS service; repeat
with `--offline` to prove the tests run entirely against stubs. DNSSEC signatures
expire: use a fresh fixture directory when refreshing them. Never commit private
CA keys, generated binaries, raw build logs, simulator identifiers or local paths.
Run platforms serially: each scenario resets the shared stub state.

## Setup

Use a temporary work directory outside the repository and download CDK v0.18.2
sources with `gh api repos/cashubtc/cdk/tarball/v0.18.2`. Extract them there.
Create a Python 3.11+ virtual environment and install `requirements.txt`.
Install Rust targets `aarch64-apple-ios-sim` and `aarch64-linux-android`.

Set `WORK` to the temporary directory, `CDK_SOURCE` to the extracted source,
`SWIFT_PACKAGES` to an existing resolved SwiftPM packages directory, `CDK_AAR` to
the released `cdk-android-0.18.2.aar`, and `NDK` to an installed Android NDK.
No values are saved in the repository.

```sh
python CI/lightning-address-tests/prepare-bindings.py --platform ios \
  --source "$CDK_SOURCE" --workdir "$WORK" --swift-packages "$SWIFT_PACKAGES"
python CI/lightning-address-tests/prepare-bindings.py --platform android \
  --source "$CDK_SOURCE" --workdir "$WORK" --android-aar "$CDK_AAR" --ndk "$NDK"
python CI/lightning-address-tests/server.py --directory "$WORK/fixtures"
```

Keep the server running in a separate terminal. It binds only loopback ports
18444 (control/mint), 18445 (TLS LNURL), and 15353 (DNS-over-TCP).

## iOS simulator

```sh
xcodebuild -project ios/CashuWallet.xcodeproj -scheme CashuWallet \
  -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" \
  -derivedDataPath "$WORK/ios-derived" -clonedSourcePackagesDirPath "$WORK/packages" \
  -disableAutomaticPackageResolution build-for-testing
python CI/lightning-address-tests/configure-ios.py --workdir "$WORK" \
  --xctestrun "$XCTESTRUN"
xcodebuild test-without-building -xctestrun "$XCTESTRUN" \
  -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" \
  -only-testing:CashuWalletTests/LightningAddressBindingTests \
  -parallel-testing-enabled NO -resultBundlePath "$WORK/ios-network.xcresult"
```

`XCTESTRUN` is the generated file in `ios-derived/Build/Products`. Reconfigure it
after rebuilding; Xcode rewrites its environment. Require seven passed tests,
zero failures and zero skips in the result bundle. Ordinary unit runs skip this
suite unless the fixture environment is explicitly enabled.

## Android emulator

```sh
./android/gradlew -p android -I "$PWD/CI/lightning-address-tests/fixture.gradle" \
  -PlightningFixtureAar="$WORK/cdk-fixture.aar" \
  :app:assembleDebug :app:assembleDebugAndroidTest
python CI/lightning-address-tests/run-android.py \
  --apk android/app/build/outputs/apk/debug/app-debug.apk \
  --test-apk android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
```

Require `OK (7 tests)`. This runner fails if any test fails or skips the suite.
The Gradle override preserves the released AAR's classes and JNA runtime,
replacing only its arm64 native library for this invocation.

## Assertions

| Scenario | Required trace and result |
| --- | --- |
| Working dual-purpose name, BOLT11-only mint | LNURL discovery/callback, BOLT11 mint quote; no BIP-353 DNS |
| Working dual-purpose name, BOLT12-capable mint | Same LNURL-first BOLT11 route; no BIP-353 DNS |
| Recipient HTTP 502 | Recipient-service message including HTTP 502; no BIP-353 DNS or mint quote |
| LNURL HTTP 404, BOLT12 support | DNSSEC proof verified by native CDK, BOLT12 offer passed to mint, BOLT12 quote |
| Mint drops its connection | LNURL succeeds; native mint connection error maps to mint-specific message; no DNS fallback |
| DNS connection fails after LNURL 404 | BIP-353 message; no mint quote |
| LNURL 404, BOLT11-only mint | Unsupported BOLT12 message; no DNS or mint quote |

CDK 0.18.2 rejects the BIP-353 URI in the public twelve.cash record because it
contains an `lnurl` parameter alongside `lno` (`LNURL resolution not supported`).
The successful DNS fallback case therefore uses the documented public BIP-353
example with an on-chain address and BOLT12 offer, without `lnurl`. This upstream
limitation is separate from LNURL-first routing and is not repaired here.

The LNURL stub intentionally signs a plain-description BOLT11 invoice whose
description does not commit to its metadata. Successful native quotes therefore
also verify that CDK 0.18.2 contains the fix behind the removed #2235 workaround.
