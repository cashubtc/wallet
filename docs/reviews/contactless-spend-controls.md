# Contactless spending controls

iOS and Android provide native Contactless settings under Payments. Defaults are
1,000 sats per tap and 10,000 sats per UTC day. The per-tap threshold triggers
device authentication; the daily limit stops further contactless payments.
Setting the per-tap threshold to zero authenticates every payment. Saving changes
requires device authentication too.

The displayed amount, unit, mint, memo and total use the selected existing mint.
Limits include the prepared sender fee. Daily usage is shared across wallets on
the device, persists across launches and is retained when settings change.
Interrupted authorized attempts count conservatively toward the daily limit.

This change addresses a contactless security issue. Security details are omitted
in accordance with SECURITY.md.

## Validation

- Both platform policy suites cover default/boundary values, authentication,
  cumulative usage, persistence, day rollover, concurrency and cancellation.
- Native local-mint tests cover cancelled preparation, successful delivery and
  recovery after a failed delivery, including database reopen and redemption by
  an independent wallet.
- These tests are required by the payment coverage manifest.
- Real NFC hardware, native biometric/passcode interaction during a tap, and
  physical background/lock transitions still need device validation. Simulator
  and emulator tests cannot establish those behaviors.

Recovery depends on the mint being reachable. If recovery cannot finish, existing
History recovery remains available; an unresolved payment must not be reported as
refunded. This change does not resolve the separate request-fee work in #427.
