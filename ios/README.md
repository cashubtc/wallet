# Cashu Wallet iOS

A privacy-first iOS wallet for Cashu ecash and the Lightning Network, with on-chain Bitcoin support and NFC contactless payments.

Built with SwiftUI, targets iOS 18+, and uses [cdk-swift](https://github.com/asmogo/cdk-swift) (the Cashu Dev Kit) under the hood.

## Features

- **Ecash** — mint, send, and redeem Cashu tokens across multiple mints
- **Lightning** — pay and receive BOLT11 invoices, with Lightning Address support
- **On-chain** — send to and receive from regular Bitcoin addresses
- **Contactless (NFC)** — tap-to-pay using NDEF tags; receive ecash by tap on eligible iPhones with HCE enabled
- **Nostr** — NWC (Nostr Wallet Connect), payment requests, and NPC integration
- **P2PK** locking, multi-mint discovery, and deterministic recovery from seed
- Backup & restore from BIP-39 seed phrase
- [Recover a deposit](../docs/product/deposit-recovery.md) using a known mint quote ID and the original wallet seed.

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

iCloud restore keeps the loading spinner and total-balance completion screen.
If a mint fails, the remaining mints are still attempted, and the user can open
the recovered wallet after all attempts settle. A partial restore shows a brief
message that some funds may still need recovery, without per-mint details.
Failed mint URLs remain in future iCloud backups;
Nostr mint backup replacement is deferred until those mints are recovered or
explicitly removed. Adding a failed mint later also retries its fund recovery.
## Receiving by tap (HCE)

Open **Receive → Ecash**, set an amount, then choose **Tap to Receive** and
hold the payer's device near the top of the iPhone. The system presents Apple's
contactless sheet. The same action is available when reopening a request in
History. HCE is enabled in the app target’s Debug and Release configurations.
The action is hidden in Simulator, reader-only builds, and on ineligible devices.

The payer reads a transport-free NUT-18 request and writes the token back using
the NFC Forum Type 4 / Numo file and APDU protocol. iOS receivers use Apple's
approved custom AID `F999711510411700`; Android receivers continue to use the
standard NDEF AID `D2760000850101`. Updated iOS and Android payers support both.
Older payers, including third-party Numo implementations that select only the
standard AID, need an update to pay an iOS receiver. The shared QR retains its
Nostr transport. NFC accepts text, URI token links, and binary Cashu MIME records.
Tokens are saved before the final write is acknowledged. Known-mint tokens are
redeemed through the existing wallet operation coordinator and attributed to the
request. Unfamiliar mints open the existing token review screen; **Review Later**
keeps the token in History. Tokens with a wrong amount, unit, or mint are saved
for review without fulfilling the request. Failed claims remain recoverable there.

Unlike Android, iOS deliberately requires review of unfamiliar mints rather than
automatically converting their tokens into the default mint. Future automatic
conversion would need a separate implementation of Android's fee limits and
settlement recovery. NFC availability also differs because Apple restricts HCE.

### Signing and availability

Apple's [HCE entitlement](https://developer.apple.com/support/hce-transactions-in-apps/)
requires approval for the app's bundle ID and AID `F999711510411700`. Apple's
eligibility requirements apply; setting a region or adding an entitlement file
does not grant access. This app still targets iOS 18+, above CardSession's iOS
17.4 minimum. HCE cannot be exercised in Simulator.

The app target requests HCE in both Debug and Release. After Apple assigns the
capability, enable it for `com.cashu.me` in Certificates, Identifiers & Profiles
and regenerate the development **and** distribution provisioning profiles. The
profile must authorize HCE and AID `F999711510411700`; development-only approval
does not authorize TestFlight or App Store distribution.

Build or archive the `CashuWallet` scheme normally. `HCE.xcconfig` also explicitly
selects the same configuration for command-line builds:

```sh
xcodebuild -project CashuWallet.xcodeproj -scheme CashuWallet \
  -destination 'generic/platform=iOS' -xcconfig HCE.xcconfig build
```

Developers whose team does not have HCE approval can build with
`-xcconfig NFCReaderOnly.xcconfig`. In Xcode, the equivalent app-target settings
are `CASHU_HCE_ENABLED = NO` and
`CODE_SIGN_ENTITLEMENTS = CashuWallet/CashuWallet.entitlements`. This preserves
NFC sending and hides receiving by tap. Simulator tests need no Apple approval.

Before installing or distributing, inspect the **signed app** with
`codesign -d --entitlements :- <app-path>` and its embedded profile with
`security cms -D -i <app-path>/embedded.mobileprovision`. Both must contain
`com.apple.developer.nfc.hce = true` and an approved AID/RID prefix covering
`F999711510411700`. If Xcode reports a select-identifier-prefixes mismatch, verify
that the checkout being built requests this custom AID and that its provisioning
profile grants the same AID. Changing only the entitlement in an older checkout
is insufficient: its receiver and both payer implementations also need updating.
An unsigned SDK build only checks compilation, not entitlement
provisioning or radio operation.

The app checks `NFCReaderSession.readingAvailable`, `CardSession.isSupported`,
and `CardSession.isEligible` before offering the action. It does not register as
the default contactless app or start on field detection/double-click.

After a token is retained and acknowledged, the receiver acquires an optional
[presentment-intent assertion](https://developer.apple.com/documentation/corenfc/nfcpresentmentintentassertion)
before dismissing the native NFC sheet. The assertion outlives the transport and
result screen so the payer's remaining RF field does not immediately open Apple
Wallet. Its lifetime starts at completion, not while waiting for the payer or mint.
Claiming or reviewing the token proceeds immediately. iOS limits suppression to
15 seconds and invalidates it on background; a quick second receipt reuses any
still-valid assertion. Apple's cooldown or an unavailable assertion must not
prevent a persisted payment from being claimed. Separate the phones after payment.

### Verification

`NFCReceiveTests` covers request encoding, APDU selection/read/write, both NLEN
write orders, missing chunks, reconnect resets, malformed NDEF, binary records,
and request terms. `NFCReceiveLifecycleTests` covers cancellation, stale sessions,
review, and failed claims. These tests run in Simulator without HCE approval.
`NFCReceivePresentmentTests` covers protection after receive returns, bounded
release, rapid successive receipts, invalid assertions, and cooldown failures.
`NFCType4ReaderTests` covers custom application selection, request reading,
chunked writes with NLEN committed last, capacity/read-only checks, malformed
responses, and an ambiguous final acknowledgment without automatic retransmission.
`NFCReceiveIntegrationTests` uses the [CI fake mint](../CI/README.md) to create
real test tokens, transfer them in text and binary APDUs, redeem them, and verify
that a duplicate cannot credit twice.

Before shipping an HCE build, use an entitled physical iPhone and updated Android
and iOS payers to verify:

- Fixed-amount receipt at a known mint, with credited amount and linked History.
- New-mint review, Review Later, app restart, and claim from History.
- Wrong amount/unit/mint, offline redemption, and duplicate delivery.
- Both text and binary token writes, including a token larger than one APDU.
- Separation during a write, retap, native-sheet cancellation/timeout, background,
  request edits, and a second receipt on a reusable request.
- No contactless action on an ineligible device; ordinary NFC sending still works.
- Updated iOS and Android payers can still pay a standard Android/Numo receiver
  and read ordinary NDEF payment tags.
- After success, briefly keep the phones together: Apple Wallet should not open
  on the receiver and Android should not show a receive-app chooser. Also test a
  receive that takes over 15 seconds before completion and rapid repeated taps
  during Apple's presentment cooldown; radio behavior requires physical devices.

### Reference implementation

Investigated Macadamia's
[NFCRequestEmulation.swift](https://github.com/zeugmaster/macadamia/blob/73bf9d378553e8ac37f7137700163606e333b835/macadamia/Wallet/NFCRequestEmulation.swift)
and its RequestView integration on the `release` branch. It uses a foreground
`CardSession`, an optional presentment-intent assertion, and Type 4 tag emulation
to receive an in-band token. This implementation follows that approach while
implementing the protocol from our Android counterpart, with complete-write
tracking, binary token support, persistent recovery, and the wallet's own claim
and mint-review paths. Application selection differs from Macadamia's standard
NDEF AID because this app's Apple approval covers the custom AID above.
