# Android Targeted Port Plan

Target branch: `fix/apply-fixes-from-before`  
Source branch: `codex/android-update-plan-implementation`

This plan ports the useful non-UI work from `codex/android-update-plan-implementation` into `fix/apply-fixes-from-before`, which is forked from current `main`. Do not cherry-pick whole commits. Port small backend, security, protocol, CI, and focused test units. Keep the current `main` Android UI structure unless a small UI change is required to expose newly wired behavior.

## Port Rules

- Do not port broad UI redesigns or branch-level visual rewrites.
- Do not port fake visual parity harnesses that assert old labels.
- Do not port Gradle or dependency changes that downgrade current `main` choices.
- Use branch-qualified references for source code, for example `codex/android-update-plan-implementation:path/to/file`.
- Use repo-relative paths for target files.
- Keep docs and code free of local machine paths, device names, usernames, secrets, seeds, private keys, tokens, and other PII.

## 1. CI And Gradle Gates

Source:

- `codex/android-update-plan-implementation:.github/workflows/integration-tests.yml`
- `codex/android-update-plan-implementation:android/app/build.gradle.kts`
- `codex/android-update-plan-implementation:android/gradle.properties`
- `codex/android-update-plan-implementation:android/gradle/libs.versions.toml`

Target:

- `.github/workflows/integration-tests.yml`
- `android/app/build.gradle.kts`
- `android/gradle.properties`
- `android/gradle/libs.versions.toml`

Plan:

- Add Android CI jobs for `:app:testDebugUnitTest`, no-network integration, local-mint integration, `lintDebug`, and `assembleRelease`.
- Add managed-device test job and the `pixel2Api35` managed device config.
- Add custom Gradle `Test` tasks: `androidNoNetworkIntegrationTest` and `androidLocalMintIntegrationTest`.
- Port only required dependency aliases, such as Android test runner, biometric, lifecycle compose if needed, and benchmark/profile deps only if macrobenchmark is ported.
- Do not port compile SDK downgrades or Material dependency reversions from the source branch.

## 2. CDK And Nutshell Local Mint Setup

Source:

- `codex/android-update-plan-implementation:CI/setup-nutshell.sh`
- `codex/android-update-plan-implementation:CI/start-nutshell.sh`
- `codex/android-update-plan-implementation:CI/setup-cdk.sh`
- `codex/android-update-plan-implementation:CI/start-cdk.sh`
- `codex/android-update-plan-implementation:CI/generate-bolt11-invoice.py`
- `codex/android-update-plan-implementation:CI/README.md`

Target:

- `CI/setup-nutshell.sh`
- `CI/start-nutshell.sh`
- `CI/setup-cdk.sh`
- `CI/start-cdk.sh`
- `CI/generate-bolt11-invoice.py`
- `CI/README.md`

Plan:

- Port Python 3.10-3.12 selection and venv recreation for Nutshell.
- Port hermetic Nutshell workdir, local `.env`, local-only bind, and deterministic FakeWallet delays.
- Port CDK setup idempotency, checksum verification, generated local-only mint seed, updated FakeWallet config, and sat/usd units.
- Add `generate-bolt11-invoice.py` for native local-mint melt tests.
- Keep docs focused on local integration setup, not branch release narrative.

## 3. Android Local Mint Integration Tests

Source:

- `codex/android-update-plan-implementation:android/app/src/test/java/org/cashu/wallet/liveintegration/LocalMintIntegrationTest.kt`
- `codex/android-update-plan-implementation:android/app/src/androidTest/java/org/cashu/wallet/liveintegration/NativeWalletLocalMintInstrumentedTest.kt`
- `codex/android-update-plan-implementation:android/app/src/debug/AndroidManifest.xml`
- `codex/android-update-plan-implementation:android/app/src/debug/res/xml/debug_network_security_config.xml`
- `codex/android-update-plan-implementation:android/app/src/test/java/org/cashu/wallet/App/AndroidReleaseConfigurationTest.kt`

Target:

- `android/app/src/test/java/org/cashu/wallet/liveintegration/LocalMintIntegrationTest.kt`
- `android/app/src/androidTest/java/org/cashu/wallet/liveintegration/NativeWalletLocalMintInstrumentedTest.kt`
- `android/app/src/debug/AndroidManifest.xml`
- `android/app/src/debug/res/xml/debug_network_security_config.xml`
- `android/app/src/test/java/org/cashu/wallet/App/AndroidReleaseConfigurationTest.kt`

Plan:

- Port JVM local endpoint integration first and wire it to `androidLocalMintIntegrationTest`.
- Port debug-only cleartext config for localhost, `127.0.0.1`, and `10.0.2.2`.
- Port native instrumented matrix as opt-in only, gated by runner args.
- Do not make the native instrumented matrix required in CI until adb reverse or port-forwarding is explicitly handled.

## 4. App Lock And Security

Source:

- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/Core/AppLockManager.kt`
- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/Core/AppLockPolicy.kt`
- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/ui/security/AppLockGate.kt`
- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/ui/security/WalletAuthentication.kt`
- `codex/android-update-plan-implementation:android/app/src/test/java/org/cashu/wallet/Core/AppLockPolicyTest.kt`

Target:

- `android/app/src/main/java/org/cashu/wallet/Core/AppLockManager.kt`
- `android/app/src/main/java/org/cashu/wallet/Core/AppLockPolicy.kt`
- `android/app/src/main/java/org/cashu/wallet/ui/security/AppLockGate.kt`
- `android/app/src/main/java/org/cashu/wallet/ui/security/WalletAuthentication.kt`
- `android/app/src/main/java/org/cashu/wallet/App/AppContainer.kt`
- `android/app/src/main/java/org/cashu/wallet/App/MainActivity.kt`
- `android/app/src/main/java/org/cashu/wallet/Core/SettingsManager.kt`
- `android/app/src/main/java/org/cashu/wallet/Core/SettingsStore.kt`
- `android/app/src/main/java/org/cashu/wallet/Core/Protocols/StorageProtocol.kt`
- `android/app/src/main/java/org/cashu/wallet/ui/shell/CashuApp.kt`
- `android/app/src/main/java/org/cashu/wallet/ui/settings/PrivacyScreen.kt`
- `android/app/src/test/java/org/cashu/wallet/Core/AppLockPolicyTest.kt`

Plan:

- Add app-lock setting storage and state.
- Add `AppLockManager` and pure `AppLockPolicy`.
- Switch `MainActivity` to `FragmentActivity` only if required by `BiometricPrompt`.
- Wire secure window and privacy cover into current `CashuApp` without replacing the shell.
- Add Privacy setting toggle only if runtime behavior is fully wired.
- Port `AppLockPolicyTest`.

## 5. Logging, Sentry, Release Privacy

Source:

- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/Core/AppLogger.kt`
- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/Core/SentryService.kt`
- `codex/android-update-plan-implementation:android/app/src/test/java/org/cashu/wallet/Core/AppLoggerTest.kt`
- `codex/android-update-plan-implementation:android/app/src/test/java/org/cashu/wallet/Core/SentryServiceTest.kt`
- `codex/android-update-plan-implementation:android/app/src/test/java/org/cashu/wallet/App/AndroidReleaseConfigurationTest.kt`

Target:

- `android/app/src/main/java/org/cashu/wallet/Core/AppLogger.kt`
- `android/app/src/main/java/org/cashu/wallet/Core/SentryService.kt`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/src/test/java/org/cashu/wallet/Core/AppLoggerTest.kt`
- `android/app/src/test/java/org/cashu/wallet/Core/SentryServiceTest.kt`
- `android/app/src/test/java/org/cashu/wallet/App/AndroidReleaseConfigurationTest.kt`

Plan:

- Port redaction for Cashu tokens, URLs, local paths, labeled secrets, and throwable messages.
- Ensure Sentry breadcrumbs and errors pass through redaction.
- Add release configuration tests for backup exclusions, cleartext policy, Sentry auto-init off, and secure window support.
- Avoid logging local paths in new code or test output.

## 6. Staged Restore Behavior

Source:

- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/ui/onboarding/OnboardingScreen.kt`
- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/Core/Wallet/WalletManager.kt`

Target:

- `android/app/src/main/java/org/cashu/wallet/ui/onboarding/OnboardingScreen.kt`
- `android/app/src/main/java/org/cashu/wallet/Core/Wallet/WalletManager.kt`

Plan:

- Keep current onboarding visuals.
- Port only behavior: restore method step, staged mint URL entry, multiple URL parsing, per-mint restore progress, retry, skip, and partial success.
- Reuse current wallet rollback/replacement logic already present in `WalletManager`.
- Ensure restored wallet stays in onboarding until restore flow is completed.
- Add focused tests if feasible around restore state and back behavior, not branch fake UI labels.

## 7. Cashu Request Store, Listener, Privacy

Source:

- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/Core/CashuRequestStore.kt`
- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/Core/CashuRequestListener.kt`
- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/Models/Requests/CashuRequest.kt`
- `codex/android-update-plan-implementation:android/app/src/test/java/org/cashu/wallet/Core/CashuRequestStoreTest.kt`

Target:

- `android/app/src/main/java/org/cashu/wallet/Core/CashuRequestStore.kt`
- `android/app/src/main/java/org/cashu/wallet/Core/CashuRequestListener.kt`
- `android/app/src/main/java/org/cashu/wallet/Models/Requests/CashuRequest.kt`
- `android/app/src/test/java/org/cashu/wallet/Core/CashuRequestStoreTest.kt`

Plan:

- Add store abstraction, `upsert`, `update`, `upsertQuoteIntent`, `attachPaymentByQuoteId`, `reload`, and `reset`.
- Add `quoteId` and `quoteKind` to `CashuRequest`.
- Port duplicate payment suppression and legacy payment fallback tests.
- Sanitize listener error messages with user-safe error mapping/redaction.
- Avoid porting unrelated Cashu Request UI rewrites.

## 8. Cashu Request Payment Routing And Top-Up

Source:

- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/Core/CashuPaymentRequestMintSelector.kt`
- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/Core/Wallet/WalletCashuRequestPayment.kt`
- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/ui/send/SendCashuRequestTopUp.kt`
- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/ui/send/SendDestinationResolver.kt`
- `codex/android-update-plan-implementation:android/app/src/test/java/org/cashu/wallet/Core/CashuPaymentRequestMintSelectorTest.kt`
- `codex/android-update-plan-implementation:android/app/src/test/java/org/cashu/wallet/Core/WalletCashuRequestPaymentTest.kt`
- `codex/android-update-plan-implementation:android/app/src/test/java/org/cashu/wallet/ui/send/SendCashuRequestTopUpTest.kt`
- `codex/android-update-plan-implementation:android/app/src/test/java/org/cashu/wallet/ui/send/SendDestinationResolverTest.kt`

Target:

- `android/app/src/main/java/org/cashu/wallet/Core/CashuPaymentRequestMintSelector.kt`
- `android/app/src/main/java/org/cashu/wallet/Core/Wallet/WalletCashuRequestPayment.kt`
- `android/app/src/main/java/org/cashu/wallet/ui/send/SendCashuRequestTopUp.kt`
- `android/app/src/main/java/org/cashu/wallet/ui/send/SendDestinationResolver.kt`
- `android/app/src/main/java/org/cashu/wallet/Core/Wallet/WalletManager.kt`
- `android/app/src/main/java/org/cashu/wallet/ui/send/UnifiedSendScreen.kt`
- matching tests under `android/app/src/test/java/org/cashu/wallet`

Plan:

- Add `CashuPaymentRequestRoute`.
- Add route outcomes: pay with ecash, BOLT11 fallback, add requested mint, external top-up, unsupported unit, and missing amount.
- Add helper to create BOLT11/sat top-up quote for a target mint.
- Add `WalletManager.addMintAndPayCashuPaymentRequest`.
- Integrate route outcomes into current `UnifiedSendScreen` with minimal UI changes only where behavior requires a CTA.
- Do not port branch `UnifiedSendScreen` wholesale.

## 9. CDK Gateway Robustness

Source:

- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/Core/CDK/CdkWalletGateway.kt`
- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/Core/CDK/CdkWalletGatewayImpl.kt`
- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/Core/CDK/ReceiveFeeEstimator.kt`
- `codex/android-update-plan-implementation:android/app/src/test/java/org/cashu/wallet/Core/CDK/ReceiveFeeEstimatorTest.kt`

Target:

- `android/app/src/main/java/org/cashu/wallet/Core/CDK/CdkWalletGateway.kt`
- `android/app/src/main/java/org/cashu/wallet/Core/CDK/CdkWalletGatewayImpl.kt`
- `android/app/src/main/java/org/cashu/wallet/Core/CDK/ReceiveFeeEstimator.kt`
- `android/app/src/test/java/org/cashu/wallet/Core/CDK/ReceiveFeeEstimatorTest.kt`

Plan:

- Port no-crash token spendability handling for malformed or foreign tokens.
- Ensure token receive and fee calculation use the token's own unit, not always sats.
- Port receive fee fallback behavior if still absent.
- Review P2PK signing key changes carefully and do not regress current primary P2PK behavior.
- Add focused CDK tests.

## 10. Back Navigation, Accessibility, Performance Tests

Source:

- `codex/android-update-plan-implementation:android/app/src/main/java/org/cashu/wallet/ui/navigation/BackNavigationPolicy.kt`
- `codex/android-update-plan-implementation:android/app/src/test/java/org/cashu/wallet/ui/navigation/BackNavigationPolicyTest.kt`
- `codex/android-update-plan-implementation:android/app/src/androidTest/java/org/cashu/wallet/ui/navigation/BackNavigationComposeTest.kt`
- `codex/android-update-plan-implementation:android/app/src/androidTest/java/org/cashu/wallet/ui/components/AccessibilitySemanticsComposeTest.kt`
- `codex/android-update-plan-implementation:android/app/src/androidTest/java/org/cashu/wallet/ui/components/LargeFontPickerComposeTest.kt`
- `codex/android-update-plan-implementation:android/macrobenchmark`

Target:

- `android/app/src/main/java/org/cashu/wallet/ui/navigation/BackNavigationPolicy.kt`
- `android/app/src/test/java/org/cashu/wallet/ui/navigation/BackNavigationPolicyTest.kt`
- `android/app/src/androidTest/java/org/cashu/wallet/ui/navigation/BackNavigationComposeTest.kt`
- `android/app/src/androidTest/java/org/cashu/wallet/ui/components/AccessibilitySemanticsComposeTest.kt`
- `android/app/src/androidTest/java/org/cashu/wallet/ui/components/LargeFontPickerComposeTest.kt`
- `android/macrobenchmark`

Plan:

- Port pure back-navigation policy helpers only where they match current screens.
- Add JVM policy tests first.
- Port accessibility and large-font tests selectively, updating labels and selectors to current `main`.
- Add macrobenchmark module only after confirming dependencies and current package names.
- Regenerate or revalidate baseline profiles. Do not blindly copy branch profile files.

## Skip Or Rewrite

Skip:

- Branch-wide UI redesigns.
- Fake wallet app visual regression harnesses that assert old labels.
- Branch `CashuApp` and `WalletScaffold` replacement model.
- Branch `UnifiedSendScreen` full rewrite.
- Decorative or material policy tests that encode branch-specific aesthetic choices.
- Docs/planning churn such as `ANDROID_UPDATE_PLAN.md`, route inventories, parity trackers, and broad release claims.
- Deleted skill files or unrelated repo metadata changes.
- Any source change that downgrades current Gradle, Compose, or Material choices from `main`.

Rewrite instead:

- Any user-visible UI needed for app lock, staged restore, add-mint-to-pay, or top-up should be implemented in current `main` components, not copied from the source branch.
- Any Compose or instrumentation tests should be adapted to current screen text and semantics.
- Any performance profiles should be regenerated against the final target branch.

## Validation Order

Run Android commands from `android/`.

1. `./gradlew --no-daemon :app:testDebugUnitTest`
2. `./gradlew --no-daemon :app:androidNoNetworkIntegrationTest`
3. Start local mints, then `./gradlew --no-daemon :app:androidLocalMintIntegrationTest`
4. `./gradlew --no-daemon :app:lintDebug`
5. `./gradlew --no-daemon :app:assembleRelease`
6. Managed-device and instrumentation tests after Compose test selectors are updated.
