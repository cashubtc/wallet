# Android Security And Backup Model

Status date: 2026-07-07

This document records the Milestone 1 Android security decisions from `ANDROID_UPDATE_PLAN.md`.

## App Lock

- Android App Lock is a UI/privacy gate, not a wallet-storage encryption boundary.
- It uses AndroidX BiometricPrompt with `BIOMETRIC_WEAK` or `DEVICE_CREDENTIAL`, matching the iOS intent of biometrics or passcode.
- If the device cannot authenticate because no screen lock is configured, App Lock cannot be enabled and the wallet fails open rather than trapping funds behind an impossible gate.
- The lock grace period is 30 seconds, matching the iOS grace window.
- When App Lock is enabled, Android sets `FLAG_SECURE` so app-switcher snapshots and screenshots do not expose wallet balances or secrets.
- A Compose privacy cover is also shown during inactive/background lifecycle states and the full-screen lock gate covers shell overlays.

## Secret Reveal

- Seed phrase reveal and seed phrase copy require local authentication.
- Nostr nsec reveal and nsec copy require local authentication.
- P2PK private-key reveal/copy will use the same authentication path when P2PK backup/export UI lands.

## Cloud Backup

Android cloud seed backup is intentionally not enabled in this milestone.

Reasons:

- The current secure store uses an Android Keystore key generated on the device.
- Android Auto Backup cannot restore that Keystore key onto a different device in a way that would decrypt existing ciphertext reliably.
- Backing up seed material without a user-controlled encryption or platform account model would weaken the wallet's security posture.

Current behavior:

- `android:allowBackup` remains `false`.
- Backup XML files continue to exclude secure storage and CDK wallet database paths.
- No cloud-backup settings are shown to users.
- Manual seed backup remains the supported recovery path until a future Android cloud-backup design is implemented.

Future acceptable designs:

- Google Drive app-data backup with user-mediated account access and app-side encryption.
- Android Auto Backup with a user-held encryption secret rather than device-only Keystore decryption.
- Google Block Store or Credential Manager based restore if it can preserve the same user consent and recovery guarantees as iOS.
