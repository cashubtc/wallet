# NFC payment requests: draft, blocked upstream

Tracks [wallet #427](https://github.com/cashubtc/wallet/issues/427). This branch
adds native compatibility/regression coverage and records the remaining work.
It does **not** change production payment routing or complete #427. Keep the PR
draft until both SDK capabilities below are available in a released binding.

## Required CDK capabilities

Both apps currently pin CDK 0.18.2. Its prepared payment-request interface exposes
amount/fee information, `cancel()`, and `confirm()`. Confirmation delivers through
the request's HTTP/Nostr transport and returns no token to the application.
Preparation rejects a request without such a transport. NFC needs the application
to write the confirmed token through its existing native reader session.

The [released Swift interface](https://github.com/cashubtc/cdk-swift/blob/0.18.2/Sources/Cdk/CashuDevKit.swift)
(`PreparedPaymentRequestProtocol`) establishes the missing return value. Native
tests on both platforms check the no-transport preparation boundary for fixed and
amountless requests with an explicit custom amount, then reopen the database and
redeem the full balance independently.

Upstream must expose an in-band prepared-request operation that:

- Preserves request validation, mint selection, conditions and fee semantics.
- Provides the final debit for approval before confirmation.
- Returns the confirmed token without also sending it over HTTP/Nostr.
- Supports cancellation and exposes its operation ID for delivery recovery.

Receiver-fee accounting also needs the work tracked by
[CDK #2730](https://github.com/cashubtc/cdk/pull/2730). At review time that PR was
open and draft at `2110f750163f8b969baf56834c7dda78e2457c05`. That fee fix alone does
not add the missing in-band token API. No additional upstream PR is part of this
wallet task.

## Coverage in this draft

`NfcPaymentRequestCompatibilityTests` (Swift) and
`NfcPaymentRequestCompatibilityTest` (Android) cover:

- The pinned SDK's explicit no-transport limitation for fixed/amountless requests,
  with no pending reservation and spendability after reopening.
- Exact recipient credit at the zero-fee fixture using the native generic-send
  path currently used for NFC. Android invokes the production gateway; Swift
  invokes the corresponding native send options. These are not NFC hardware tests
  or complete payment-service integration tests.
- The intended net-recipient assertion at the fee-charging fixture, opt-in via
  `PAYMENT_KNOWN_REGRESSIONS=1` (Swift) or
  `cashu.paymentKnownRegressions=true` (Android). It remains a known failing
  regression, not a passing assertion that accepts a shortfall.

The existing `CashuRequestReceiverFeeRegression` / `cashuRequestReceiverFeeRegression`
tests separately exercise the prepared HTTP request path for fixed and amountless
requests. Existing prepared-send cancellation and request delivery-recovery coverage
remains applicable, but NFC-specific adapter coverage is still required below.
Use the fixture startup and platform test commands in `CI/payment-tests/README.md`,
selecting the compatibility class names above. For Xcode, prefix environment
variables with `TEST_RUNNER_`.

## Work required before marking ready

- Upgrade both bindings to a release with the in-band API and corrected recipient
  fees; revalidate fixed and amountless requests on zero/nonzero-fee mints.
- Route both NFC services through the prepared request API, preserving all request
  restrictions. Use its total debit with the controls in wallet PR #468.
- Add native amount entry for amountless requests. Until then the NFC flow must
  continue rejecting them; the custom amount used in a test is not an app default.
- Connect confirmation to native NFC writing and existing recovery. Cover cancel
  before confirmation, write failure, timeout, uncertain acknowledgement,
  recipient claim, relaunch and foreground/lock transitions.
- Replace the SDK-limitation assertion with the successful in-band contract and
  promote the fee regression to required CI coverage. Exercise the real services,
  and verify physical NFC on both platforms.
- Keep generic ecash sends unchanged. Do not substitute an estimated fee or an
  HTTP receiver for the requested NFC delivery.

The two wallet PRs branch independently from main. The eventual request integration
must retain the contactless controls when the branches are combined.
