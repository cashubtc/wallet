# Recover a deposit

Both apps offer **Settings → Backup & Restore → Recover a deposit**. Use it for a
known mint quote whose signing key was lost before its paid funds were issued as
ecash. Normal seed restore continues to recover already-issued proofs through
NUT-09.

Restore the original wallet's recovery phrase first, if necessary. Deposit
recovery uses the current wallet's seed and never replaces it. Enter the mint
URL and exact quote ID. The form defaults to a Lightning invoice in sats; expand
Options to change the payment method, currency, or search depth. A Lightning invoice,
payment hash, or Bitcoin address alone cannot identify the mint quote.

The default search covers the first 1,000 seed-derived deposit keys. Choose the
10,000-deposit search if the first search cannot find a match. Keys generated
randomly cannot be recovered from the seed. Unknown quote IDs are not discovered
by this feature.

Confirmation comes before CDK imports the quote or searches for its key. A
NUT-09 scan of the selected mint and currency first restores existing ecash and
resynchronizes proof-output counters, so a fresh database cannot reuse outputs
from an earlier issuance. This happens before importing any reconcilable quote.
A matching key is saved by CDK, and its derivation counter advances past that key.
The app then uses its existing check → mint → verify reconciliation, with the
quote's own mint and currency, to claim available funds and refresh balance and
history. Repeating recovery does not claim the same funds again. A reusable
Lightning offer can still receive new payments later.

An unmatched newly imported quote is removed before it can enter automatic
issuance. Existing quote records, keys, counters, and incomplete issue sagas are
preserved. Mint/currency/method conflicts with an existing quote are rejected.
Approved deposits with recovered keys remain in the normal durable recovery
path: unpaid deposits wait for payment; uncertain issuance retries after the
existing backoff. An already-issued deposit directs the user to normal Restore.

Supported payment methods are Lightning invoices (BOLT11), reusable Lightning
offers (BOLT12), and on-chain deposits. The form supports SAT, MSAT, USD, and EUR.
No signing key or recovery phrase is displayed by the flow.

Regression coverage includes request validation, bounded searches, status
classification, wrong seeds, a key beyond the default search limit, preservation
of stored quote identity, and repeat recovery across Lightning and on-chain
deposits. Native tests use isolated databases and the local CDK fake mint.
