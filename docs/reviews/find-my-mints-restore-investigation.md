# Find My Mints restore investigation

## Scope and confidence

Investigated the report that mnemonic restore and Find My Mints failed until
Android app storage was cleared. The reported APK was approximately 1.0.1 or
1.0.2; the precise failure message and discovery result were unavailable.

Tests use the current source at base revision `48387a2f`, with CDK 0.18.3 on
both platforms. The original discovery service and Android staging logic, and
the iOS onboarding discovery logic, match tag `v1.0.2`. This is a reproducible
explanation for the storage-dependent symptoms, not a confirmed diagnosis of
the reporter's individual wallet. No funded wallets or public relays were used.

## Reproduction

1. Start an isolated test wallet and disable WebSocket connections in Privacy.
2. Restore a mnemonic without clearing app storage.
3. Tap Find my mints.

Both platforms retain this device privacy preference across wallet replacement.
The backup service rejects discovery before contacting a relay. A fresh settings
store enables WebSockets by default, explaining why clearing storage can change
the outcome with exactly the same mnemonic.

Android also maps the disabled-connection error to “Couldn't reach the mint.”
Its restore staging state marks the failed search as completed, producing
“No backup found” despite never searching. iOS onboarding has the same incorrect
completed-search flag, alongside its existing disabled-WebSockets error.

An explicitly empty saved relay list is another retained setting that prevents
discovery. Fresh storage restores the default relay list. Unreachable or different
custom relays can also affect discovery, but were not exercised against public
relay infrastructure in this investigation.

## Patch

- Preserve typed backup errors before generic error classification on both
  platforms. Explain where to enable WebSockets or configure relays.
- Set the completed-search flag only after a successful lookup. Clear previous
  lookup results and notices when retrying, so an earlier failure or empty result
  cannot be mistaken for the current outcome.
- Preserve the wallet database, mnemonic, relay list and privacy preference.
  Enabling WebSockets and retrying works without clearing wallet storage; manual
  mint entry remains available.

## Separate unfinished-restore behavior

Starting a restore and then entering a different mnemonic before completing it
is blocked on both platforms. This is intentional protection: the staged wallet
may already contain recovered funds, while the previous wallet's database is
still backed up. Retrying the same mnemonic is allowed. Tests exercise this with
the real native CDK repositories.

The UI permits returning to seed entry before that protection is released.
A follow-up should offer an explicit resume/finish-current-restore route when
this guard fires. Do not solve it by deleting the journal, automatically committing
the old wallet, or clearing app storage.

## Verification

Final results: 27 Android unit tests, two Android instrumented tests, and 27 iOS
tests passed, with no skips. `git diff --check` passed. Native tests ran on fresh,
unfunded Android and iOS test devices; both devices were shut down afterward.

- Before the patch, Android's new instrumented regression fails because a lookup
  rejected by the privacy setting claims a completed search. Afterward it verifies
  failure, a successful retry, removal of the stale error, and another failed retry
  without deleting the wallet.
- Android additionally replaces a seed through the real CDK gateway, verifies
  the retained privacy setting, and verifies the unfinished-restore guard.
- iOS tests exercise the actual backup-service precondition, retained settings
  versus a fresh store, actionable errors, same-seed ledger preservation and
  different-seed retry protection. Existing mint restore batch tests are included.
- Android unit tests cover relay normalization, error classification, restore
  identity and durable replacement.

The successful empty discovery retry uses a deterministic Android gateway. It
does not establish live NUT-27 relay availability or funded NUT-09 recovery.
For a conclusive match to the report, obtain the exact APK version and whether
the original attempt produced a settings/network error, no mints, or found mints
with zero recovered balance. A mnemonic is not needed for that follow-up.
