# Duplicate BOLT11 invoice history: reproduction and wallet fix

Verified 2026-09-19 against wallet main `7c4bbdae` and CDK `v0.18.0`.

## Customer screenshot update — diagnosis remains unconfirmed

The customer screenshot received after the initial implementation shows muted
**Lightning invoice** rows, all labelled **Now**, at repeated 99,000 / 98,000
amounts and 98,100. These are consistent with unpaid quote rows. The reproduced
mint-attempt bug below produces **Lightning received** transaction rows instead.
Therefore the original retry fix must not be represented as a confirmed fix for
this customer report. The patch addresses the independently reproduced retry issue; the customer
incident remains under investigation.

A further local test (`LiveMintRetryHistoryJourneyTest.unpaidInvoicesWithRepeatedAmountsRemainDistinctWithoutMultiplyingOnReload`)
used the real Android WalletManager, CDK, SQLite, production History UI, and an
isolated Nutshell FakeWallet with automatic payment disabled. It created nine
invoices using the screenshot's amounts. All nine had distinct quote IDs and
distinct invoice strings. Five history reloads preserved exactly nine unpaid
rows, zero mint transactions and a zero balance. The test passed. The captured
[local screen](unpaid-invoices-local-reproduction.png) has the same repeated
labels and amounts, despite containing separate invoices. It does not reproduce
or disprove an actual duplicated invoice in the customer's wallet.

The live iOS retry regression additionally used LightningService to create a real
invoice, reject five mint HTTP requests, then mint once successfully. With the
projection fix temporarily disabled, all accounting assertions passed (five
rejections, one successful issuance, six records, balance 64) and the history
assertions failed (six displayed records). The fix has been restored after this
baseline experiment. This live iOS test has not yet been rerun with the fix;
the separate native database regressions passed with the fix as listed below.

The reporter subsequently confirmed that the repeated rows are the same quotes.
Treat quote equality as supplied evidence; the distinct-invoice experiment above
does not explain this incident. Platform/build is still needed to identify the
app path that produced those rows. Current unpaid quote loading reads the quote
table once per refresh; Android additionally deduplicates by row ID. No matching
production-path reproduction has yet been established. Do not merge distinct
unpaid invoices by amount or publish customer invoices as test fixtures.

## Finding

**Confirmed reproducible bug:** five rejected mint attempts followed by one
successful attempt for one BOLT11 quote produce six distinct stored CDK
transactions with the same invoice and amount. The unfixed iOS and Android
history loaders display all six as separate receipts. The wallet-only fix
shows the completed receipt while retaining all six records in CDK.

This confirms the mechanism, not the original user's incident. The report
has confirmed matching quotes but has not supplied platform/build or detail statuses.
That confirmation alone does not establish that retry transactions caused the rows.
Multiple completed records are a different case and remain visible.

## Runtime reproduction

`CI/reproduce-mint-history.py` extracts CDK `v0.18.0` into a temporary directory
and inserts `CI/mint-history-retry-test.rs` into its existing mint saga tests.
Only test code is added; neither CDK production code nor the wallet's CDK
version or bindings change. The supplied CDK repository remains untouched.

The test uses CDK's real wallet, SQLite test database, mint operations and
transaction persistence, with its existing mock mint connector:

1. Store one paid BOLT11 quote for 64 sats.
2. Reject five mint requests with `MintingDisabled`.
3. Verify five failed transactions and a zero balance.
4. Permit a sixth attempt with valid mock blind signatures.
5. Verify six distinct transaction IDs sharing one quote and invoice, exactly
   one completed transaction, and a balance of exactly 64 sats.

The checked-in harness was executed successfully:

```text
CONFIRMED: one BOLT11 quote, six distinct stored rows (five failed, one completed), balance 64
1 passed; 0 failed
```

Run with a local CDK repository containing the release tag:

```sh
python3 CI/reproduce-mint-history.py /path/to/cdk-repository
```

Rust, CDK build prerequisites, and dependency access/cache are required.
`CARGO_TARGET_DIR` and `CARGO_NET_OFFLINE` can reuse an existing build cache.
No real Lightning payment or user wallet is used.

## Why this happens

`MintSaga::new` creates a new operation UUID. `execute` saves a pending
transaction before sending the mint request. A definitive rejection marks
that transaction failed and releases the quote. A fresh retry gets a new
operation UUID and therefore a different transaction ID, even for the same
quote/invoice. These are legitimate attempt records, not multiple successful
issuances in the reproduced case.

The app previously treated each stored transaction as a separate receipt.
Its quote-ID check suppresses only the synthetic pending quote row. Android's
transaction-ID deduplication likewise does not merge distinct attempts.

## Wallet-only fix

Both platforms retain CDK's explicit payment method in the wallet transaction
model and project incoming BOLT11 mint attempts to one receipt per exact stored
mint URL, currency unit, and quote ID. Amount is never used as payment identity.

- Select the single completed attempt when available.
- Otherwise select the newest pending attempt, or the newest attempt if none
  are pending. Equal timestamps use transaction ID as a deterministic tie-break.
- Keep the selected transaction's original ID, amount, fee, and other fields.
  Existing quote-based detail lookup resolves an old hidden attempt to this receipt.
- Preserve separate quotes, accounts, outgoing payments, BOLT12 and on-chain
  payments, and records lacking explicit method or operation identity.
- Preserve groups with conflicting invoices, amounts, or multiple completed
  records rather than concealing a possible accounting problem.

The fix does not remove stored transactions, add proofs, change balances,
modify quote counters, or alter mint/recovery calls. Existing duplicate attempt
rows are projected correctly on history reload; no database migration is needed.
Older Android caches without payment-method metadata still decode and acquire
that metadata when refreshed from CDK.

## Before-and-after verification

The new native database regressions both failed before the production fix:
expected one completed receipt ID, received six transaction IDs. The iOS
regression also exposed an old failed attempt remaining selected in detail.
The same regressions pass after the fix.

| Check | Result |
| --- | --- |
| CDK runtime retry reproduction, including single balance credit | Passed |
| iOS StoredWalletAccountTests, TransactionServiceTests, HistorySearchTests | 40 passed |
| Android MintReceiptProjectionTest, TransactionDetailLookupTest, PendingMintQuoteTransactionsTest | 17 passed |
| Android StoredAccountProjectionInstrumentedTest on emulator | 5 passed |
| Android lint | Passed: 0 errors, 34 warnings, 6 hints |
| Diff whitespace check | Passed |

Tests cover successful/active/failed attempt selection, stable tie-breaking,
unchanged amounts, detail lookup, idempotence, separate accounts and quotes,
reusable payments, conflicting settlements, missing metadata, and Android
cache compatibility. Database tests retain all six CDK records after reading
history. Android repeats the check after closing and reopening the repository;
iOS repeats it with a fresh transaction service. Both verify that the history
loader does not create a balance from inserted history records.

Selected iOS suites run with `xcodebuild test`. Android verification runs
`:app:testDebugUnitTest`, `:app:connectedDebugAndroidTest` for the classes above,
and `:app:lintDebug`. Coverage percentage was not collected; the counts describe
these selected tests, not the entire application test suite.

## Related work and version distinction

[#372](https://github.com/cashubtc/wallet/pull/372) addresses missing on-chain
history for equivalent mint URL spellings;
[#373](https://github.com/cashubtc/wallet/pull/373) addresses pending on-chain
payments in Recent. Both were open when checked during the initial investigation.
Neither explains or fixes the retry-to-receipt mapping reproduced here.

The original checkout `7f7c4fd6` also discarded interrupted mint operations
before starting fresh attempts. Current main already preserves those operations
for recovery. This checkout was fast-forwarded to fetched main before applying
the fix; no older recovery behavior was reintroduced.

To tie this result to the user's report, compare two rows' quote IDs, invoice
equality, transaction IDs, and statuses, plus platform/build. If multiple rows
are completed or the balance was credited repeatedly, investigate that separately;
this patch deliberately does not merge conflicting completed records.
