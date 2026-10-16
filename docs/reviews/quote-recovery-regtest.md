# Quote recovery with a paid, unminted Bitcoin deposit

The recovery confirmation on iOS now uses a single item-backed presentation.
The validated request is the sheet's payload; a separate presentation Boolean
can no longer open the sheet while its request is absent. Android already uses
its approval request to drive the native confirmation sheet.

The reported intermittent blank sheet did not reproduce in the automated
before-change simulator run. The new regression covers the first tap without
opening options, cancellation, and reopening with a different mint. A physical
device confirmation remains useful for this intermittent issue.

## What the E2E test does

1. Start an isolated Bitcoin **regtest** node and a local CDK mint with **BDK**.
   This uses actual Bitcoin transactions and block confirmations, not fakewallet
   payment notifications. No mainnet/testnet funds or existing wallet are used.
2. Generate a fresh test seed and derive a NUT-20 locking pubkey at
   `m/129373'/20'/0'/0'/{counter}`. Ask the mint for an on-chain quote with that
   pubkey. The fixture retains no original wallet database or quote secret key.
3. Pay **21,000 sats**, mine **three confirmations**, and wait for BDK to report
   `amount_paid = 21000` and `amount_issued = 0`. The fixture never calls an
   issuance endpoint.
4. Start the native app with only that seed and a fresh database with no mints.
   Enter the mint URL and copied quote ID, select **On-chain address**, open
   confirmation once, and approve recovery.
5. Assert that the native app reports recovery and the mint records exactly
   21,000 sats issued. Retrying must report **Already claimed** without issuing
   more ecash. iOS also relaunches the app before retrying.
6. An additional iOS service test rejects an unrelated seed without importing
   or queueing the quote, recovers with the correct seed, reopens SQLite, checks
   persisted balance/key/history, and sends all recovered ecash to a second
   wallet. The second wallet redeems it at the mint and holds 21,000 sats.

A fresh seed per scenario matters: NUT-09 deliberately restores every previous
proof belonging to that seed. Reusing the same test seed across scenarios
inflates balances by correctly recovering other scenarios' funds.

## Validation on 2026-10-15

- Wallet bindings: CDK **0.18.2** on both platforms.
- Local mint binary: `cdk-mintd 0.18.0-rc.1`, BDK/Bitcoin RPC backend.
- iOS simulator: **5 passed, 0 failed, 0 skipped**, covering the first-tap sheet,
  real-payment UI recovery/relaunch, wrong-seed rejection, SQLite persistence,
  spending recovered ecash, and request validation.
- Android emulator (API 36): **1 passed, 0 failed, 0 skipped**, covering native
  confirmation, paid/unminted recovery, exact balance, one receipt, and retry.
- The reusable launcher also created a fresh paid/unminted regtest deposit and
  stopped its Bitcoin/CDK/fixture services on shutdown.
- An additional opt-in token-capture run passed (1/1, zero skips). The exported
  token decoded to 21,000 sats; an independent mint proof-state query confirmed
  that all 42 proofs were spent after redemption. The token stays local.
- Python syntax, shell syntax, and `git diff --check` passed.

These results exercise local simulators/emulators. Physical-device behavior and
mainnet payments were not part of this test. The iOS presentation fix still needs
a check on the device where the intermittent blank sheet was observed.

## Run locally

Prerequisites: Bitcoin Core (`bitcoind`, `bitcoin-cli`), Python with
`cryptography` and `mnemonic`, Xcode/iOS Simulator and/or Android SDK/emulator,
and a `cdk-mintd` build with BDK's Bitcoin RPC backend and the database-backed
`config init --file` command. Existing `CI/setup-cdk.sh` uses fakewallet and is
not the setup for this test.

Start the services in a separate terminal, supplying your mint binary:

```sh
CDK_MINTD_BIN="$MINTD_BINARY" bash CI/run-quote-recovery-regtest.sh
```

The launcher creates a temporary data directory, prints its location, binds the
mint and fixture to loopback, and stops Bitcoin/CDK when interrupted. Ports:
Bitcoin RPC **18483**, CDK mint **3341**, fixture **3340**. Use a fresh data
directory on each launch. `RECOVERY_REGTEST_DIR` optionally selects it.

Run native suites serially against this fixture. iOS:

```sh
xcodebuild test \
  -project ios/CashuWallet.xcodeproj -scheme CashuWallet \
  -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" \
  -parallel-testing-enabled NO \
  -only-testing:CashuWalletUITests/SettingsUITests/testDepositRecoveryConfirmationHasContentOnFirstTapWithoutOpeningOptions \
  -only-testing:CashuWalletUITests/SettingsUITests/testPaidUnmintedOnchainQuoteRecoversThroughUIAndSurvivesRelaunch \
  -only-testing:CashuWalletTests/DepositRecoveryTests/testRegtestRecoveryRejectsWrongSeedPersistsAndProducesSpendableEcash
```

Android (reverse ports on the test emulator, then run from `android`):

```sh
adb reverse tcp:3340 tcp:3340
adb reverse tcp:3341 tcp:3341
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cashu.me.ui.settings.RegtestDepositRecoveryJourneyTest \
  -Pandroid.testInstrumentationRunnerArguments.cashu.regtestRecovery=true
```

The iOS regtest tests skip if the fixture is unavailable. Android requires the
explicit instrumentation flag. Require **zero skips** when reporting an E2E
run. Screenshots are XCTest attachments and Android test-storage outputs.
Per-quote JSON evidence in the launcher's `evidence` directory contains the
transaction ID, confirming blocks, paid/unissued state, and state after recovery.
Seeds, signing keys, and ecash tokens are not written to that evidence. To inspect
the spendability test's token locally, set `RECOVERY_EXPORT_SPENT_TOKEN=1` in
the **CashuWalletTests test runner environment**. The test attaches the token
only after the second wallet has redeemed it. This export is off by default;
keep result bundles containing token attachments out of Git and PR uploads.

## Protocol references

[NUT-20](https://github.com/cashubtc/nuts/blob/main/20.md) specifies the quote
signature and deterministic locking-key path. On-chain quotes require that lock
under [NUT-30](https://github.com/cashubtc/nuts/blob/main/30.md).
