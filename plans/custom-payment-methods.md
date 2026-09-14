# Generic custom payment methods — assessment and implementation plan

Status: implemented in both native apps, retaining CDK 0.18.0. The baseline
assessment below was made at wallet commit `50431e31` on 2026-09-13.

Unknown custom units are whole integers (for example, `100 bux`); fractional
entry is rejected. Existing known-currency precision is unchanged. Mint-provided
unit metadata remains outside this change until the proposed specification lands.

## Recommendation

Support advertised custom methods through the existing native quote, issuance,
settlement, and recovery paths. Extend method identity and capability metadata,
add an explicit custom Send route, and reuse the current Receive and payment
detail components. Keep CDK responsible for keys, proofs, transactions, and sagas.

This is a bounded cross-cutting change, not just a picker change. The essential
work includes unit-correct withdrawal recovery and partially paid deposit
recovery. Removing those pieces would reduce the diff but leave incomplete
payment support.

## Reference behavior

[Pecan's wallet contract](https://github.com/zeugmaster/pecan/blob/ee78b20501a2e69a3738badda646058702876a70/docs/wallet-integration.md)
uses the method `branch`, with a mint-selected unit. Deposits require a NUT-20
public key and signed issuance. The teller identifies deposits and withdrawals
by the quote ID: bare-ID QR, full copyable text, and an emphasized six-character
suffix. Withdrawals submit an explicit amount and a free-form request/memo,
reserve proofs before payout, and can remain pending until the teller settles
or voids them.

[Web-wallet PR #596](https://github.com/cashubtc/cashu.me/pull/596), reviewed at
head `03e49fc679c8c77eb732a6409bb5711abcbf88d2`, is open. It provides a useful
behavioral reference: advertised methods and names, generic quote endpoints,
limits, paid/issued accounting, history, recovery, and quote-ID presentation.
Its new store and WebSocket machinery should not be transplanted: the native
apps already have much of the corresponding infrastructure.

## Baseline behavior and gaps

| Area | Findings in this wallet |
| --- | --- |
| Method identity | Both `PaymentMethodKind` models contain only BOLT11, BOLT12, and on-chain. iOS discards other CDK methods; Android discards them from capabilities and maps unknown stored quote methods to BOLT11. |
| Capability discovery | Both reduce NUT-04/05 entries to method lists, losing method/unit pairs, custom labels, and limits. Melt discovery filters to sat. iOS also enumerates `allCases` in discovery, mint details, and Receive. |
| Empty advertisements | iOS `MintService.makeMintInfo` keeps previous/default methods when the live list becomes empty. Android preserves an empty live list in metadata, but its Receive screen replaces it with BOLT11. iOS Receive also supplies this fallback. A custom-only mint can therefore expose an unusable Lightning option. |
| Receive execution | Existing mint quote creation already accepts a method and unit. Stored quotes carry their unit, and check/issue resolves that wallet. CDK's custom path generates and persists a NUT-20 signing key. The app model and UI prevent access to it. |
| Send execution | Unified Send recognizes known invoice/address formats. An arbitrary memo cannot identify a method. Quote creation, melt preparation, and Android melt status checks currently resolve sat wallets. App melt quote models omit the unit. |
| Recovery | Existing reconciliation issues paid-minus-issued deltas. However, focused monitoring and scheduling treat any fully issued payment as completion for methods other than BOLT12. CDK's unissued SQL query includes only `amount_issued = 0 OR payment_method = 'bolt12'`, so a partially issued custom quote disappears from subsequent discovery. |
| Withdrawal recovery parity | iOS pending-saga recovery already iterates tracked wallets. Android's sweep iterates mint URLs and its gateway recovers only the sat wallet. |
| History and detail | Transaction projections drop the original custom method or classify it as Lightning. Pending quote selection, reopening, request labels, and receipts assume known methods. A generic quote must remain identifiable without parsing its request as an invoice. |
| Currency support | Both registries already format unknown units as whole units and provide native-unit amount entry. Existing multi-unit balances and ecash support are reusable. This portion of the web PR does not need porting. |

Key local entry points:

- Identity and capabilities: `Models/Payments/PaymentMethodKind.*`,
  `Models/Mints/MintInfo.*`, iOS `Core/Services/MintService.swift`, and Android
  `Core/CDK/CdkMintMethodMapping.kt` / `CdkWalletGatewayImpl.kt`.
- Execution: iOS `Core/Services/LightningService.swift` and
  `Core/Wallet/WalletManager+Lightning.swift`; Android `Core/CDK/` and
  `Core/Wallet/WalletManager.kt`.
- Recovery: `FocusedMintQuoteMonitor.*`, quote schedule policies,
  iOS `WalletManager+MintQuoteSync.swift`, Android `WalletMintQuoteSyncService.kt`,
  and pending-melt recovery.
- UI: iOS `ReceiveLightningView.swift` and the active `UnifiedSendView` inside
  `SendView.swift`; Android `ReceiveLightningScreen.kt`, `UnifiedSendScreen.kt`,
  and `SendPaymentDetails.kt`; both transaction projections/detail screens.

## CDK boundary: retain 0.18.0 initially

The [released Swift wrapper](https://github.com/cashubtc/cdk-swift/blob/3a5664d7f0732f0f92d7326969b9da55c08f13a3/Sources/Cdk/CashuDevKit.swift)
and [CDK FFI](https://github.com/cashubtc/cdk/blob/v0.18.0/crates/cdk-ffi/src/wallet.rs)
expose generic `mintQuote` / `meltQuote`, custom method identity, method names,
limits, stored quote units, and enumeration of all stored mint quotes.

- [Custom minting](https://github.com/cashubtc/cdk/blob/v0.18.0/crates/cdk/src/wallet/issue/mod.rs)
  already supplies a NUT-20 public key and stores the signing key. Preserve this
  path rather than generating keys in application code.
- [Custom melting](https://github.com/cashubtc/cdk/blob/v0.18.0/crates/cdk/src/wallet/melt/custom.rs)
  does not expose a dedicated amount argument. It accepts JSON `extra`, which
  the [request serializer](https://github.com/cashubtc/cdk/blob/v0.18.0/crates/cashu/src/nuts/nut05.rs)
  flattens into the request body. Supply a serialized integer `amount` there;
  verify the wire body before relying on this adapter.
- [Subscription bindings](https://github.com/cashubtc/cdk/blob/v0.18.0/crates/cdk-ffi/src/types/subscription.rs)
  lack a custom subscription kind and custom notification payload. Other
  payloads become an empty proof-state notification; conversion of a custom
  subscription kind back into the FFI enum panics. Use the existing application
  polling path for custom methods and skip custom FFI subscriptions explicitly.
- The [unissued-query limitation](https://github.com/cashubtc/cdk/blob/v0.18.0/crates/cdk-sql-common/src/wallet/mod.rs)
  can be handled by selecting unfinished custom quotes from `getMintQuotes()`
  alongside the existing built-in candidates. No database schema change is
  needed for this selection.

These were source-level findings. A real custom-backend round trip was a
required implementation check, especially for the flattened melt amount and
recovery after restarting with a partially issued quote.

## Implementation sequence

### 1. Open method identity and retain advertised capabilities

- Make `PaymentMethodKind` an immutable string-backed value on each platform,
  retaining existing built-in constants and string serialization. This keeps
  current parameters and comparisons usable without parallel custom-method
  fields throughout the app. Decode existing saved strings unchanged.
- Validate advertised custom IDs before creating a CDK method or endpoint:
  match the reference's lowercase ASCII `[a-z0-9_-]{1,32}` rule. Reject invalid
  IDs rather than rewriting them or treating them as Lightning. The web-only
  `-subpayment` history convention need not be introduced here.
- Retain a small capability record per NUT direction: method, unit, optional
  advertised name, minimum, and maximum. Keep direction-disabled information.
  Resolve capabilities against the selected mint, unit, and direction, with
  deterministic ordering and deduplication by method/unit.
- Derive legacy method lists from live capabilities where still needed.
  Default absent cached metadata compatibly, but preserve an explicitly empty
  live advertisement. Revalidate selection when the mint or unit changes.
- Prefer a valid `method_name`, otherwise derive a readable label from the ID.
  Apply the reference's bounded printable-name policy; keep identity separate
  from the label. Centralize the generic icon and neutral request/action copy.
- Update discovery and detail enumeration so custom methods are not lost after
  the service layer accepts them. Include old-cache round trips and malformed
  advertisement tests in this commit.

### 2. Connect custom quotes and make withdrawal context explicit

- Receive: pass the selected custom method, positive base-unit amount, and unit
  into existing mint quote creation. Use existing CDK storage, signing,
  `mintUnified`, and coordinator behavior. Enforce advertised limits before
  creation, with mint errors remaining authoritative.
- Send: add one explicit custom-melt entry point with method, mint URL, unit,
  amount, and opaque request text. Keep existing parsed invoice/address entry
  points intact. Call CDK `meltQuote(custom(id), request, extra: {amount})` on
  the chosen unit's wallet. Build JSON with the platform serializer.
- Carry unit through `MeltQuoteInfo` and the payment detail/result context.
  Resolve preparation, status checks, and recovery from the stored quote's
  mint/unit. Select funds and validate amount plus fee reserve in that unit.
  A later active-mint/unit change must not redirect an existing quote.
- Reuse prepared-melt confirmation, pending handling, and compensation checks.
  A timeout remains pending/uncertain until CDK resolves it; it must not cause
  an automatic second withdrawal or imply that reserved funds were returned.
- Change Android's pending-saga sweep to visit stored unit wallets, reusing
  stored-account enumeration. Keep iOS's existing all-wallet sweep.
- Verify NUT-20 ownership, the actual melt request body, pending settlement,
  void/refund, and unit isolation against a controlled CDK custom backend.

### 3. Preserve partial deposits and recoverable history

- Keep issuance based on `amountPaid - amountIssued`. Distinguish an issued
  payment installment from completion of the requested quote amount. A
  40-of-100 deposit must not close monitoring merely because those 40 units
  have been issued. Complete only after the target is met and paid credit is
  fully issued; preserve existing built-in behavior.
- Select unfinished custom quotes from stored quotes even after their first
  issuance. Use the same selection for maintenance and recoverable quote
  presentation. Reuse persisted schedules/backoff rather than another worker.
- Keep the requested amount available across refresh/restart. Expiry ends the
  invitation to pay; it must not suppress recovery of paid-but-unissued credit
  or a required late status check. Treat custom zero/no-expiry quotes according
  to the existing never-expiring convention, without converting them to BOLT11.
- Preserve method identity in transaction projection and add a neutral custom
  payment kind. History uses the advertised/fallback label and actual unit.
  Reopen custom quote details by stored ID and direction, including requests
  that are empty or not parseable as invoices.
- Reuse CDK transactions as the money ledger and existing quote intents for
  incomplete-request presentation where needed. Do not duplicate completed
  receipts to keep an unfinished quote visible, or invent subpayment IDs.
- Test partial payment, first issuance, durable reopen, remaining payment,
  final issuance, and an additional sweep yielding no duplicate credit.

### 4. Expose the flows in the existing native UI

- Receive: append advertised custom methods to the current method picker and
  use the existing amount, unit, and quote display flow. Require an amount for
  custom quote creation; no new reusable/amountless custom-offer editor.
- Send: expose advertised custom methods through a compact native selection
  control on Unified Send. Route explicit selection into its amount and
  confirmation flow with a plain request/memo field. An unknown pasted string
  remains unrecognized until the user explicitly selects a custom method.
- Reuse the QR/copy/detail primitives for generic request text and quote IDs.
  Keep the request and ID distinct: a payment URL or instruction is not the
  quote ID. Offer a bare-ID QR and full copy action with the last six characters
  visually emphasized, in Receive, withdrawal confirmation/pending, and history.
  Keep withdrawal ID access available while teller settlement is pending.
- Present requests as plain text, optionally with the existing user-initiated
  copy/share actions. Add no automatic external navigation or embedded web UI.
- Display unit-correct amount, fees, requested/received progress, and truthful
  pending/settled status. Give the full quote ID an accessible label; do not
  depend on suffix styling alone for identification.
- Work in active Unified Send routes and existing components. Avoid unrelated
  view extraction, renaming the Lightning service/screens, or rewriting legacy
  melt views simply because their names predate generic support.

## Validation and review boundaries

Run relevant existing model, capability, quote, focused-monitor, schedule,
history, and melt-recovery tests on both platforms. Add behavior tests at those
boundaries rather than exhaustive snapshots of every new getter.

Required scenarios:

1. Custom-only mint/unit; mixed built-ins and custom methods; distinct mint-only
   and melt-only advertisements; disabled/empty capabilities; the same method
   with different unit limits and different names at different mints.
2. Valid and invalid IDs, missing/invalid labels, old serialized caches, refreshed
   capabilities, and preservation of built-in aliases/serialization.
3. Locked deposit accepted, another wallet unable to issue it, issuance after
   restart, partial payments, duplicate observations, expiry with outstanding
   credit, and no-WebSocket operation.
4. Custom-unit withdrawal with exact wire amount; fee/balance conservation;
   pending then paid; pending then void; lost response and restart; no second
   debit; another unit's proofs/balance untouched.
5. Matched iOS/Android UI evidence for custom Receive, explicit Send, quote-ID
   display, pending withdrawal, and history reopening. Verify QR payloads,
   copy actions, long text, large text, and accessibility labels.
6. Existing BOLT11, reusable BOLT12, on-chain, and ordinary ecash flows retain
   their current behavior and amount precision.

Use a pinned Pecan/CDK test fixture or a minimal controlled CDK custom processor
for real settlement checks. Existing Lightning-only fake flows cannot establish
generic endpoint routing or proof conservation for this feature. Keep test
fixture work confined to the existing integration harness.

Suggested review slices: identity/capabilities; execution/recovery/history;
native UI. Include each slice's relevant tests with it. Enable the selectable
custom flows only when execution and recovery are complete on both platforms.
Optimize for a small number of coherent changes, not a promised line count that
would hide required correctness work.

## Deliberate scope

“Generic” means arbitrary well-formed advertised method IDs using the common
amount/unit/request/quote contract, as in the reference PR. It does not imply
discovery of arbitrary backend-specific form schemas from a method name.
Backends requiring additional undiscoverable fields receive a clear mint error.

No Pecan-specific method switch, backend plugin registry, custom HTTP payment
client, proof ledger, app database migration, currency-formatting rewrite,
cross-platform UI framework, or dependency upgrade is proposed. Custom NUT-17
push support is deferred behind working polling on both platforms. Supporting
non-sat Lightning withdrawals or non-sat Cashu payment requests is separate work;
custom withdrawals use their explicitly selected advertised unit.

## Implementation and validation

Both apps now preserve custom method IDs, per-unit capabilities, names and
limits. Receive and Send share the native amount-entry layout and numeric
keypad. Custom withdrawals appear in the existing method list, with the optional
request or memo in the toolbar. Mint and melt quotes share the adaptive QR
layout and show one copyable quote ID, with its final six characters emphasized.
Custom requests remain plain text in history. History retains the method and
unit. CDK still owns signing
keys, proofs, payment execution and recovery; there is no SDK upgrade or new
payment ledger.

Validation performed:

- iOS: 112 focused tests passed, including the real CDK custom-method round trip,
  persisted partial-quote discovery, transaction projection, unit formatting,
  quote monitoring/scheduling, account recovery and QR accessibility behavior.
- Android: all 751 unit tests completed, with zero failures and six existing
  skips (test locale fixed to English/US). The native gateway integration test
  also exercises custom deposit, NUT-20 issuance after database reopen, payout
  quote amount/unit, asynchronous withdrawal recovery after reopen and balance
  conservation.
- Both app builds and `git diff --check` passed.
- Both native round trips passed against mixed sat/bux and bux-only mints.
- After the shared amount-entry UI changes: 56 iOS tests passed (one opt-in
  integration test skipped), 22 Android amount-entry/custom-method tests passed,
  and both native builds passed. The Android debug build was installed for
  physical-device testing.

The opt-in integration fixture is [custom-method-test-mint.toml](../CI/custom-method-test-mint.toml).
Use the **v0.18.0** `cdk-mintd` release with a fresh work directory and a
disposable `CUSTOM_METHOD_TEST_MNEMONIC`. Initialize with `config init --new-mint
--file CI/custom-method-test-mint.toml`, then start that mint with the same work
directory. The existing general CI mint setup is intentionally unchanged.

- iOS: set `TEST_RUNNER_CUSTOM_METHOD_MINT_URL=http://127.0.0.1:3349` for
  `xcodebuild test -only-testing:CashuWalletTests/CustomPaymentMethodTests` with
  the repository's normal project, scheme and simulator arguments.
- Android: run `:app:connectedDebugAndroidTest` with
  `-Pandroid.testInstrumentationRunnerArguments.class=com.cashu.me.liveintegration.NativeWalletLocalMintInstrumentedTest#customDepositRestartAndWithdrawalStayInTheirUnit`
  and `-Pandroid.testInstrumentationRunnerArguments.cashu.customMethodMintUrl=http://10.0.2.2:3349`.
  The Android emulator and mint clocks must agree.
- To check a mint without a satoshi unit, remove the sat backend and `sat` from
  `supported_units`, initialize another fresh work directory, and rerun the
  same tests.

These tests use CDK's fake payment processor with real wallet cryptography and
storage. They do not validate a Pecan teller deployment, teller-driven voids,
or real cash payout. Partial issuance is covered by domain and database tests;
the fake processor pays deposits in full. Custom notifications use polling
because CDK 0.18's native bindings lack a custom subscription type.
