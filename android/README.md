# Cashu Wallet Android

This folder is the Kotlin/Android rewrite of the Swift Cashu Wallet app.

## Implementation State

The Android app is a native Jetpack Compose implementation with the same broad source sections as the Swift app:
`App`, `Core`, `Core/Protocols`, `Core/Services`, `Core/Navigation`, `Models`, `Resources`, and `Views`.

Implemented runtime coverage includes:

- CDK-backed wallet creation, restore, delete, mint management, mint quotes, melts, token send/receive, transaction loading, and payment request handling.
- Android Keystore-backed secure storage, DataStore-backed app settings, wallet-scoped reset boundaries, and CDK database migration/recovery helpers.
- Compose flows for onboarding, wallet home, history, mints, receive, send/pay, scanner, contactless NFC, and settings.
- Android manifest permissions for internet, camera, NFC, vibration, optional hardware features, backup exclusions, and `cashu:` deep links.
- Product/design parity tokens from `../docs/product/PRODUCT.md`, `../docs/product/DESIGN.md`, `../docs/product/DESIGN.json`, and the button prompt files through `CashuTheme`, shared action buttons, quiet cards, semantic state colors, and amount display controls.

## Build

For a paid deposit whose quote key was lost, use **Settings → Backup & Restore →
Recover a deposit**. See the [deposit recovery guide](../docs/product/deposit-recovery.md)
for requirements and supported payment methods.

Use JDK 17 and a local Android SDK:

```sh
cd android
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
./gradlew --no-daemon :app:assembleDebug
```

Useful verification targets:

```sh
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:assembleRelease
```

For emulator UI journeys, deterministic fixtures, local-mint tests, screenshot
goldens, and CI tiers, see [`../docs/android/TESTING.md`](../docs/android/TESTING.md).

The CDK dependency is managed by Gradle as `org.cashudevkit:cdk-kotlin` in `gradle/libs.versions.toml`.

## Contactless interoperability

Contactless Pay supports standard NDEF payment tags and Android/Numo receivers,
plus iOS receivers using Apple's approved custom AID `F999711510411700`.
Standard tags use Android's `Ndef` API. Custom-AID receivers use `IsoDep` with
the same Type 4 capability container, NDEF file, and read/write APDUs. The
connection stays open from request reading through token writing; the final
NLEN commits the complete token. Ambiguous writes are not automatically retried.
Reader mode remains active through the payment success screen so phones that
are still touching cannot switch the payer into NFC receive handling. Further
tag callbacks are ignored after success; closing the send sheet releases reader
mode.

The request's NFC indicator shows **Tap to Receive** when ready, matching iOS's
button label without an explanatory paragraph. Android receiving continues to
advertise the standard AID `D2760000850101`,
preserving existing payer compatibility. Older payers that only recognize that
AID need an update to pay an iOS receiver. See the [iOS HCE guide](../ios/README.md#receiving-by-tap-hce)
for entitlement requirements and physical-device acceptance checks.

The JVM `NfcType4ReaderTest` suite verifies custom AID selection and the shared
Android receiver protocol, chunking, text/binary records, write limits, malformed
responses, and lost final acknowledgments. Real NFC discovery and radio behavior
still require an Android phone and an entitled physical iPhone.
