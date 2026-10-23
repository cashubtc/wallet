# Run document template

Copy this structure into the user's dedicated document or a local Markdown
report. Fill results as each step completes. Keep raw evidence in a private
directory; document links need not expose that directory publicly.

## Current status

- Run ID and dates/timezone:
- Requested revision; built wallet SHA; dirty/harness changes:
- Live upstream SHA checked at:
- Resolved iOS/Android binding versions; build evidence:
- Devices and driver ownership: private manifest reference
- Seed backups verified before funding: yes/no for each wallet; private reference
- Funding authorization, original amount/unit, mint, budget:
- Completed / failed / pending / not run:
- Current spendable balances by platform, mint, unit:
- Legitimate in-flight value, counted once:
- Cumulative actual fees:
- Reconciliation equation and unexplained difference:
- Unresolved findings and next safe action:

## Mint capabilities

| Mint | Version | Methods and units | Min/max | Active keyset fees | Evidence/time |
| --- | --- | --- | --- | --- | --- |

## Planned scenarios

| ID | Platform/mint route | Preconditions/amount/budget | Expected outcome | Status |
| --- | --- | --- | --- | --- |

## Step results

| Step ID/time | Action | Expected | Observed UI | Durable/mint evidence | Result |
| --- | --- | --- | --- | --- | --- |

Record harness errors and invalid setup attempts too. A timeout leaves an
unknown/pending outcome until inspected. Keep one row per action/verification;
do not replace failed attempts with only the eventual success.

## Transaction ledger

| Tx ID | Sender/recipient mints | Before balances | Requested / encoded / net credited | Preview fees + max debit | Actual debit / fee / change | Both terminal states | After balances | Evidence |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |

IDs may be aliases in shared reports; retain exact quote/transaction IDs in the
private evidence manifest. Keep cancellation quotes separate from executed ones.

## Findings

For each finding: ID, impact, platform, built version, scenario/steps, expected
versus actual, persistence after restart, funds location, evidence, suspected
layer and confidence, and what would verify the cause. For an eventual recovery,
record time to credit and mechanism without deleting the original observation.
Follow `AGENTS.md`/`SECURITY.md` for private handling of vulnerabilities.

## Handoff and limits

Final per-mint balances/fees; unresolved proofs/quotes/sagas; private backup and
evidence references; native driver source, exact commands and artifacts; runner
shutdown state; preserved devices; untested functions and prerequisites. Distinguish
source review, local-fixture tests, public-mint transactions, and released behavior.
