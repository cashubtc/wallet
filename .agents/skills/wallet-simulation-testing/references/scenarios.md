# Scenario selection

Write the matrix before funding. Assign scenario IDs and step IDs; repeat the
same behavior on both platforms where applicable. Match the scope to the user,
the mint capabilities, and the remaining budget. Do not mark a family passed
from one happy path.

| Scenario | Required observations |
| --- | --- |
| Setup and backup | Distinct valid seed backups before receipt; zero baseline; app/binding revisions and owned devices recorded. |
| Mint management | Add all mints on both platforms; identity/path/case, metadata, default selection, duplicate URL handling, independent balances. Remove only an unfunded mint when testing removal. |
| External funding | Offline-decode amount/unit/mint; receive once; fee preview, spendable proofs, completed incoming record; original token becomes spent. |
| Duplicate receipt | Same spent token rejected; spendable balance unchanged; inspect retained pending proofs, transactions, sagas and preserving-relaunch behavior. |
| Same-mint ecash | iOS → Android and Android → iOS; exact token value, sender debit, receiver net amount, both histories and sender claim status. Repeat on each affordable mint. |
| Fee-bearing ecash | Nonzero per-keyset input fees; rounding with several small proofs; distinguish send-side swap fee and recipient redemption fee; send-max and insufficient balance. |
| Cross-mint Lightning | Receiver creates invoice on B, sender melts on A; amount, reserve, actual fee, change; sender PAID and receiver ISSUED. Reverse direction/mint roles where affordable. |
| Cancellation and invalid input | Cancel a prepared payment; change amount/mint; malformed or wrong-unit token/invoice; insufficient funds; no unexpected debit/reservation. |
| History/details | Completed, pending and failed states; amount, fee, mint and direction agree with durable state on both platforms. |
| Persistence/recovery | Preserving app restart; runner restart; delayed receiver refresh; settle once, retain exact value, no duplicate history/credit. |
| Cashu Request / NUT-18 | Recipient request → sender confirmation → delivery/claim; net requested credit, disclosed method/input fees, cancellation releases reservation. Record transport prerequisites. |
| P2PK | Correct recipient can claim; wrong key rejected; replay rejected; cancellation/send-max if in scope. Use fresh separate test keys. |
| Restore | Restore a backed-up seed only in an additional isolated wallet/device; verify supported proof/quote recovery and avoid racing the original wallet for the same proofs. Seed backup alone does not prove restoration. |
| Other advertised functions | BOLT12, on-chain, Lightning Address, Nostr/NWC, NFC, camera QR, app lock/export: enumerate requested functions and prerequisites, then execute or mark not run with reason. |

For a small public-mint budget, sequence operations to preserve minimum balances:
fund one wallet, split enough value to the other, test a small cross-mint invoice,
then route a larger amount to a mint with a higher minimum. Reserve enough for
the intended return route and input fees before doing small token transfers.
Use quote results to decide amounts, not historical minimums in another report.

Fault injection, exhaustive amount boundaries, and repeated retry storms are
better covered by the local native interoperability/payment suites. Running
those suites supplements rather than replaces the public-mint matrix.
