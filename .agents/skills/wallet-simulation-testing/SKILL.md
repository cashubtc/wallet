---
name: wallet-simulation-testing
description: Run and document native iOS Simulator and Android emulator wallet journeys with real CDK bindings, user-provided ecash funding, multiple mints, and transaction-by-transaction fee reconciliation. Use for operator-led wallet interoperability and recovery testing; ordinary CI fixture tests alone do not establish public-mint coverage.
---

# Wallet simulation testing

Produce a reproducible run with two isolated native wallets, a scenario matrix
written before spending, a result for every step, and accounting that explains
every sat. A simulator wallet connected to a public mint moves real funds.
Use only the user's authorized funding, destinations, and budget; this skill
does not authorize additional spending or publication of wallet secrets.

## Establish the run

1. Read repository `AGENTS.md`. Identify whether the user wants current upstream
   `main`, a PR, or a pinned revision. Record local HEAD, dirty changes, upstream
   repository/ref/SHA, and the exact revision actually built. Use `gh api
   repos/cashubtc/wallet/commits/main --jq .sha` for a live upstream check;
   a cached `upstream/main` is insufficient. Report a mismatch before funding.
   Never switch branches or replace binaries underneath another active run.
2. Record resolved CDK Swift and Android versions from the Xcode SwiftPM lockfile
   and `android/gradle/libs.versions.toml`, plus the resolved build dependencies.
   Distinguish wallet source, binding release, and mint version. A different
   local CDK checkout is supporting evidence, not the shipped implementation.
3. Create a private evidence directory outside Git, with directory mode 700,
   files mode 600, and `umask 077`. Create a dedicated results document using
   [references/results-template.md](references/results-template.md). Keep raw
   logs, screenshots, databases, tokens, and recovery phrases private. Public
   reports/PRs must contain no seeds, proof secrets, bearer tokens, machine
   identifiers, personal paths, or uncoordinated vulnerability details.
4. Create a fresh, explicitly identified iOS device and Android AVD for this run.
   Record their IDs privately and target every command explicitly. Do not reuse,
   erase, uninstall, or clear a funded wallet from a concurrent or earlier run.
5. Onboard two distinct wallets. Save each recovery phrase in a durable private
   backup **before receiving funding**. Verify word order and BIP39 checksum
   locally, without transmitting the phrase. Record backup verification in the
   report, not the phrase. Confirm both wallets have zero spendable balance.

Read [references/native-runners.md](references/native-runners.md) to build and
drive the real apps using XCTest and Android UI Automator. Use an available
authorized UI driver if reliable; native runners are an alternative, not a
reason to bypass tool or execution permissions. Do not run the existing resetting
fixture suites against funded wallets.

## Plan mint coverage before transactions

Read [references/scenarios.md](references/scenarios.md) and write the selected
scenarios, amounts, expected results, and prerequisites into the results document.
For the three-mint example, discover and verify these endpoints:

| Mint | URL |
| --- | --- |
| Minibits | `https://mint.minibits.cash/Bitcoin` |
| Antifiat | `https://antifiat.cash` |
| Sovran | `https://mint.sovran.money` |

Use the funding token's actual mint URL, including path and case; do not guess a
similarly spelled endpoint. Read each mint's `/v1/info`, `/v1/keysets`, and active
keys before choosing amounts. Record current implementation/version, unit,
supported methods, minimum/maximum amounts, and per-keyset `input_fee_ppk`.
Capabilities and fees change; examples here are not live configuration.

Add all selected mints through each app and verify persisted identity, default
selection, and separate balances. A 144-sat funding example may support a small
Minibits → Antifiat route, then a larger Minibits → Sovran route. Recompute the
budget before every quote; a mint's minimum plus reserve and input fees may make
the reverse route impossible. Record unsupported or unaffordable coverage as
blocked/not run instead of silently changing the funding limit.

## Execute one financial operation at a time

For each numbered step, record action, expected outcome, observed UI, durable
state, evidence reference, and pass/fail/pending/blocked status. A successful tap
or runner exit is not a financial result.

1. Capture both wallets' balances, relevant proofs, quotes, transactions, and
   incomplete sagas before the action. Use the actual schema/version. The
   read-only `scripts/audit_snapshot.py` can summarize **already consistent
   SQLite snapshots** without exporting secret columns.
2. Obtain a token or invoice only through the wallet flow under test. Transfer
   it privately between the two devices; avoid command-line literals, shell
   history, logs, and public screenshots containing bearer data. If a source
   document escaped token characters, save the original, normalize only known
   transport escaping, and decode offline to verify mint, unit, and amount.
3. Record the confirmation screen's amount, mint, input/method/network fees,
   reserve/maximum debit, and total. Include cancellation before a paid test:
   verify no debit and no stranded reservation. An unpaid quote can legitimately
   remain after cancellation; inspect funds rather than requiring quote deletion.
4. Confirm once. Save quote and transaction identifiers privately. If a command
   times out, inspect current UI and durable/mint state before taking another
   action. Never automatically replay Pay, Receive, mint, melt, or swap.
5. Verify the sender's terminal result **and** the recipient's issued/spendable
   credit. Correlate the same invoice/token across both ends. For Lightning,
   check melt PAID and mint ISSUED; where a preimage is available, independently
   verify SHA-256(preimage) equals the invoice payment hash. For ecash, verify
   token value, receiver credit, and sender claim/completed state; read-only
   proof-state checks are additional evidence, not another redemption attempt.
6. Reconcile fees from proof value and balance changes, not only displayed text:

   - One swap's input fee is `ceil(sum(input_fee_ppk of consumed proofs) / 1000)`.
     Group by the actual keysets and operation; do not round each proof or add
     per-operation fees twice.
   - A fee reserve is a maximum commitment, not automatically the actual fee.
     Account for input fees, actual routing/method fees, and returned change.
   - Record sender debit, encoded token/invoice value, recipient net credit, and
     each fee separately. A receiver may pay a redemption fee; request semantics
     may require the sender to cover it. Check the requested net amount explicitly.
   - Across transfers within the two-wallet run: initial funding = total current
     spendable value + legitimate in-flight value counted once + cumulative
     actual fees. Track units independently. Known spent duplicate inputs and
     orphan pending records are not funds.
7. Record discrepancies before proceeding. If an outgoing payment is paid but
   the receiver is not credited, pause new spending until value is located.

## Recover and distinguish failure layers

- On HTTP 429, stop extra manual probes, honor `Retry-After` when present, and
  allow bounded backoff. Record visibility of refresh errors and credit latency.
  Inspect the existing quote after a quiet window; do not pay again. If normal
  app recovery later issues it, update the outcome while retaining the delay.
- Preserve databases and seeds before restarting a runner/app. Relaunch with no
  reset/reseed flags; verify balance and history persistence. ADB offline, an
  empty accessibility tree, an OS paste prompt, or XCTest animation stalls are
  harness/environment observations until wallet evidence proves otherwise.
- Duplicate-token tests deliberately submit a known-spent token once. Verify
  rejection, unchanged spendable funds, history, pending proofs, and saga cleanup
  after a preserving relaunch. Retained pending state is a finding even if hidden
  by the UI; record it separately from actual value at risk.
- Use public-mint faults opportunistically. Deliberate interrupted-response,
  offline, and repeatable failure injection belongs on isolated local fixtures;
  see `CI/payment-tests/README.md`, `CI/receipt-recovery-proxy.py`, and
  `CI/run-native-interop.py`. Label those results as local fixture coverage.

## Finish with a usable handoff

Update the document after each transaction and at meaningful findings. Keep its
current summary and ledger consistent with the detailed chronology. Classify
findings as app UI, app lifecycle/storage, binding/CDK candidate, upstream mint,
or harness/environment; use evidence and avoid claiming a root cause from an
unmatched source version.

Report completed/failed/pending/not-run scenarios, final per-mint balances,
actual fees, unresolved quotes/sagas, and safe next steps. Preserve remaining
funds and durable private evidence, including the seeds and exact native driver
source/commands. Stop only owned test runners/listeners. Remove only temporary
harness source changes after saving them privately; do not erase funded devices.
Do not claim full coverage when camera/NFC hardware, relay-dependent functions,
on-chain minimums, or other prerequisites were unavailable.
