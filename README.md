# Cashu Wallet

Cashu Wallet is a native wallet for Cashu ecash, Lightning, on-chain Bitcoin,
Nostr payment requests, and NFC contactless payments.

This repository contains the two supported platform apps:

- `ios/` - SwiftUI iOS wallet, Xcode project, iOS tests, and iOS helper scripts.
- `android/` - Kotlin/Jetpack Compose Android wallet and Gradle project.

Shared product and design references live in `docs/product/`. Platform-specific
notes live in `docs/ios/` and `docs/android/`. Integration mint scripts and live
mint test infrastructure remain in `CI/`.

## Build

For iOS:

```sh
cd ios
xcodebuild -project CashuWallet.xcodeproj \
  -scheme CashuWallet \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  build
```

For Android:

```sh
cd android
./gradlew --no-daemon :app:assembleDebug
```

See `ios/README.md` and `android/README.md` for platform details.
