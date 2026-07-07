# Android Service Boundary Notes

Android intentionally keeps the core wallet runtime centered on `WalletManager` for this unreleased build.

The iOS app has separate service files for mint, token, transaction, Lightning, and payment flows. Android currently exposes the same domain contracts through protocols and small compatibility anchors:

- `Core/Protocols/WalletServiceProtocol.kt`
- `Core/Protocols/PaymentMethodProtocol.kt`
- `Core/Services/LightningService.kt`
- `Core/Services/MintService.kt`
- `Core/Services/TokenService.kt`
- `Core/Services/TransactionService.kt`
- `Core/Services/NFCPaymentService.kt`

This keeps CDK repository ownership, wallet-scoped storage snapshots, pending quote sync, receive events, and balance/transaction refresh ordering in one place while Android is still catching up to iOS feature parity.

Acceptance rule:

- New UI screens should depend on the protocol/domain helpers where practical.
- Runtime wallet mutations should continue to refresh balance, reload transactions, and emit receive events from `WalletManager` until a real split removes complexity.
- If the Android service layer is later split, it must preserve the tests in `WalletServiceProtocolTest`, pending quote tests, transaction metadata tests, and wallet boundary recovery tests.
