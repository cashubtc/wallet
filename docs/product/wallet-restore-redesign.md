# Wallet restore redesign

## Problem and contract

Seed submission currently commits a clean database before mint recovery begins.
Restoring the current seed therefore removes quotes, quote signing keys, derivation
counters, transaction history and operation journals that proof recovery cannot
reconstruct. Recovery must preserve that evidence.

Same-seed restore scans the existing wallet. Different-seed restore stages an
isolated database and retains the previous database and encrypted state until the
user finishes restore. Closing the screen does not roll back a staged wallet:
it may already contain newly claimed funds. Relaunch resumes it. An unfinished
replacement must be completed before another different seed can be installed.
Explicit wallet deletion removes both the active wallet and retained backup.

## iOS implementation plan

1. Classify seed identity under the recovery operation lease, using BIP39 entropy.
2. Reuse the current database/repository for the same seed; preserve settings,
   onboarding state, quote keys, counters and interrupted-operation journals.
3. Add a durable `restoring` replacement phase. Keep the existing checkpoint,
   encrypted snapshot and installation rollback, but defer commit/backup cleanup
   until `completeRestore`. Startup leaves staged wallets and backups intact.
4. Reject a second replacement before stopping services. Make completion errors
   visible and keep the restore screen open. Explicit deletion cleans staged state.
5. Cover identity selection, staged relaunch, completion, missing backups and
   failure/retry with regression tests.

## Android implementation plan

1. Classify seed identity under the initialization mutex using BIP39 entropy.
2. Reuse the open repository, or reopen the existing database, for matching seeds.
   Do not clear preferences, quote records, counters or cached history.
3. Mirror the durable `restoring` phase and deferred completion on iOS. Keep
   cancellation-safe installation rollback; resume staged wallets after relaunch.
4. Serialize completion/deletion with initialization. Do not dismiss Settings
   restore after a failed completion. Block replacement of an unfinished restore.
5. Run journal/identity regression tests, Android unit tests and build/lint checks.

## Validation and limits

Test with synthetic seeds and local fixtures only. Verify that the same-seed path
does not call database replacement, and that a staged different-seed database and
its predecessor both survive relaunch and failed completion. Retain compatibility
with existing journal phases. Run iOS regression tests and Android build/tests.

This change does not recover quote records already deleted, discover unknown
quotes from a seed, or implement a quote-ID/NUT-20 counter scanning tool. Partial
mint recovery may be explicitly accepted by finishing restore. Existing CDK
proof-scan behavior remains; interrupted operations must retain their journals.

## Implementation verification

- iOS: 10 focused tests passed with no skips. The app-entry regression stores a
  paid quote and signing key in the native database, advances the NUT-20 counter,
  submits the same seed again and verifies the repository, key and counter survive.
  Journal tests cover staged relaunch, explicit completion, failed commit/retry,
  missing backups and the existing installation rollback behavior.
- Android: 781 unit tests passed and 6 were skipped; debug build and lint passed.
  The same-seed emulator journey passed, preserving history, tracked mints,
  balance and the open repository. Staged-journal tests cover relaunch,
  failed completion/retry, missing backups and first installation without old files.
- Test fixtures use synthetic keys/seeds and isolated test state. No real mint
  payments were required for this validation.
