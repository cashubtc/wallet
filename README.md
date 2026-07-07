# Cashu Wallet

A privacy-first wallet for Cashu ecash and the Lightning Network, with on-chain Bitcoin support and NFC contactless payments.

The iOS app is the production reference implementation. The Android app in `android/` is a native Jetpack Compose implementation being brought to feature and UX parity before public release.

## Features

- **Ecash** — mint, send, and redeem Cashu tokens across multiple mints
- **Lightning** — pay and receive BOLT11 invoices, with Lightning Address support
- **On-chain** — send to and receive from regular Bitcoin addresses
- **Contactless (NFC)** — tap-to-pay using NDEF tags
- **Nostr** — NWC (Nostr Wallet Connect), payment requests, and NPC integration
- **P2PK** locking, multi-mint discovery, and deterministic recovery from seed
- Backup & restore from BIP-39 seed phrase

## Screenshots

| Launch | Onboarding | Wallet |
| :---: | :---: | :---: |
| ![Launch](docs/screenshots/01-launch.png) | ![Welcome](docs/screenshots/02-welcome.png) | ![Wallet](docs/screenshots/03-main-wallet.png) |

| Send options | Settings |
| :---: | :---: |
| ![Send](docs/screenshots/04-send-options.png) | ![Settings](docs/screenshots/05-settings.png) |

| Receive on-chain | Send on-chain |
| :---: | :---: |
| ![Receive on-chain](docs/screenshots/06-receive-onchain.png) | ![Send on-chain](docs/screenshots/07-send-onchain.png) |

## Building

### iOS

Open `CashuWallet.xcodeproj` in Xcode 16+ and run on an iOS 18 simulator or device. Swift Package Manager resolves `cdk-swift` automatically.

For a CLI build to the simulator:

```sh
xcodebuild -project CashuWallet.xcodeproj \
  -scheme CashuWallet \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  build
```

### Android

Android builds live in `android/` and use Gradle with `cdk-kotlin`. The current local and CI gates are:

```sh
cd android
./gradlew --no-daemon :app:testDebugUnitTest
./gradlew --no-daemon :app:lintDebug
./gradlew --no-daemon :app:assembleRelease
```

## Project layout

- `CashuWallet/App` — app entry point and root view
- `CashuWallet/Core` — services (wallet, mints, NFC, Nostr, keychain), navigation, settings
- `CashuWallet/Views` — SwiftUI views grouped by flow (Send, Receive, Mints, History, Settings)
- `CashuWallet/Models` — data types and protocols
- `android/app/src/main` — native Android app, Core services, Compose UI, models, and resources
- `android/app/src/test` — Android JVM unit coverage for parsers, protocol helpers, storage, settings, history, mints, requests, and UI support logic
