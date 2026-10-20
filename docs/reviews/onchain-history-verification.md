# Fulfilled on-chain payment history verification

Verified 2026-09-18 using ECC's verification workflow.

## Finding and scope

Android's original mint URL matching can omit **completed** on-chain payments
from history when the database stores `https://offline.example:443` and the
tracked mint uses `https://offline.example/`. Account discovery finds the
transactions, but the history loader rejects their account before reading its
transactions. Payment status does not protect a row from this filter.

The fix treats default ports as equivalent and uses consistent URL matching
for pending quotes and retained quote rows. Original database URL keys remain
unchanged. iOS already normalizes these URL forms.

This establishes a reproducible cause of a fulfilled payment missing from
history. It does **not** establish the cause of the original user report: its
platform, version, and stored/tracked mint URLs have not been supplied.

## Before and after

The new Android regression writes one incoming and one outgoing completed
on-chain transaction to a real CDK SQLite database. It verifies both records
are still present and completed, and that there are no pending mint quotes.
It then loads history with an empty cache, checks the All and Completed
filters, and repeats after closing and reopening the database.

The test was run in an isolated source copy with the original
`CdkWalletGateway.kt` restored from base commit `7c4bbdae`. It compiled and executed, then
failed at the history assertion: two stored transaction IDs were expected,
but history returned an empty list. The control using the exact stored URL
passed before that assertion.

The identical regression passes with the default-port fix.

## Test evidence

| Guarantee | Test | Result |
| --- | --- | --- |
| Completed sent and received payments survive equivalent mint URLs, empty caches, filtering, and database reopening | `StoredAccountProjectionInstrumentedTest.fulfilledOnchainPaymentsRemainInHistoryWithEquivalentMintUrls` | Fails before fix; passes after fix |
| Real CDK database records appear in the native History screen and Completed filter; receipts retain their details after database reopening and activity recreation | `OnchainHistoryJourneyTest.fulfilledPaymentsFromRealDatabaseAppearInCompletedHistoryAndAfterReopen` | Passed |
| Equivalent completed-payment case survives database reopening on iOS | `StoredWalletAccountTests.testFulfilledOnchainPaymentsRemainInHistoryWithEquivalentMintURLs` | Passed without iOS production changes |

Android verification command (from `android`):

```sh
./gradlew --offline --no-daemon :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.cashu.me.Core.StoredAccountProjectionInstrumentedTest,com.cashu.me.ui.journeys.OnchainHistoryJourneyTest \
  :app:lintDebug
```

Android: 9 device tests passed, zero failures or skips. Build and Kotlin type
checking passed. Lint completed with zero errors, 34 warnings, and 6 hints.

iOS: `xcodebuild test` passed all three selected on-chain tests in
`StoredWalletAccountTests`: the fulfilled-payment regression, transaction
direction/state compatibility, and the paid-quote-to-completed-receipt
transition. Build and Swift type checking passed.

`git diff --check` passed. Changed tests use synthetic data, reserved example
domains, and isolated test wallets. Diff review found no secrets or unrelated
production edits introduced by this follow-up.

Coverage percentage was not collected; these results describe the tested
scenarios, not whole-application coverage. The tests insert completed records
and exercise production storage/loading/UI; they do not send live Bitcoin or
prove that an older app successfully persisted the original reported payment.
