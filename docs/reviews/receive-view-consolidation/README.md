# Receive and History view consolidation

Receive and History now mount the same native receive screen for live incoming
Bitcoin addresses, Lightning invoices, and reusable BOLT12 invoices. The screen
retains status monitoring, copy/share, explorer links, creation date, and the
Details inspector. Reusable invoices retain amount/description editing and total
received. Reopening reads the existing quote rather than creating a request.
New-address and invoice-edit actions remain bound to the displayed mint and unit,
including when a different mint is selected on Home.

## Audit

| Screens | Outcome |
| --- | --- |
| On-chain Receive / unpaid address in History | One live receive screen on both platforms |
| Lightning Receive / unpaid invoice in History | Same consolidation |
| Reusable invoice Receive / request in History | Same consolidation, including edits and Details |
| Cashu request creation / History | Already share CashuRequestDetailView / CashuRequestDetailScreen; retained |
| Completed or failed transactions / live requests | Retained receipts: settled codes must not be offered as fresh payment requests |
| Funded on-chain address / unfunded address | Retained receipt after funding; a deposit already on its way must not invite a second payment |
| Send/receive success screens | Already share PaymentStatusView / PaymentStatusScreen; retained |
| Ecash claim review / sent-token receipt | Different actions and ownership; retained |
| Transaction / request technical inspectors | Already share their native inspector renderer; retained |

Missing local quotes fall back to the existing receipt. A one-shot quote that
settled between selecting its History row and reading it also falls back to its
receipt. Reusable invoices remain reusable after previous payments.

## Regression coverage

The routing tests cover incoming versus outgoing, pending versus terminal states,
legacy rows without an explicit quote ID, and funded versus unfunded addresses.
The Android on-chain History journey checks that an unfunded address opens the
Receive screen with Copy address and the technical Details inspector.

Manual device checks: generate each request type, copy/share it, open Details,
close and reopen from History, and verify the identical code and fields. Repeat
with another mint selected on Home; edit a reusable invoice and generate a new
address, verifying the original mint and unit are preserved. Confirm that settled
payments still open receipts and that a previously paid reusable offer does not
replay its success animation. Exercise large text and screen-reader actions.
