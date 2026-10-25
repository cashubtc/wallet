import Cdk
import Foundation

extension WalletManager {
    func sendRecoverySnapshot(
        wallet: Wallet,
        context: PendingSendRecovery
    ) async throws -> PendingSendRecovery {
        guard let db else { throw WalletError.notInitialized }
        let pending = try await wallet.getPendingSends()
        let sagas = try await db.getIncompleteSagas()
        let transactions = try await wallet.listTransactions(direction: .outgoing)
        return try resolvePendingSendRecovery(
            context: context, mintURL: wallet.mintUrl().url, sagaJSON: sagas, pendingIDs: pending,
            transactions: Dictionary(transactions.map { transaction in
                (transaction.id.hex, transaction.status == .completed ? SendRecoveryTransactionStatus.completed
                    : transaction.status == .pending ? .pending : .failed)
            }, uniquingKeysWith: { _, latest in latest })
        )
    }

    func checkSendRecovery(_ context: PendingSendRecovery) async throws -> PendingSendRecovery {
        let result = try await operationCoordinator.perform(kind: .tokenStatus, resourceID: context.mintURL) {
            guard let wallet = try await self.walletRepository?.getWallet(
                mintUrl: MintUrl(url: context.mintURL),
                unit: PaymentRequestDecoder.currencyUnit(from: context.unit)
            ) else { throw WalletError.notInitialized }
            _ = try await wallet.recoverIncompleteSagas()
            return try await self.sendRecoverySnapshot(wallet: wallet, context: context)
        }
        await refreshBalance()
        await loadTransactions()
        return result
    }

    @discardableResult
    func reclaimPendingSend(operationID: String, mintURL: String, unit: String) async throws -> UInt64 {
        let amount = try await operationCoordinator.perform(kind: .send, resourceID: mintURL, protectsBackgroundExecution: true) {
            guard let wallet = try await self.walletRepository?.getWallet(
                mintUrl: MintUrl(url: mintURL), unit: PaymentRequestDecoder.currencyUnit(from: unit)
            ) else { throw WalletError.notInitialized }
            return try await wallet.revokeSend(operationId: operationID).value
        }
        await refreshBalance()
        await loadTransactions()
        return amount
    }
}
