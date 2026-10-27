---
name: wallet-version-bump
description: Update Cashu Wallet iOS and Android app versions and build identifiers for a release, including version-dependent tests and verification.
---

# Wallet version bump

Inspect the current branch, release configuration, and repository guidance before making changes. Confirm the requested version and platform scope; follow the repository's cross-platform parity guidance.

- Set Android's `versionName` in `android/app/build.gradle.kts` and increment `versionCode` above the highest code already used in Play Console or other uploads. The repository value alone cannot establish the highest uploaded code; state that limitation if it cannot be checked.
- Set `MARKETING_VERSION` in both iOS app configurations in `ios/CashuWallet.xcodeproj/project.pbxproj` and update `CURRENT_PROJECT_VERSION` as appropriate for the release. Do not assume the repository build number reflects every uploaded build.
- Search the repository for old version literals and version-related assertions, especially `ios/CashuWalletTests/AppVersionTests.swift`; distinguish app versions from dependency versions. Update tests that intentionally assert the release version.
- Run the affected iOS unit tests and appropriate Android build checks. If the user requests in-app verification, build and inspect the Settings footer on both platforms in isolated simulators/emulators; do not erase or overwrite existing wallets. State precisely which tests ran and on which revision.
- Review the diff, including both build configurations and the Android code. Report the version and build identifiers, validation, and any unverified store-upload constraints. Create or update a PR only when requested.
