import Foundation
import Cdk

struct MintQuoteWalletContext: Equatable {
    let mintURL: String
    let unit: Cdk.CurrencyUnit
}

/// Keeps every operation on the wallet that owns the quote. Missing persisted
/// context fails closed rather than guessing from mutable active-wallet state.
enum MintQuoteContextPolicy {
    static func context(for quote: MintQuote) -> MintQuoteWalletContext {
        MintQuoteWalletContext(mintURL: quote.mintUrl.url, unit: quote.unit)
    }

    static func walletContext(storedQuote: MintQuote?) -> MintQuoteWalletContext? {
        storedQuote.map { context(for: $0) }
    }
}

/// Builds CDK's explicit amount override for a request that does not carry one.
/// Keeping this small conversion outside the service makes the protocol boundary
/// directly testable without a live wallet repository.
func meltOptionsForLightningRequest(
    requestAmountMsat: UInt64?,
    amountSats: UInt64?
) throws -> MeltOptions? {
    guard requestAmountMsat == nil else { return nil }
    guard let amountSats, amountSats > 0 else {
        throw WalletError.networkError(
            "This Lightning request doesn't include an amount. Enter an amount before requesting a quote."
        )
    }
    guard amountSats <= UInt64.max / 1_000 else {
        throw WalletError.networkError("Amount is too large.")
    }
    return .amountless(amountMsat: Amount(value: amountSats * 1_000))
}

/// Which proofs a melt spends.
enum MeltProofSelection: Equatable {
    /// CDK picks the proofs, swapping first when it needs exact inputs.
    case automatic
    /// Every unspent proof goes in directly, with no pre-melt swap. A max
    /// cross-mint quote sizes its amount on the input fee of exactly this proof
    /// set, so a swap would charge a second fee the balance no longer covers.
    case allUnspentSkippingSwap(expectedInputFee: UInt64)
}

/// The proofs on hand no longer cost what the quote was sized for, so melting
/// them would not leave the amount the quote promised. Nothing was sent.
struct MeltInputFeeChanged: Error, Equatable {
    let expected: UInt64
    let actual: UInt64
}

/// A destination mint quote and the source melt quote that pays it.
struct CrossMintQuotes {
    let mintQuote: MintQuoteInfo
    let meltQuote: MeltQuoteInfo
    /// Input fee for spending every unspent source proof. Zero on most mints.
    let inputFee: UInt64
}

/// What Max means for a Lightning or on-chain send: the largest amount a mint
/// can pay from `balance` once its melt fee reserve is added. `requiredTotal`
/// quotes the mint and returns amount + fee reserve. The reserve usually tracks
/// the amount (a percentage with a floor), so each round steps down by the
/// overshoot and a second quote normally settles it. Nil when nothing fits.
/// Ecash Max stays the gross balance: its fees are the receiver's.
func largestPayableMeltAmount(
    balance: UInt64,
    rounds: Int = 4,
    requiredTotal: (UInt64) async throws -> UInt64
) async throws -> UInt64? {
    var amount = balance
    for _ in 0..<rounds {
        guard amount > 0 else { return nil }
        let total = try await requiredTotal(amount)
        if total <= balance { return amount }
        let overshoot = total - balance
        amount = amount > overshoot ? amount - overshoot : 0
    }
    return nil
}

// MARK: - Lightning Service

/// Service responsible for Lightning Network operations (NUT-04/NUT-05).
/// Handles minting (receiving via Lightning) and melting (paying via Lightning).
@MainActor
class LightningService: ObservableObject {
    static func requiredMeltAmount(amount: UInt64, feeReserve: UInt64) throws -> UInt64 {
        let total = amount.addingReportingOverflow(feeReserve)
        guard !total.overflow else {
            throw WalletError.networkError("The mint returned an invalid payment amount or fee.")
        }
        return total.partialValue
    }

    private enum QuoteExpiry {
        static let never: UInt64 = 0
        static let localNeverExpiresSentinel: UInt64 = 253_402_300_799
    }

    // MARK: - Published Properties
    
    /// Whether an operation is in progress
    @Published var isLoading = false
    
    // MARK: - Dependencies
    
    private let walletRepository: () -> WalletRepository?
    private let walletDatabase: () -> WalletSqliteDatabase?
    private let getActiveMint: () -> MintInfo?
    private let getMints: () -> [MintInfo]
    private var mintQuotesInFlight: Set<String> = []
    // These native waits can outlive the wallet operation lease. They cannot
    // be cancelled by Swift and must survive clearState() until they return.
    private var activeMeltSettlementWaits = 0
    
    // MARK: - Initialization
    
    init(
        walletRepository: @escaping () -> WalletRepository?,
        walletDatabase: @escaping () -> WalletSqliteDatabase?,
        getActiveMint: @escaping () -> MintInfo?,
        getMints: @escaping () -> [MintInfo] = { [] }
    ) {
        self.walletRepository = walletRepository
        self.walletDatabase = walletDatabase
        self.getActiveMint = getActiveMint
        self.getMints = getMints
    }

    func clearState() {
        isLoading = false
        mintQuotesInFlight.removeAll()
    }

    func requireNoActiveMeltSettlement() throws {
        guard activeMeltSettlementWaits == 0 else {
            throw WalletError.networkError("A payment is still settling. Wait for it to finish before replacing or deleting the wallet.")
        }
    }
    
    // MARK: - Minting (NUT-04) - Receive via Lightning
    
    /// Create a mint quote for the requested payment method.
    /// - Parameters:
    ///   - amount: Amount in satoshis when required by the payment method
    ///   - method: The payment method to use for the quote
    /// - Returns: Mint quote with request details
    /// - Parameter targetMintURL: mint the quote is created at. Defaults to the
    ///   active mint (existing callers). Pass an explicit URL to mint at a
    ///   specific mint — e.g. funding a freshly-added mint to pay a Cashu request.
    ///   Honored for all supported payment methods.
    func createMintQuote(
        amount: UInt64?,
        method: PaymentMethodKind = .bolt11,
        targetMintURL: String? = nil,
        unit: Cdk.CurrencyUnit = .sat,
        description: String? = nil
    ) async throws -> MintQuoteInfo {
        guard let activeMint = getActiveMint() else {
            throw WalletError.notInitialized
        }

        isLoading = true
        defer { isLoading = false }

        if method.requiresMintAmount {
            guard let amount, amount > 0 else {
                throw WalletError.networkError("An amount is required for \(method.displayName) receive requests.")
            }
        }

        if method == .onchain {
            return try await createOnchainMintQuote(mintURL: targetMintURL ?? activeMint.url)
        }

        guard let repo = walletRepository() else {
            throw WalletError.notInitialized
        }

        let mintUrl = MintUrl(url: targetMintURL ?? activeMint.url)
        // Mint into the selected unit's wallet (amount is in that unit's base
        // units). Ensure a non-sat per-unit wallet exists first (sat is always
        // tracked) — mirrors TokenService.sendTokens.
        if PaymentRequestDecoder.unitDescription(unit) != "sat" {
            try await repo.createWallet(mintUrl: mintUrl, unit: unit, targetProofCount: nil)
        }
        let wallet = try await repo.getWallet(mintUrl: mintUrl, unit: unit)

        let offerDescription = method == .bolt12 ? MintQuoteDomain.normalizedOfferDescription(description) : nil
        let quote = try await wallet.mintQuote(
            paymentMethod: method.cdkMethod,
            amount: amount.map { Amount(value: $0) },
            // Description is only threaded for BOLT12 offers (NUT-04 optional);
            // mint support on other rails is uneven, so they keep nil.
            description: offerDescription,
            extra: nil
        )

        await persistMintQuote(quote, paymentMethod: method)

        var info = mintQuoteInfo(from: quote, fallbackAmount: amount, paymentMethod: method)
        info.description = offerDescription
        return info
    }

    /// Returns the pending amountless BOLT12 offer matching `description`
    /// (nil → the plain, description-less offer), or nil if none exists.
    /// Used to avoid creating a new offer on every visit to the Reusable
    /// Invoice screen. CDK never returns offer descriptions, so the match
    /// joins quotes with the locally stored quote-intent memos by quote id —
    /// keeping reuse unambiguous once several amountless offers exist (offers
    /// are immutable, so a changed description always mints a fresh one).
    func existingAmountlessOffer(mintURL: String, unit: Cdk.CurrencyUnit, description: String? = nil) async throws -> MintQuoteInfo? {
        guard let db = walletDatabase() else { return nil }
        let pendingQuotes = try await db.getUnissuedMintQuotes()
        let memosByQuoteId = Dictionary(
            CashuRequestStore.shared.requests.compactMap { request in
                request.quoteId.map { ($0, request.memo) }
            },
            uniquingKeysWith: { first, _ in first }
        )
        guard let match = pendingQuotes.first(where: {
            MintQuoteDomain.isReusableAmountlessOffer(
                paymentMethod: PaymentMethodKind.from($0.paymentMethod),
                isAmountless: $0.amount == nil,
                quoteMintUrl: $0.mintUrl.url,
                quoteUnit: PaymentRequestDecoder.unitDescription($0.unit),
                activeMintUrl: mintURL,
                unit: PaymentRequestDecoder.unitDescription(unit),
                storedMemo: memosByQuoteId[$0.id] ?? nil,
                description: description
            )
        }) else { return nil }
        var info = mintQuoteInfo(from: match, fallbackAmount: nil, paymentMethod: .bolt12)
        info.description = description
        return info
    }

    /// Returns an existing unpaid onchain quote at the active mint, or nil if none exists.
    /// Used to avoid generating a fresh deposit address on every visit to the onchain receive screen.
    func existingOnchainMintQuote(mintURL: String? = nil) async throws -> MintQuoteInfo? {
        guard let db = walletDatabase(),
              let mintURL = mintURL ?? getActiveMint()?.url else { return nil }
        let pendingQuotes = try await db.getUnissuedMintQuotes()
        guard let match = MintQuoteDomain.reusableOnchainAddress(in: pendingQuotes, mintURL: mintURL) else {
            return nil
        }
        return mintQuoteInfo(from: match, fallbackAmount: nil, paymentMethod: .onchain)
    }

    func checkMintQuote(quoteId: String) async throws -> MintQuoteInfo {
        guard let repo = walletRepository() else {
            throw WalletError.notInitialized
        }

        if let walletDatabase = walletDatabase(),
           let existingQuote = try await walletDatabase.getMintQuote(quoteId: quoteId) {
            let storedPaymentMethod = PaymentMethodKind.from(existingQuote.paymentMethod)

            if storedPaymentMethod == .onchain {
                let storedQuote = try await refreshStoredOnchainMintQuoteStatus(
                    existingQuote,
                    fallbackAmount: existingQuote.amount?.value
                )
                return mintQuoteInfo(
                    from: storedQuote,
                    fallbackAmount: existingQuote.amount?.value,
                    paymentMethod: .onchain
                )
            }

            if storedPaymentMethod == .bolt12 {
                await persistMintQuoteIfNeeded(existingQuote, paymentMethod: .bolt12)
            }

            let context = MintQuoteContextPolicy.context(for: existingQuote)
            let wallet = try await repo.getWallet(
                mintUrl: MintUrl(url: context.mintURL),
                unit: context.unit
            )
            let quote = try await wallet.checkMintQuote(quoteId: quoteId)
            let paymentMethod = PaymentMethodKind.from(quote.paymentMethod) ?? storedPaymentMethod ?? .bolt11
            let refreshedQuote = try await storedMintQuoteAfterStatusCheck(
                quoteId: quoteId,
                paymentMethod: paymentMethod,
                fallbackAmount: existingQuote.amount?.value
            )
            return mintQuoteInfo(
                from: refreshedQuote,
                fallbackAmount: existingQuote.amount?.value,
                paymentMethod: paymentMethod
            )
        }

        throw WalletError.networkError(
            "The mint context for this receive request is unavailable. Create a new request and try again."
        )
    }

    /// Last durable local quote snapshot without contacting the mint. Recovery
    /// uses this only to tell the truth after a status request fails: a cached
    /// paid/issued delta is enough to say that ecash is still pending, but never
    /// enough to claim success.
    func storedMintQuote(quoteId: String) async -> MintQuoteInfo? {
        guard let database = walletDatabase() else { return nil }
        let quote: MintQuote
        do {
            guard let stored = try await database.getMintQuote(quoteId: quoteId) else {
                return nil
            }
            quote = stored
        } catch {
            return nil
        }
        let method = PaymentMethodKind.from(quote.paymentMethod) ?? .bolt11
        return mintQuoteInfo(
            from: quote,
            fallbackAmount: quote.amount?.value,
            paymentMethod: method
        )
    }
    
    /// Mint tokens after invoice is paid
    /// - Parameter quoteId: The quote ID to mint
    /// - Returns: Total amount minted
    func mintTokens(quoteId: String) async throws -> UInt64 {
        guard let repo = walletRepository() else {
            throw WalletError.notInitialized
        }

        guard !mintQuotesInFlight.contains(quoteId) else {
            throw WalletError.networkError("Mint quote is already being minted.")
        }

        mintQuotesInFlight.insert(quoteId)
        defer {
            mintQuotesInFlight.remove(quoteId)
        }
        
        isLoading = true
        defer { isLoading = false }

        let mintUrl: MintUrl
        let amountSplitTarget: SplitTarget
        // Redeem into the quote's own unit wallet (also makes resuming a
        // persisted non-sat quote correct). Never guess from the active wallet.
        let quoteUnit: Cdk.CurrencyUnit

        if let walletDatabase = walletDatabase(),
           let existingQuote = try await walletDatabase.getMintQuote(quoteId: quoteId) {
            let storedPaymentMethod = PaymentMethodKind.from(existingQuote.paymentMethod)
            let currentQuote = if storedPaymentMethod == .onchain {
                try await refreshStoredOnchainMintQuoteStatus(
                    existingQuote,
                    fallbackAmount: existingQuote.amount?.value
                )
            } else {
                existingQuote
            }

            let normalizedQuote = mintQuoteForLocalStorage(
                currentQuote,
                paymentMethod: storedPaymentMethod ?? .bolt11,
                fallbackAmount: nil
            )
            if normalizedQuote.amount?.value != currentQuote.amount?.value
                || normalizedQuote.expiry != currentQuote.expiry {
                try await replaceStoredMintQuote(normalizedQuote, in: walletDatabase)
            }

            mintUrl = normalizedQuote.mintUrl
            amountSplitTarget = .none
            quoteUnit = normalizedQuote.unit

            if storedPaymentMethod == .onchain,
               normalizedQuote.amountPaid.value <= normalizedQuote.amountIssued.value {
                throw WalletError.networkError(
                    "Mint has not credited this on-chain quote yet (amount_paid=\(normalizedQuote.amountPaid.value), amount_issued=\(normalizedQuote.amountIssued.value))."
                )
            }

            if storedPaymentMethod == .bolt12 {
                await persistMintQuoteIfNeeded(normalizedQuote, paymentMethod: .bolt12)
            }

            if normalizedQuote.usedByOperation != nil {
                let wallet = try await repo.getWallet(mintUrl: mintUrl, unit: quoteUnit)
                let recoveredAmount = try await MintQuoteRecovery.reconcile(
                    quote: normalizedQuote,
                    recover: { _ = try await wallet.recoverIncompleteSagas() },
                    reload: { try await walletDatabase.getMintQuote(quoteId: quoteId) }
                )
                if recoveredAmount > 0 { return recoveredAmount }
            }
        } else {
            throw WalletError.networkError(
                "The mint context for this receive request is unavailable. Create a new request and try again."
            )
        }

        let wallet = try await repo.getWallet(mintUrl: mintUrl, unit: quoteUnit)
        guard let database = walletDatabase() else { throw WalletError.notInitialized }
        try await MintRestoreSafety.prepareEmptyAccount(database: database, wallet: wallet, mintURL: mintUrl.url)
        let proofs = try await wallet.mintUnified(
            quoteId: quoteId,
            amountSplitTarget: amountSplitTarget,
            spendingConditions: nil
        )
        
        return proofs.reduce(UInt64(0)) { $0 + $1.amount.value }
    }
    
    // MARK: - Melting (NUT-05) - Pay via Lightning
    
    /// Create a melt quote for paying a Lightning payment request
    /// - Parameter request: The BOLT11 invoice or BOLT12 offer to pay
    /// - Returns: Melt quote with fee information
    func createMeltQuote(
        request: String,
        amount: UInt64? = nil,
        preferredMintURL: String? = nil
    ) async throws -> MeltQuoteInfo {
        guard let repo = walletRepository() else {
            throw WalletError.notInitialized
        }
        
        isLoading = true
        defer { isLoading = false }

        guard let metadata = await CdkRuntime.shared.lightningMetadata(from: request) else {
            if PaymentRequestParser.isBitcoinAddress(request) {
                throw WalletError.networkError("On-chain payments require an amount before requesting a quote.")
            }
            throw WalletError.networkError("Invalid Lightning payment request.")
        }

        let normalizedRequest = metadata.normalizedRequest
        let paymentMethod = metadata.paymentMethod
        let invoiceAmountSats = metadata.amountSats
        let meltOptions = try meltOptionsForLightningRequest(
            requestAmountMsat: metadata.amountMsat,
            amountSats: amount
        )

        if PaymentRequestParser.isBitcoinAddress(normalizedRequest) {
            throw WalletError.networkError("On-chain payments require an amount before requesting a quote.")
        }

        let amountless = metadata.amountMsat == nil
        let candidates = meltQuoteCandidateMints(
            paymentMethod: paymentMethod,
            amountless: amountless,
            minimumAmount: invoiceAmountSats ?? amount,
            preferredMintURL: preferredMintURL
        )

        guard !candidates.isEmpty else {
            if amountless, paymentMethod == .bolt11 {
                throw WalletError.networkError("None of your mints can pay invoices without an amount.")
            }
            throw WalletError.networkError("No mint supports \(paymentMethod.displayName) payments.")
        }

        return try await firstAffordableMeltQuote(in: candidates, failureMessage: "No mint could create a melt quote for this payment request.") { mint in
            let mintUrl = MintUrl(url: mint.url)
            let wallet = try await repo.getWallet(mintUrl: mintUrl, unit: .sat)
            AppLogger.wallet.info(
                "wallet-op native-call kind=meltQuote resource=\(WalletOperationCoordinator.privacySafeIdentifier(mint.url), privacy: .public) method=\(paymentMethod.rawValue, privacy: .public)"
            )
            let quote = try await wallet.meltQuote(
                method: paymentMethod.cdkMethod,
                request: normalizedRequest,
                options: meltOptions,
                extra: nil
            )

            return meltQuoteInfo(from: quote, paymentMethod: paymentMethod, fallbackMintUrl: mint.url)
        }
    }
    
    /// Backward-compatible wrapper for older bolt11-specific call sites.
    func createMeltQuote(
        invoice: String,
        preferredMintURL: String? = nil
    ) async throws -> MeltQuoteInfo {
        try await createMeltQuote(request: invoice, amount: nil, preferredMintURL: preferredMintURL)
    }
    
    /// Create a melt quote for paying a human-readable address.
    ///
    /// Resolves the address as a Lightning Address (LUD-16 / LNURL-pay) first. If the
    /// domain returns HTTP 404/410 for LNURL discovery, falls back to `meltBip353Quote`,
    /// which resolves BIP-353 names (DNS-published BOLT12 offers).
    /// - Parameters:
    ///   - address: The user@domain address
    ///   - amount: Amount in satoshis
    /// - Returns: Melt quote with fee information
    func createHumanReadableMeltQuote(
        address: String,
        amount: UInt64,
        preferredMintURL: String? = nil
    ) async throws -> MeltQuoteInfo {
        guard let repo = walletRepository() else {
            throw WalletError.notInitialized
        }

        isLoading = true
        defer { isLoading = false }

        guard amount <= UInt64.max / 1000 else {
            throw WalletError.networkError("Amount is too large.")
        }

        return try await LightningAddressResolver.quote(
            lnurl: {
                try await self.lightningAddressMeltQuote(
                    address: address, amount: amount,
                    preferredMintURL: preferredMintURL, repo: repo
                )
            },
            bip353: {
                try await self.bip353MeltQuote(
                    address: address, amount: amount,
                    preferredMintURL: preferredMintURL, repo: repo
                )
            }
        )
    }

    private func lightningAddressMeltQuote(
        address: String,
        amount: UInt64,
        preferredMintURL: String?,
        repo: WalletRepository
    ) async throws -> MeltQuoteInfo {
        let candidates = meltQuoteCandidateMints(
            paymentMethod: .bolt11,
            minimumAmount: amount,
            preferredMintURL: preferredMintURL
        )

        guard !candidates.isEmpty else {
            throw WalletError.networkError("No mint supports Lightning payments.")
        }

        return try await firstAffordableMeltQuote(
            in: candidates,
            failureMessage: "No mint could create a melt quote for this Lightning address.",
            shouldStopOnError: LightningAddressResolver.isRecipientError
        ) { mint in
            let mintUrl = MintUrl(url: mint.url)
            let wallet = try await repo.getWallet(mintUrl: mintUrl, unit: .sat)
            AppLogger.wallet.info(
                "wallet-op native-call kind=meltQuote resource=\(WalletOperationCoordinator.privacySafeIdentifier(mint.url), privacy: .public) method=bolt11"
            )
            let quote = try await wallet.meltLightningAddressQuote(
                lightningAddress: address,
                amountMsat: Amount(value: amount * 1000)
            )

            return meltQuoteInfo(from: quote, paymentMethod: .bolt11, fallbackMintUrl: mint.url)
        }
    }

    /// Fallback for human-readable addresses without an LNURL-pay endpoint (BIP-353 names).
    /// BIP-353 resolves to a BOLT12 offer, so only bolt12-capable mints are candidates.
    private func bip353MeltQuote(
        address: String,
        amount: UInt64,
        preferredMintURL: String?,
        repo: WalletRepository
    ) async throws -> MeltQuoteInfo {
        let candidates = meltQuoteCandidateMints(
            paymentMethod: .bolt12,
            minimumAmount: amount,
            preferredMintURL: preferredMintURL
        )

        guard !candidates.isEmpty else {
            throw WalletError.networkError("No mint supports BOLT12 payments required for this address.")
        }

        return try await firstAffordableMeltQuote(in: candidates, failureMessage: "No mint could create a melt quote for this address.") { mint in
            let mintUrl = MintUrl(url: mint.url)
            let wallet = try await repo.getWallet(mintUrl: mintUrl, unit: .sat)
            AppLogger.wallet.info(
                "wallet-op native-call kind=meltQuote resource=\(WalletOperationCoordinator.privacySafeIdentifier(mint.url), privacy: .public) method=bolt12"
            )
            let quote = try await wallet.meltBip353Quote(
                bip353Address: address,
                amountMsat: Amount(value: amount * 1000),
                network: bitcoinNetwork(for: mint.url)
            )

            let paymentMethod = PaymentMethodKind.from(quote.paymentMethod) ?? .bolt12
            return meltQuoteInfo(from: quote, paymentMethod: paymentMethod, fallbackMintUrl: mint.url)
        }
    }

    func createOnchainMeltQuote(
        address: String,
        amount: UInt64,
        preferredMintURL: String? = nil
    ) async throws -> MeltQuoteInfo {
        guard let repo = walletRepository() else {
            throw WalletError.notInitialized
        }

        isLoading = true
        defer { isLoading = false }

        let normalizedAddress = PaymentRequestParser.normalizeBitcoinRequest(address)
        let candidates = meltQuoteCandidateMints(
            paymentMethod: .onchain,
            minimumAmount: amount,
            preferredMintURL: preferredMintURL
        )

        guard !candidates.isEmpty else {
            throw WalletError.networkError("No mint supports On-chain payments.")
        }

        return try await firstAffordableMeltQuote(in: candidates, failureMessage: "No mint could create a melt quote for this on-chain payment.") { mint in
            let mintUrl = MintUrl(url: mint.url)
            let wallet = try await repo.getWallet(mintUrl: mintUrl, unit: .sat)
            AppLogger.wallet.info(
                "wallet-op native-call kind=meltQuote resource=\(WalletOperationCoordinator.privacySafeIdentifier(mint.url), privacy: .public) method=onchain"
            )
            let quoteOptions = try await wallet.quoteOnchainMeltOptions(
                address: normalizedAddress,
                amount: Amount(value: amount),
                maxFeeAmount: nil
            )

            guard let quoteOption = quoteOptions.first else {
                throw WalletError.networkError("Mint returned no on-chain melt fee options.")
            }

            let quote = try await wallet.selectOnchainMeltQuote(quote: quoteOption)
            return meltQuoteInfo(from: quote, paymentMethod: .onchain, fallbackMintUrl: mint.url)
        }
    }

    /// Candidate order, overflow-safe affordability and last-error propagation are shared
    /// by every payment rail; the closure only creates the rail-specific quote.
    func firstAffordableMeltQuote(
        in candidates: [MintInfo],
        failureMessage: String,
        shouldStopOnError: (Error) -> Bool = { _ in false },
        createQuote: (MintInfo) async throws -> MeltQuoteInfo
    ) async throws -> MeltQuoteInfo {
        var lastError: Error?
        for mint in candidates {
            do {
                let quote = try await createQuote(mint)
                let required = try Self.requiredMeltAmount(amount: quote.amount, feeReserve: quote.feeReserve)
                guard mint.balance >= required else {
                    lastError = NFCPaymentError.insufficientBalance(required: required, available: mint.balance)
                    continue
                }
                return quote
            } catch {
                // Recipient failures are independent of the mint. Surface them
                // immediately so LNURL discovery can choose BIP-353 precisely.
                if shouldStopOnError(error) { throw error }
                lastError = error
                AppLogger.wallet.error(
                    "melt quote failed resource=\(WalletOperationCoordinator.privacySafeIdentifier(mint.url), privacy: .public) error_type=\(String(reflecting: type(of: error)), privacy: .public)"
                )
            }
        }
        if let lastError { throw lastError }
        throw WalletError.networkError(failureMessage)
    }

    func subscribeToMintQuote(
        quoteId: String,
        paymentMethod: PaymentMethodKind
    ) async throws -> ActiveSubscription? {
        guard let repo = walletRepository() else {
            throw WalletError.notInitialized
        }

        let storedQuote: MintQuote?
        if let database = walletDatabase() {
            storedQuote = try await database.getMintQuote(quoteId: quoteId)
        } else {
            storedQuote = nil
        }

        guard let context = MintQuoteContextPolicy.walletContext(storedQuote: storedQuote) else {
            throw WalletError.networkError(
                "The mint context for this receive request is unavailable. Create a new request and try again."
            )
        }

        let wallet = try await repo.getWallet(
            mintUrl: MintUrl(url: context.mintURL),
            unit: context.unit
        )
        let params = SubscribeParams(kind: paymentMethod.subscriptionKind, filters: [quoteId], id: nil)
        return try await wallet.subscribe(params: params)
    }

    private func meltQuoteCandidateMints(
        paymentMethod: PaymentMethodKind,
        amountless: Bool = false,
        minimumAmount: UInt64?,
        preferredMintURL: String? = nil
    ) -> [MintInfo] {
        let activeMint = getActiveMint()
        let allMints = getMints()
        let mints = allMints.isEmpty
            ? activeMint.map { [$0] } ?? []
            : allMints

        if let preferredMintURL,
           let preferredMint = preferredMeltMint(
               for: preferredMintURL,
               activeMint: activeMint,
               mints: mints
           ) {
            guard preferredMint.canMelt(paymentMethod, amountless: amountless) else {
                return []
            }
            return [preferredMint]
        }

        let compatibleMints = mints.filter { $0.canMelt(paymentMethod, amountless: amountless) }
        guard !compatibleMints.isEmpty else {
            return []
        }

        let affordableCandidates = compatibleMints.filter { mint in
            guard let minimumAmount else { return true }
            return mint.balance >= minimumAmount
        }
        let candidates = affordableCandidates.isEmpty ? compatibleMints : affordableCandidates

        var ordered: [MintInfo] = []
        if let activeMint,
           candidates.contains(where: { $0.id == activeMint.id }) {
            let activeCanCover = minimumAmount.map { activeMint.balance >= $0 } ?? true
            if activeCanCover {
                ordered.append(activeMint)
            }
        }

        if ordered.isEmpty,
           let activeMint,
           candidates.contains(where: { $0.id == activeMint.id }) {
            ordered.append(activeMint)
        }

        ordered.append(contentsOf: candidates
            .filter { candidate in !ordered.contains(where: { $0.id == candidate.id }) }
            .sorted { lhs, rhs in
                if lhs.balance == rhs.balance {
                    return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
                }
                return lhs.balance > rhs.balance
            }
        )

        return ordered
    }

    private func preferredMeltMint(
        for preferredMintURL: String,
        activeMint: MintInfo?,
        mints: [MintInfo]
    ) -> MintInfo? {
        let normalizedPreferredURL = MintURLIdentity.normalized(preferredMintURL)
        if let mint = mints.first(where: {
            MintURLIdentity.normalized($0.url) == normalizedPreferredURL
        }) {
            return mint
        }

        guard let activeMint,
              MintURLIdentity.normalized(activeMint.url) == normalizedPreferredURL else {
            return nil
        }

        return activeMint
    }

    /// Outcome of a melt confirmation. Pending results are persisted and
    /// reconciled by the manager's serialized foreground poll.
    struct MeltConfirmation {
        let result: MeltPaymentResult
        /// Set when a lightning settlement wait outlived its cap: the
        /// still-running `PendingMelt.wait()` task (Swift's CDK bindings can't
        /// cancel a Rust future, so the racer abandons it rather than stopping
        /// it). The manager observes it to refresh balance/history the moment
        /// settlement lands; durable reconciliation stays with the coordinated
        /// foreground poll.
        var deferredSettlement: Task<FinalizedMelt, any Error>? = nil
    }

    /// Ceiling on how long a lightning melt may hold the repository lane while
    /// CDK's `PendingMelt.wait()` drives settlement. A fallback exit, not a
    /// polling knob — `wait()` polls the mint internally; this only bounds how
    /// long the send screen (and the exclusive wallet lane) stay committed
    /// before the pending face takes over. Kept under the coordinator's 30s
    /// log-only watchdog plus the confirm round-trips already spent.
    private enum MeltSettlementWait {
        static let cap: Duration = .seconds(30)
    }

    /// How a bounded settlement wait ended.
    enum BoundedMeltWaitOutcome {
        case finalized(FinalizedMelt)
        case failed(any Error)
        /// The cap fired first. The wait task keeps running — see
        /// `MeltConfirmation.deferredSettlement` — and becomes the post-cap
        /// settlement watcher (the shape Android has always run in
        /// `watchPendingMelt`).
        case capExpired(residual: Task<FinalizedMelt, any Error>)
    }

    /// Race CDK's settlement wait against a cap. No app-side polling — the
    /// injected `wait` (CDK's `PendingMelt.wait()`) polls internally; the cap
    /// exists only so a genuinely stuck payment (a held HTLC can hang for
    /// minutes) hands the screen back to the pending face instead of pinning
    /// the wallet lane. Injected as a closure so tests can drive all three
    /// outcomes without a CDK runtime.
    func awaitMeltSettlementBounded(
        cap: Duration,
        wait: @escaping () async throws -> FinalizedMelt
    ) async -> BoundedMeltWaitOutcome {
        // Both racers inherit this service's MainActor isolation, so the gate
        // is serialized; it exists because both will reach it.
        final class ResumeGate { var resumed = false }
        let gate = ResumeGate()
        activeMeltSettlementWaits += 1
        let waitTask = Task {
            defer { activeMeltSettlementWaits -= 1 }
            return try await wait()
        }
        return await withCheckedContinuation { continuation in
            func resumeOnce(_ outcome: BoundedMeltWaitOutcome) {
                guard !gate.resumed else { return }
                gate.resumed = true
                continuation.resume(returning: outcome)
            }
            Task {
                do {
                    let finalized = try await waitTask.value
                    resumeOnce(.finalized(finalized))
                } catch {
                    resumeOnce(.failed(error))
                }
            }
            Task {
                try? await Task.sleep(for: cap)
                resumeOnce(.capExpired(residual: waitTask))
            }
        }
    }

    /// Terminal-melt mapping shared by the immediate `.paid` confirmation and a
    /// settlement wait that finished within the cap.
    private func settledMeltConfirmation(
        from finalized: FinalizedMelt,
        mintURLString: String
    ) -> MeltConfirmation {
        MeltConfirmation(
            result: MeltPaymentResult(
                preimage: finalized.preimage,
                amount: finalized.amount.value,
                feePaid: finalized.feePaid.value,
                mintUrl: mintURLString,
                settlement: .settled
            )
        )
    }

    /// Pending result built from the stored quote's numbers. Amount and fee
    /// aren't final until the payment settles, so the fee is the reserve upper
    /// bound — the UI still gets facts to show. CDK reports input fees inside
    /// the settled fee, so a melt that planned one adds it to the bound.
    private func pendingMeltConfirmation(
        storedMeltQuote: MeltQuote?,
        mintURLString: String,
        inputFee: UInt64 = 0,
        deferredSettlement: Task<FinalizedMelt, any Error>? = nil
    ) -> MeltConfirmation {
        MeltConfirmation(
            result: MeltPaymentResult(
                preimage: nil,
                amount: storedMeltQuote?.amount.value ?? 0,
                feePaid: (storedMeltQuote?.feeReserve.value ?? 0) + inputFee,
                mintUrl: mintURLString,
                settlement: .pending
            ),
            deferredSettlement: deferredSettlement
        )
    }

    /// Pay a Lightning invoice or on-chain address (melt tokens)
    /// - Parameter quoteId: The quote ID to melt
    /// - Returns: Melt confirmation, including whether settlement is pending.
    func meltTokens(
        quoteId: String,
        mintUrl preferredMintUrl: String? = nil,
        selection: MeltProofSelection = .automatic
    ) async throws -> MeltConfirmation {
        guard let repo = walletRepository() else {
            throw WalletError.notInitialized
        }

        isLoading = true
        defer { isLoading = false }

        let storedMeltQuote = try await walletDatabase()?.getMeltQuote(quoteId: quoteId)
        guard let mintURLString = preferredMintUrl ?? storedMeltQuote?.mintUrl?.url ?? getActiveMint()?.url else {
            throw WalletError.notInitialized
        }
        let mintUrl = MintUrl(url: mintURLString)
        let wallet = try await repo.getWallet(mintUrl: mintUrl, unit: .sat)

        let preparedMelt: PreparedMelt
        do {
            switch selection {
            case .automatic:
                preparedMelt = try await wallet.prepareMelt(quoteId: quoteId)
            case .allUnspentSkippingSwap:
                let proofs = try await wallet.getProofsByStates(states: [.unspent])
                preparedMelt = try await wallet.prepareMeltProofs(quoteId: quoteId, proofs: proofs)
            }
        } catch {
            // Preparation can reserve proofs before its native future reports an
            // error. If CDK persisted an operation, resolve it exactly like an
            // interrupted confirmation instead of treating the quote as reusable.
            guard let database = walletDatabase() else {
                throw MeltPaymentRecoveryError.unresolved(
                    quoteID: quoteId,
                    mintURL: mintURLString,
                    operationID: "unknown"
                )
            }
            var reservedOperationID: String?
            do {
                let reservedQuote = try await database.getMeltQuote(quoteId: quoteId)
                reservedOperationID = reservedQuote?.usedByOperation
            } catch {
                // A failed read cannot establish that prepareMelt left no
                // reservation. Keep the outcome ambiguous and block a
                // potentially duplicate retry until status can be checked.
                throw MeltPaymentRecoveryError.unresolved(
                    quoteID: quoteId,
                    mintURL: mintURLString,
                    operationID: "unknown"
                )
            }
            guard let operationID = reservedOperationID else {
                throw error
            }
            return try await resolveMeltAfterAmbiguousFailure(
                wallet: wallet,
                quoteId: quoteId,
                mintURLString: mintURLString,
                operationID: operationID,
                fallbackQuote: storedMeltQuote
            )
        }

        let operationID = preparedMelt.operationId()
        AppLogger.wallet.info(
            "wallet-op melt prepared operation=\(WalletOperationCoordinator.privacySafeIdentifier(operationID), privacy: .public) quote=\(WalletOperationCoordinator.privacySafeIdentifier(quoteId), privacy: .public)"
        )

        if case .allUnspentSkippingSwap(let expectedInputFee) = selection {
            let inputFee = preparedMelt.inputFeeWithoutSwap().value
            if inputFee != expectedInputFee {
                do {
                    try await preparedMelt.cancel()
                } catch {
                    // The reservation outlived a failed cancel, so the quote is
                    // not reusable until recovery has released it.
                    return try await resolveMeltAfterAmbiguousFailure(
                        wallet: wallet,
                        quoteId: quoteId,
                        mintURLString: mintURLString,
                        operationID: operationID,
                        fallbackQuote: storedMeltQuote
                    )
                }
                throw MeltInputFeeChanged(expected: expectedInputFee, actual: inputFee)
            }
        }

        do {
            AppLogger.wallet.info(
                "wallet-op native-call kind=melt phase=confirm operation=\(WalletOperationCoordinator.privacySafeIdentifier(operationID), privacy: .public) quote=\(WalletOperationCoordinator.privacySafeIdentifier(quoteId), privacy: .public)"
            )
            if case .allUnspentSkippingSwap(let inputFee) = selection {
                // No respond-async confirmation takes options, so the
                // confirmation itself is the bounded wait. If the cap fires the
                // native call keeps running as the settlement watcher; it is
                // never cancelled mid-payment.
                return try await boundedMeltSettlement(
                    wallet: wallet,
                    quoteId: quoteId,
                    mintURLString: mintURLString,
                    operationID: operationID,
                    storedMeltQuote: storedMeltQuote,
                    inputFee: inputFee,
                    wait: { try await preparedMelt.confirmWithOptions(options: MeltConfirmOptions(skipSwap: true)) }
                )
            }
            switch try await preparedMelt.confirmPreferAsync() {
            case .paid(let finalized):
                return settledMeltConfirmation(from: finalized, mintURLString: mintURLString)
            case .pending(let pendingMelt):
                // A lightning melt settles in seconds in the healthy case, so
                // stay in the lane and let CDK's `wait()` drive the saga to a
                // terminal state — it polls the mint internally; the app adds
                // no polling of its own. The old rule ("never run wait() after
                // leaving the repository lane") still holds: this wait runs
                // *inside* the lease, and the only wait that outlives it is
                // the post-cap residual, which is the sanctioned settlement
                // watcher. On-chain melts genuinely take minutes and keep the
                // immediate pending path.
                let method = storedMeltQuote?.paymentMethod
                if method == .bolt11 || method == .bolt12 {
                    return try await boundedMeltSettlement(
                        wallet: wallet,
                        quoteId: quoteId,
                        mintURLString: mintURLString,
                        operationID: operationID,
                        storedMeltQuote: storedMeltQuote,
                        wait: { try await pendingMelt.wait() }
                    )
                }
                return pendingMeltConfirmation(
                    storedMeltQuote: storedMeltQuote,
                    mintURLString: mintURLString
                )
            }
        } catch {
            AppLogger.wallet.warning(
                "wallet-op melt confirmation returned error operation=\(WalletOperationCoordinator.privacySafeIdentifier(operationID), privacy: .public) quote=\(WalletOperationCoordinator.privacySafeIdentifier(quoteId), privacy: .public) error_type=\(String(reflecting: type(of: error)), privacy: .public)"
            )
            return try await resolveMeltAfterAmbiguousFailure(
                wallet: wallet,
                quoteId: quoteId,
                mintURLString: mintURLString,
                operationID: operationID,
                fallbackQuote: storedMeltQuote
            )
        }
    }

    /// Drive a lightning settlement to a result within the cap, mapping the
    /// three ways the wait can end.
    private func boundedMeltSettlement(
        wallet: Wallet,
        quoteId: String,
        mintURLString: String,
        operationID: String,
        storedMeltQuote: MeltQuote?,
        inputFee: UInt64 = 0,
        wait: @escaping () async throws -> FinalizedMelt
    ) async throws -> MeltConfirmation {
        switch await awaitMeltSettlementBounded(cap: MeltSettlementWait.cap, wait: wait) {
        case .finalized(let finalized) where finalized.state == .paid || finalized.state == .issued:
            AppLogger.wallet.info(
                "wallet-op melt settled via wait operation=\(WalletOperationCoordinator.privacySafeIdentifier(operationID), privacy: .public)"
            )
            return settledMeltConfirmation(from: finalized, mintURLString: mintURLString)
        case .finalized, .failed:
            // A wait that ends any other way carries the same ambiguity as a
            // thrown confirmation: resolve it against the mint through the
            // recovery path.
            return try await resolveMeltAfterAmbiguousFailure(
                wallet: wallet,
                quoteId: quoteId,
                mintURLString: mintURLString,
                operationID: operationID,
                fallbackQuote: storedMeltQuote
            )
        case .capExpired(let residual):
            AppLogger.wallet.info(
                "wallet-op melt wait cap expired operation=\(WalletOperationCoordinator.privacySafeIdentifier(operationID), privacy: .public)"
            )
            return pendingMeltConfirmation(
                storedMeltQuote: storedMeltQuote,
                mintURLString: mintURLString,
                inputFee: inputFee,
                deferredSettlement: residual
            )
        }
    }

    // MARK: - Transfers between held mints

    /// Quote the largest amount `sourceMintURL` can move to `destinationMintURL`
    /// in one Lightning payment: a BOLT11 mint quote at the destination and the
    /// melt quote at the source that pays it.
    ///
    /// CDK probes to find that amount, and every probe leaves a quote at both
    /// mints that cannot be cancelled. Call this once for an explicit request,
    /// never speculatively. CDK keeps only the returned pair locally.
    func createMaxCrossMintQuotes(
        sourceMintURL: String,
        destinationMintURL: String
    ) async throws -> CrossMintQuotes {
        guard let repo = walletRepository() else {
            throw WalletError.notInitialized
        }

        isLoading = true
        defer { isLoading = false }

        let source = try await repo.getWallet(mintUrl: MintUrl(url: sourceMintURL), unit: .sat)
        let destination = try await repo.getWallet(mintUrl: MintUrl(url: destinationMintURL), unit: .sat)
        AppLogger.wallet.info(
            "wallet-op native-call kind=transferQuote resource=\(WalletOperationCoordinator.privacySafeIdentifier(sourceMintURL), privacy: .public)"
        )
        let quotes = try await source.crossMintTransferQuoteMax(targetWallet: destination)
        return CrossMintQuotes(
            mintQuote: mintQuoteInfo(from: quotes.mintQuote, fallbackAmount: nil, paymentMethod: .bolt11),
            meltQuote: meltQuoteInfo(from: quotes.meltQuote, paymentMethod: .bolt11, fallbackMintUrl: sourceMintURL),
            inputFee: quotes.inputFee.value
        )
    }

    /// Forget a quote pair the user backed out of, so the destination quote
    /// stops reading as an invoice awaiting payment. Returns false, and removes
    /// nothing, once there is any local evidence the melt started: from then on
    /// the destination quote is the only handle on money that may be in flight.
    /// A failed or compensated melt still counts as started — the wallet can
    /// believe a payment failed that the mint went on to make.
    @discardableResult
    func removeUnusedQuotes(mintQuoteID: String, meltQuoteID: String?) async -> Bool {
        guard let database = walletDatabase() else { return false }
        do {
            if let meltQuoteID {
                if let meltQuote = try await database.getMeltQuote(quoteId: meltQuoteID) {
                    guard meltQuote.state == .unpaid, meltQuote.usedByOperation == nil else { return false }
                }
                let attempts = try await database.listTransactions(mintUrl: nil, direction: .outgoing, unit: nil)
                guard !attempts.contains(where: { $0.quoteId == meltQuoteID }) else { return false }
            }
            if let mintQuote = try await database.getMintQuote(quoteId: mintQuoteID) {
                guard mintQuote.amountPaid.value == 0,
                      mintQuote.amountIssued.value == 0,
                      mintQuote.usedByOperation == nil else { return false }
                try await database.removeMintQuote(quoteId: mintQuoteID)
            }
            if let meltQuoteID {
                try await database.removeMeltQuote(quoteId: meltQuoteID)
            }
            return true
        } catch {
            AppLogger.wallet.error(
                "unused quote removal failed resource=\(WalletOperationCoordinator.privacySafeIdentifier(mintQuoteID), privacy: .public) error_type=\(String(reflecting: type(of: error)), privacy: .public)"
            )
            return false
        }
    }

    /// Whether local state shows a melt is over and did not pay: the quote is
    /// unpaid, no operation holds it, and no attempt is still pending. False
    /// whenever that cannot be established.
    func meltEndedUnpaid(quoteID: String?) async -> Bool {
        guard let quoteID, let database = walletDatabase() else { return false }
        do {
            guard let quote = try await database.getMeltQuote(quoteId: quoteID),
                  quote.state == .unpaid,
                  quote.usedByOperation == nil else { return false }
            let attempts = try await database.listTransactions(mintUrl: nil, direction: .outgoing, unit: nil)
            return !attempts.contains { $0.quoteId == quoteID && $0.status == .pending }
        } catch {
            return false
        }
    }

    /// Resolve a post-reservation error through the mint/CDK recovery path. A
    /// paid or pending response becomes a normal result; a compensated response
    /// explicitly requires a fresh quote; an unknowable result is persisted by
    /// the manager and blocks immediate retry.
    private func resolveMeltAfterAmbiguousFailure(
        wallet: Wallet,
        quoteId: String,
        mintURLString: String,
        operationID: String,
        fallbackQuote: MeltQuote?
    ) async throws -> MeltConfirmation {
        let database = walletDatabase()
        await logMeltReservationState(
            wallet: wallet,
            database: database,
            quoteId: quoteId,
            operationID: operationID,
            stage: "before-resolution"
        )

        var checkedQuote: MeltQuote?
        do {
            checkedQuote = try await wallet.checkMeltQuoteStatus(quoteId: quoteId)
        } catch {
            // This is CDK's status-aware saga recovery, not an unconditional
            // reservation release. It may safely leave a still-pending saga in
            // place when the mint cannot establish a terminal outcome.
            let report = try? await wallet.recoverIncompleteSagas()
            AppLogger.wallet.info(
                "wallet-op melt recovery operation=\(WalletOperationCoordinator.privacySafeIdentifier(operationID), privacy: .public) recovered=\(report?.recovered ?? 0, privacy: .public) compensated=\(report?.compensated ?? 0, privacy: .public) skipped=\(report?.skipped ?? 0, privacy: .public) failed=\(report?.failed ?? 0, privacy: .public)"
            )
            checkedQuote = try? await wallet.checkMeltQuoteStatus(quoteId: quoteId)
        }

        await logMeltReservationState(
            wallet: wallet,
            database: database,
            quoteId: quoteId,
            operationID: operationID,
            stage: "after-resolution"
        )

        guard let checkedQuote else {
            throw MeltPaymentRecoveryError.unresolved(
                quoteID: quoteId,
                mintURL: mintURLString,
                operationID: operationID
            )
        }

        switch checkedQuote.state {
        case .paid, .issued:
            let transaction = try? await wallet.listTransactions(direction: .outgoing)
                .last(where: { $0.quoteId == quoteId })
            return MeltConfirmation(
                result: MeltPaymentResult(
                    preimage: transaction?.paymentProof ?? checkedQuote.paymentProof,
                    amount: transaction?.amount.value
                        ?? (checkedQuote.amount.value > 0 ? checkedQuote.amount.value : fallbackQuote?.amount.value ?? 0),
                    feePaid: transaction?.fee.value
                        ?? (checkedQuote.feeReserve.value > 0 ? checkedQuote.feeReserve.value : fallbackQuote?.feeReserve.value ?? 0),
                    mintUrl: mintURLString,
                    settlement: .settled
                )
            )
        case .pending:
            return MeltConfirmation(
                result: MeltPaymentResult(
                    preimage: nil,
                    amount: checkedQuote.amount.value > 0
                        ? checkedQuote.amount.value
                        : fallbackQuote?.amount.value ?? 0,
                    feePaid: checkedQuote.feeReserve.value > 0
                        ? checkedQuote.feeReserve.value
                        : fallbackQuote?.feeReserve.value ?? 0,
                    mintUrl: mintURLString,
                    settlement: .pending
                )
            )
        case .unpaid:
            guard let database else {
                throw MeltPaymentRecoveryError.unresolved(
                    quoteID: quoteId,
                    mintURL: mintURLString,
                    operationID: operationID
                )
            }

            do {
                let storedQuote = try await database.getMeltQuote(quoteId: quoteId)
                let saga = try await database.getSaga(id: operationID)
                let reservedProofs = try await database.getReservedProofs(operationId: operationID)
                guard saga == nil,
                      storedQuote?.usedByOperation == nil,
                      reservedProofs.isEmpty else {
                    throw MeltPaymentRecoveryError.unresolved(
                        quoteID: quoteId,
                        mintURL: mintURLString,
                        operationID: operationID
                    )
                }
            } catch let recoveryError as MeltPaymentRecoveryError {
                throw recoveryError
            } catch {
                // "Unpaid" only becomes definitely retryable after local
                // saga and quote-reservation reads both succeed and show that
                // compensation completed. An I/O error is not that evidence.
                throw MeltPaymentRecoveryError.unresolved(
                    quoteID: quoteId,
                    mintURL: mintURLString,
                    operationID: operationID
                )
            }
            throw MeltPaymentRecoveryError.compensated(operationID: operationID)
        }
    }

    private func logMeltReservationState(
        wallet: Wallet,
        database: WalletSqliteDatabase?,
        quoteId: String,
        operationID: String,
        stage: String
    ) async {
        var incompleteSagaCount = -1
        var sagaExists = false
        var reservedProofCount = -1
        var quoteReserved = false
        if let database {
            incompleteSagaCount = (try? await database.getIncompleteSagas().count) ?? -1
            sagaExists = ((try? await database.getSaga(id: operationID)) ?? nil) != nil
            reservedProofCount = (try? await database.getReservedProofs(operationId: operationID).count) ?? -1
            quoteReserved = (try? await database.getMeltQuote(quoteId: quoteId))?.usedByOperation != nil
        }
        let reservedBalance = (try? await wallet.totalReservedBalance().value) ?? UInt64.max
        let pendingSendCount = (try? await wallet.getPendingSends().count) ?? -1

        AppLogger.wallet.info(
            "wallet-op melt state stage=\(stage, privacy: .public) operation=\(WalletOperationCoordinator.privacySafeIdentifier(operationID), privacy: .public) incomplete_sagas=\(incompleteSagaCount, privacy: .public) saga_exists=\(sagaExists, privacy: .public) quote_reserved=\(quoteReserved, privacy: .public) reserved_proofs=\(reservedProofCount, privacy: .public) reserved_balance=\(reservedBalance, privacy: .public) pending_sends=\(pendingSendCount, privacy: .public)"
        )
    }

    private func mintQuoteInfo(
        from quote: MintQuote,
        fallbackAmount: UInt64?,
        paymentMethod: PaymentMethodKind
    ) -> MintQuoteInfo {
        let resolvedAmount = quote.amount?.value
            ?? (quote.amountPaid.value > 0 ? quote.amountPaid.value : nil)
            ?? fallbackAmount

        // Reusable BOLT12 offers (amountless or fixed) have no CDK creation field;
        // the amountless one is also reused across opens. Stamp the first time we
        // materialize each offer, then read it back so the "Created" row stays put.
        // Keyed by quote id, so a fixed offer minted from the Amount pencil gets
        // its own stable date.
        let createdAt: Date? = paymentMethod == .bolt12
            ? MintQuoteCreatedAtStore.recordIfAbsent(quoteId: quote.id, date: Date())
            : nil

        return MintQuoteInfo(
            id: quote.id,
            request: quote.request,
            amount: resolvedAmount,
            isAmountless: quote.amount == nil,
            paymentMethod: paymentMethod,
            state: mintQuoteState(from: quote, paymentMethod: paymentMethod),
            expiry: displayExpiry(quote.expiry),
            createdAt: createdAt,
            unit: PaymentRequestDecoder.unitDescription(quote.unit),
            mintURL: quote.mintUrl.url,
            amountPaid: quote.amountPaid.value,
            amountIssued: quote.amountIssued.value,
            updatedAt: quote.updatedAt > 0 ? Date(timeIntervalSince1970: TimeInterval(quote.updatedAt)) : nil
        )
    }

    private func mintQuoteState(
        from quote: MintQuote,
        paymentMethod: PaymentMethodKind
    ) -> MintQuoteState {
        if quote.amountPaid.value > 0, quote.amountIssued.value >= quote.amountPaid.value {
            return .issued
        }

        if quote.amountPaid.value > quote.amountIssued.value {
            return .paid
        }

        guard paymentMethod == .bolt11 else {
            return .pending
        }

        return MintQuoteState(quote.state)
    }

    private func meltQuoteInfo(
        from quote: MeltQuote,
        paymentMethod: PaymentMethodKind,
        fallbackMintUrl: String
    ) -> MeltQuoteInfo {
        MeltQuoteInfo(
            id: quote.id,
            mintUrl: quote.mintUrl?.url ?? fallbackMintUrl,
            amount: quote.amount.value,
            feeReserve: quote.feeReserve.value,
            paymentMethod: paymentMethod,
            state: MeltQuoteState(quote.state),
            expiry: displayExpiry(quote.expiry)
        )
    }

    private func displayExpiry(_ expiry: UInt64) -> UInt64? {
        guard expiry != QuoteExpiry.never,
              expiry != QuoteExpiry.localNeverExpiresSentinel else {
            return nil
        }

        return expiry
    }

    private func persistMintQuoteIfNeeded(
        _ quote: MintQuote,
        paymentMethod: PaymentMethodKind
    ) async {
        let normalizedQuote = mintQuoteForLocalStorage(quote, paymentMethod: paymentMethod)
        guard normalizedQuote.expiry != quote.expiry else { return }

        await persistMintQuote(normalizedQuote)
    }

    private func persistMintQuote(
        _ quote: MintQuote,
        paymentMethod: PaymentMethodKind,
        fallbackAmount: UInt64? = nil
    ) async {
        await persistMintQuote(
            mintQuoteForLocalStorage(
                quote,
                paymentMethod: paymentMethod,
                fallbackAmount: fallbackAmount
            )
        )
    }

    private func persistMintQuote(_ quote: MintQuote) async {
        do {
            guard let walletDatabase = walletDatabase() else { return }
            try await replaceStoredMintQuote(quote, in: walletDatabase)
        } catch {
            AppLogger.wallet.error(
                "mint quote persistence failed resource=\(WalletOperationCoordinator.privacySafeIdentifier(quote.id), privacy: .public) error_type=\(String(reflecting: type(of: error)), privacy: .public)"
            )
        }
    }

    private func mintQuoteForLocalStorage(
        _ quote: MintQuote,
        paymentMethod: PaymentMethodKind,
        fallbackAmount: UInt64? = nil
    ) -> MintQuote {
        let expiry = paymentMethod == .bolt12 && quote.expiry == QuoteExpiry.never
            ? QuoteExpiry.localNeverExpiresSentinel
            : quote.expiry

        let amount = normalizedMintQuoteAmount(
            for: quote,
            paymentMethod: paymentMethod,
            fallbackAmount: fallbackAmount
        )

        guard expiry != quote.expiry || amount?.value != quote.amount?.value else {
            return quote
        }

        return MintQuote(
            id: quote.id,
            amount: amount,
            unit: quote.unit,
            request: quote.request,
            state: quote.state,
            expiry: expiry,
            mintUrl: quote.mintUrl,
            amountIssued: quote.amountIssued,
            amountPaid: quote.amountPaid,
            updatedAt: quote.updatedAt,
            estimatedBlocks: quote.estimatedBlocks,
            paymentMethod: quote.paymentMethod,
            secretKey: quote.secretKey,
            usedByOperation: quote.usedByOperation,
            version: quote.version
        )
    }

    private func normalizedMintQuoteAmount(
        for quote: MintQuote,
        paymentMethod: PaymentMethodKind,
        fallbackAmount: UInt64?
    ) -> Amount? {
        guard paymentMethod == .onchain, quote.amount == nil else {
            return quote.amount
        }

        if quote.amountPaid.value > 0 {
            return Amount(value: quote.amountPaid.value)
        }

        if quote.amountIssued.value > 0 {
            return Amount(value: quote.amountIssued.value)
        }

        if let fallbackAmount, fallbackAmount > 0 {
            return Amount(value: fallbackAmount)
        }

        return nil
    }

    func replaceStoredMintQuote(
        _ quote: MintQuote,
        in walletDatabase: WalletSqliteDatabase
    ) async throws {
        // While the repository lease is held, reject stale app projections
        // and reservation changes instead of overwriting CDK's recovery state.
        if let current = try await walletDatabase.getMintQuote(quoteId: quote.id) {
            guard quote.version >= current.version,
                  quote.usedByOperation == current.usedByOperation else {
                throw WalletError.networkError("This receive request changed. Refresh it and try again.")
            }
        }
        try await walletDatabase.addMintQuote(quote: quote)
    }

    /// CDK persists status updates but returns the pre-write version. Adopt
    /// its stored row, and write only app metadata that actually changes.
    /// Database failures must not fall back to stale recovery ownership.
    func storedMintQuoteAfterStatusCheck(
        quoteId: String,
        paymentMethod: PaymentMethodKind,
        fallbackAmount: UInt64?
    ) async throws -> MintQuote {
        guard let database = walletDatabase() else { throw WalletError.notInitialized }
        guard let storedQuote = try await database.getMintQuote(quoteId: quoteId) else {
            throw WalletError.networkError("This receive request is no longer available.")
        }
        let normalizedQuote = mintQuoteForLocalStorage(
            storedQuote, paymentMethod: paymentMethod, fallbackAmount: fallbackAmount
        )
        if normalizedQuote.amount != storedQuote.amount || normalizedQuote.expiry != storedQuote.expiry {
            try await replaceStoredMintQuote(normalizedQuote, in: database)
            guard let persisted = try await database.getMintQuote(quoteId: quoteId) else {
                throw WalletError.networkError("This receive request is no longer available.")
            }
            return persisted
        }
        return storedQuote
    }

    private func refreshStoredOnchainMintQuoteStatus(
        _ existingQuote: MintQuote,
        fallbackAmount: UInt64?
    ) async throws -> MintQuote {
        guard let repo = walletRepository(), let database = walletDatabase() else {
            throw WalletError.notInitialized
        }

        let wallet = try await repo.getWallet(mintUrl: existingQuote.mintUrl, unit: existingQuote.unit)
        let storedQuote = try await MintQuoteRecovery.refresh(
            check: { try await wallet.checkMintQuoteStatus(quoteId: existingQuote.id) },
            reload: { try await database.getMintQuote(quoteId: existingQuote.id) }
        )
        let refreshedQuote = mintQuoteForLocalStorage(
            storedQuote,
            paymentMethod: .onchain,
            fallbackAmount: fallbackAmount
        )

        if refreshedQuote.amount != storedQuote.amount || refreshedQuote.expiry != storedQuote.expiry {
            try await replaceStoredMintQuote(refreshedQuote, in: database)
            guard let persisted = try await database.getMintQuote(quoteId: existingQuote.id) else {
                throw WalletError.networkError("This receive request is no longer available.")
            }
            return persisted
        }
        return storedQuote
    }

    private func createOnchainMintQuote(mintURL: String) async throws -> MintQuoteInfo {
        guard let repo = walletRepository() else {
            throw WalletError.notInitialized
        }

        let mintUrl = MintUrl(url: mintURL)
        let wallet = try await repo.getWallet(mintUrl: mintUrl, unit: .sat)

        let quote = try await wallet.mintQuote(
            paymentMethod: PaymentMethodKind.onchain.cdkMethod,
            amount: nil,
            description: nil,
            extra: "{}"
        )

        await persistMintQuote(quote, paymentMethod: .onchain)

        return mintQuoteInfo(from: quote, fallbackAmount: nil, paymentMethod: .onchain)
    }

    private func bitcoinNetwork(for mintURLString: String) -> BitcoinNetwork {
        guard let host = URL(string: mintURLString)?.host?.lowercased() else {
            return .bitcoin
        }

        if host == "onchain.cashudevkit.org"
            || host.contains("signet")
            || host.contains("mutinynet") {
            return .signet
        }

        if host.contains("regtest") {
            return .regtest
        }

        if host.contains("testnet") {
            return .testnet
        }

        return .bitcoin
    }

}

enum LightningAddressResolver {
    /// Preserve LNURL-first behavior independently of the mint's BOLT12 support.
    static func quote<T>(
        lnurl: () async throws -> T,
        bip353: () async throws -> T
    ) async throws -> T {
        do { return try await lnurl() }
        catch {
            guard case let FfiError.Cdk(_, message) = error,
                  isMissingEndpoint(message) else { throw error }
            return try await bip353()
        }
    }

    // CDK 0.18.2 flattens resolver errors into FFI detail strings. Require the
    // discovery-stage prefix; callback/mint errors must never trigger fallback.
    static func isMissingEndpoint(_ message: String) -> Bool {
        [404, 410].contains { message.hasPrefix("\(discoveryPrefix)\($0)):") }
    }

    static func isRecipientError(_ error: Error) -> Bool {
        guard case let FfiError.Cdk(_, message) = error else { return false }
        return message.hasPrefix("Failed to request invoice from Lightning address service:")
            || message.hasPrefix("Failed to parse Lightning address:")
    }

    private static let discoveryPrefix = "Failed to request invoice from Lightning address service: Failed to fetch pay request data: Http transport error Some("
}

private extension PaymentMethodKind {
    var subscriptionKind: SubscriptionKind {
        switch self {
        case .bolt11:
            return .bolt11MintQuote
        case .bolt12:
            return .bolt12MintQuote
        case .onchain:
            return .onchainMintQuote
        }
    }
}
