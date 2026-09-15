# Cashu Wallet iOS

A privacy-first iOS wallet for Cashu ecash and the Lightning Network, with on-chain Bitcoin support and NFC contactless payments.

Built with SwiftUI, targets iOS 18+, and uses [cdk-swift](https://github.com/asmogo/cdk-swift) (the Cashu Dev Kit) under the hood.

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
| ![Launch](../docs/screenshots/01-launch.png) | ![Welcome](../docs/screenshots/02-welcome.png) | ![Wallet](../docs/screenshots/03-main-wallet.png) |

| Send options | Settings |
| :---: | :---: |
| ![Send](../docs/screenshots/04-send-options.png) | ![Settings](../docs/screenshots/05-settings.png) |

| Receive on-chain | Send on-chain |
| :---: | :---: |
| ![Receive on-chain](../docs/screenshots/06-receive-onchain.png) | ![Send on-chain](../docs/screenshots/07-send-onchain.png) |

## Building

Open `ios/CashuWallet.xcodeproj` from the repository root, or open
`CashuWallet.xcodeproj` from this folder, in Xcode 16+ and run on an iOS 18
simulator or device. Swift Package Manager resolves `cdk-swift` automatically.

For a CLI build to the simulator from this folder:

```sh
xcodebuild -project CashuWallet.xcodeproj \
  -scheme CashuWallet \
  -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
  build
```

## Project layout

- `CashuWallet/App` — app entry point and root view
- `CashuWallet/Core` — services (wallet, mints, NFC, Nostr, keychain), navigation, settings
- `CashuWallet/Views` — SwiftUI views grouped by flow (Send, Receive, Mints, History, Settings)
- `CashuWallet/Models` — data types and protocols

## Reinstall and onboarding

An iOS Keychain seed can survive deleting the app while its local database and
preferences are removed. A seed alone must not activate a wallet on reinstall:
show onboarding and wait for an explicit create or restore choice. Keep the
surviving secret in Keychain until that choice replaces it. Existing installs
retain their onboarding marker; older installs without the marker migrate only
when the current or legacy wallet database is present.

For regression testing, use a disposable simulator wallet: complete onboarding,
uninstall and reinstall the app, and verify that onboarding appears on both the
first launch and a subsequent relaunch. Create Wallet must generate a fresh seed.
Separately verify that an upgrade preserves a completed wallet and that relaunching
unfinished onboarding preserves the seed already shown to the user.
