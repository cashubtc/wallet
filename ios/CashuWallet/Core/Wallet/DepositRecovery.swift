import Cdk
import Foundation

struct DepositRecoveryRequest: Equatable {
    let mintURL: String
    let quoteID: String
    let method: PaymentMethodKind
    let unit: String
    let searchLimit: UInt32

    static let searchLimits: [UInt32] = [1_000, 10_000]
    static let units = ["sat", "msat", "usd", "eur"]

    init(mintURL: String, quoteID: String, method: PaymentMethodKind, unit: String, searchLimit: UInt32) throws {
        let trimmed = mintURL.trimmingCharacters(in: .whitespacesAndNewlines)
        let url = trimmed.contains("://") ? trimmed : "https://" + trimmed
        guard let parts = URLComponents(string: url), let host = parts.host, !host.isEmpty,
              parts.user == nil, parts.password == nil, parts.query == nil, parts.fragment == nil,
              parts.port.map({ (1...65535).contains($0) }) ?? true,
              parts.scheme == "https" || (parts.scheme == "http" && ["localhost", "127.0.0.1", "[::1]", "::1"].contains(host)) else {
            throw DepositRecoveryError.invalidMint
        }
        let id = quoteID.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !id.isEmpty, id.utf8.count <= 1_024,
              !id.unicodeScalars.contains(where: CharacterSet.whitespacesAndNewlines.union(.controlCharacters).contains) else {
            throw DepositRecoveryError.invalidQuote
        }
        let normalizedUnit = unit.lowercased()
        guard Self.units.contains(normalizedUnit), Self.searchLimits.contains(searchLimit) else {
            throw DepositRecoveryError.invalidOptions
        }
        self.mintURL = MintURLIdentity.normalized(url)
        self.quoteID = id
        self.method = method
        self.unit = normalizedUnit
        self.searchLimit = searchLimit
    }
}

enum DepositRecoveryError: LocalizedError {
    case invalidMint, invalidQuote, invalidOptions, differentQuote

    var errorDescription: String? {
        switch self {
        case .invalidMint: "Enter a valid HTTPS mint URL without a login, query, or fragment."
        case .invalidQuote: "Enter the deposit's quote ID, without spaces."
        case .invalidOptions: "Choose a supported currency and search depth."
        case .differentQuote: "This quote belongs to a different mint, currency, or payment method. Check the deposit details."
        }
    }
}

struct DepositRecoveryResult {
    enum Status { case recovered, keyNotFound, waiting, alreadyClaimed, pending, expired }
    let status: Status
    var amount: UInt64 = 0
    let unit: String
    var remaining: UInt64 = 0

    static func from(quote: MintQuoteInfo, newlyIssued: UInt64 = 0, retryPending: Bool = false) -> Self {
        if newlyIssued > 0 { return Self(status: .recovered, amount: newlyIssued, unit: quote.unit, remaining: quote.mintableAmount) }
        if quote.mintableAmount > 0 || retryPending { return Self(status: .pending, unit: quote.unit) }
        if quote.hasSettledPayment || quote.state == .issued { return Self(status: .alreadyClaimed, unit: quote.unit) }
        if quote.isExpired && quote.paymentMethod == .bolt11 { return Self(status: .expired, unit: quote.unit) }
        return Self(status: .waiting, unit: quote.unit)
    }

    var message: String {
        switch status {
        case .recovered:
            let value = CurrencyAmount(value: amount, currency: CurrencyRegistry.currency(forMintUnit: unit)).formatted()
            return remaining > 0 ? "Recovered \(value). More funds are still pending; the wallet will retry." : "Recovered \(value). Your balance and history have been updated."
        case .keyNotFound:
            return "No matching key found. Try the deeper search, or restore the original wallet's recovery phrase first. Deposits made with random keys require the original database backup."
        case .waiting:
            return "Deposit key recovered. No funds are available yet. The wallet will keep checking this deposit."
        case .alreadyClaimed:
            return "This deposit has already been claimed. Use Restore to recover ecash that was issued before you lost your wallet."
        case .pending:
            return "Deposit key recovered. Claiming the funds is still pending; the wallet will retry automatically."
        case .expired:
            return "This unpaid deposit has expired. There are no funds available to claim."
        }
    }
}

extension WalletManager {
    /// Called only after approval. Import and reconciliation share the repository
    /// lease, preserving CDK's quote keys, counters, and incomplete issue sagas.
    func recoverDeposit(_ request: DepositRecoveryRequest) async throws -> DepositRecoveryResult {
        guard isRuntimeReady, !needsOnboarding else { throw WalletError.notInitialized }
        weak var expectedRepository = walletRepository
        return try await operationCoordinator.perform(kind: .recovery, resourceID: request.quoteID,
                                                     protectsBackgroundExecution: true, defaultFailureOutcome: .ambiguousFailure) {
            guard self.isRuntimeReady, let repo = self.walletRepository, repo === expectedRepository,
                  let db = self.db else { throw WalletError.notInitialized }
            do {
                let result = try await self.recoverDepositAssumingLease(request, repo: repo, db: db)
                await self.publishRecoveredDepositAccountsAssumingLease()
                return result
            } catch {
                // NUT-09 or minting may already have committed proofs. Retain
                // their account metadata even when a later network call fails.
                await self.publishRecoveredDepositAccountsAssumingLease()
                throw error
            }
        }
    }

    private func publishRecoveredDepositAccountsAssumingLease() async {
        _ = await reconcileReceivedAccountsAssumingLease()
        await refreshBalanceAssumingWalletOperationLease()
        await loadTransactionsAssumingWalletOperationLease()
        performICloudBackup()
        Task { await NostrMintBackupService.shared.backupCurrentMintsIfEnabled() }
    }

    private func recoverDepositAssumingLease(_ request: DepositRecoveryRequest, repo: WalletRepository,
                                             db: WalletSqliteDatabase) async throws -> DepositRecoveryResult {
        let unit = PaymentRequestDecoder.currencyUnit(from: request.unit)
        let existing = try await db.getMintQuote(quoteId: request.quoteID)
        if let stored = existing {
            guard MintRemovalPolicy.matches(stored.mintUrl.url, request.mintURL), stored.unit == unit,
                  stored.paymentMethod == request.method.cdkMethod else { throw DepositRecoveryError.differentQuote }
        }
        try await repo.createWallet(mintUrl: MintUrl(url: request.mintURL), unit: unit, targetProofCount: nil)
        let wallet = try await repo.getWallet(mintUrl: MintUrl(url: request.mintURL), unit: unit)
        // A fresh database also lost its proof-output counters. Restore
        // existing proofs before importing a quote that background recovery
        // could issue, so previously signed outputs are never reused.
        self.walletStore.setMintRemoved(url: request.mintURL, removed: false)
        _ = try await wallet.restore()
        let recovered = try await wallet.fetchMintQuoteWithKeySearch(quoteId: request.quoteID,
            paymentMethod: request.method.cdkMethod, keySearchLimit: request.searchLimit)
        guard recovered.unit == unit, MintRemovalPolicy.matches(recovered.mintUrl.url, request.mintURL),
              recovered.paymentMethod == request.method.cdkMethod else {
            if existing == nil { try await db.removeMintQuote(quoteId: request.quoteID) }
            throw DepositRecoveryError.differentQuote
        }
        guard let quote = await self.lightningService.storedMintQuote(quoteId: request.quoteID) else { throw WalletError.notInitialized }
        // A new quote without a matching key must not enter automatic
        // issuance. Never delete a pre-existing quote or its saga journal.
        if existing == nil && recovered.secretKey == nil { try await db.removeMintQuote(quoteId: request.quoteID) }
        if quote.state == .issued || quote.hasSettledPayment {
            return DepositRecoveryResult(status: .alreadyClaimed, unit: quote.unit)
        }
        guard recovered.secretKey != nil else {
            return DepositRecoveryResult(status: .keyNotFound, unit: quote.unit)
        }
        self.walletStore.setMintRemoved(url: request.mintURL, removed: false)
        self.mintService.trackReceivedMintLocally(url: request.mintURL, unit: unit)
        self.rememberMintQuoteForScheduling(quote)
        let reconciled = await self.reconcileMintQuoteAssumingLease(quoteId: request.quoteID, force: true)
        guard let reconciled else { return DepositRecoveryResult(status: .pending, unit: quote.unit) }
        return DepositRecoveryResult.from(quote: reconciled.quote, newlyIssued: reconciled.newlyIssued,
            retryPending: reconciled.retryStatus.state != .none)
    }

}
