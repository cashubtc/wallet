# Cashu Wallet Android

This folder is the Kotlin/Android implementation of Cashu Wallet. iOS remains the production reference; Android is an unreleased native parity target, so legacy Android behavior and data migrations are intentionally not preserved unless a future release requires them.

## Implementation State

The Android app is a native Jetpack Compose implementation with the same broad source sections as the Swift app:
`App`, `Core`, `Core/Protocols`, `Core/Services`, `Core/Navigation`, `Models`, `Resources`, and `Views`.

Implemented runtime coverage includes:

- CDK-backed wallet creation, staged restore, delete, mint management, mint quotes, melts, token send/receive, transaction loading, and payment request handling.
- Android Keystore-backed secure storage, DataStore-backed app settings, wallet-scoped reset boundaries, and CDK database migration/recovery helpers.
- Compose flows for onboarding, wallet home, history, mints, receive ecash, receive Lightning/BOLT12/on-chain, unified send/pay, scanner, contactless NFC, locked ecash/P2PK, Nostr, privacy, backup, and settings.
- Cashu Request creation/detail/history/payment support, Nostr relay management, Sentry opt-in, local app lock, responsive QR/amount surfaces, Android back handling, and Material 3 bottom sheets/dialogs.
- Android manifest permissions for internet, camera, NFC, vibration, optional hardware features, backup exclusions, and deep links.
- Product/design parity tokens from `PRODUCT.md`, `DESIGN.md`, `DESIGN.json`, `android/DESIGN-ANDROID.md`, and `android/UX_SPEC.md` through `CashuTheme`, shared action buttons, semantic state colors, and amount display controls.

Known release blockers are tracked in `../ANDROID_UPDATE_PLAN.md`: device/emulator Compose tests, screenshot/accessibility checks, Macrobenchmark/JankStats profiling, managed-device CI, real-mint validation, and manual iOS/Android parity walkthroughs.

## Build

Use JDK 21 or Android Studio's bundled JBR plus a local Android SDK. On macOS with Android Studio installed:

```sh
cd android
ANDROID_STUDIO_APP="$(mdfind "kMDItemCFBundleIdentifier == 'com.google.android.studio'" | head -n 1)"
if [ -z "$ANDROID_STUDIO_APP" ]; then ANDROID_STUDIO_APP="/Applications/Android Studio.app"; fi
export JAVA_HOME="$ANDROID_STUDIO_APP/Contents/jbr/Contents/Home"
./gradlew --no-daemon :app:assembleDebug
```

Useful verification targets:

```sh
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:assembleRelease
```

The CDK dependency is managed by Gradle as `org.cashudevkit:cdk-kotlin` in `gradle/libs.versions.toml`.
