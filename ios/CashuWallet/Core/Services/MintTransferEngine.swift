import Foundation

/// Moves ecash between two held mints: a BOLT11 mint quote at the destination,
/// a melt at the source that pays it, then issuance at the destination.
///
/// The wallet operations are injected, so the rules about which quotes may be
/// forgotten and when a transfer counts as finished are testable without a CDK
/// runtime (Android `WalletMintTransferService.kt` parity).
@MainActor
struct MintTransferEngine {
    var mints: () -> [MintInfo]
    var createMintQuote: (_ amount: UInt64, _ mintURL: String) async throws -> MintQuoteInfo
    var createMeltQuote: (_ request: String, _ mintURL: String) async throws -> MeltQuoteInfo
    var createMaxQuotes: (_ sourceMintURL: String, _ destinationMintURL: String) async throws -> CrossMintQuotes
    var melt: (_ quoteID: String, _ mintURL: String, _ selection: MeltProofSelection) async throws -> MeltPaymentResult
    /// Check the destination quote and issue what is outstanding. True once the
    /// quote has been paid and fully issued.
    var issue: (_ mintQuoteID: String) async -> Bool
    /// Remove an unused quote pair. False, removing nothing, once the melt
    /// shows any sign of having started.
    var removeUnusedQuotes: (_ mintQuoteID: String, _ meltQuoteID: String?) async -> Bool
    var meltEndedUnpaid: (_ meltQuoteID: String?) async -> Bool
    var loadRecords: () -> [MintTransferRecord]
    var saveRecords: ([MintTransferRecord]) -> Void
    var now: () -> Date = Date.init
    var makeID: () -> String = { UUID().uuidString }
    var pause: (Duration) async throws -> Void = { try await Task.sleep(for: $0) }

    /// How long a transfer waits for the destination to issue before handing
    /// over to the pending-quote sweeps.
    static let issuanceAttempts = 10
    static let issuanceInterval: Duration = .seconds(2)

    // MARK: - Preparing

    /// Quote moving `amount` from one held mint to another. `amount` is what
    /// arrives; the source pays it plus the fee reserve.
    func prepare(
        from sourceMintURL: String,
        to destinationMintURL: String,
        amount: UInt64
    ) async throws -> MintTransferPlan {
        let (source, destination) = try heldMints(from: sourceMintURL, to: destinationMintURL)
        guard amount > 0 else { throw MintTransferError.nothingToTransfer }

        let mintQuote = try await createMintQuote(amount, destination.url)
        // Recorded before the second quote so a kill in between still leaves
        // something that knows this invoice is not awaiting payment.
        var record = MintTransferRecord(
            id: makeID(),
            sourceMintURL: source.url,
            destinationMintURL: destination.url,
            mintQuoteID: mintQuote.id,
            meltQuoteID: nil,
            amount: amount,
            createdAt: now()
        )
        save(record)

        let meltQuote: MeltQuoteInfo
        do {
            meltQuote = try await createMeltQuote(mintQuote.request, source.url)
        } catch {
            await discard(record)
            if case NFCPaymentError.insufficientBalance(let required, let available) = error {
                throw MintTransferError.insufficientBalance(required: required, available: available)
            }
            throw error
        }
        record.meltQuoteID = meltQuote.id
        save(record)

        let plan = MintTransferPlan(
            id: record.id,
            mode: .exact,
            sourceMintURL: source.url,
            destinationMintURL: destination.url,
            mintQuoteID: mintQuote.id,
            meltQuoteID: meltQuote.id,
            amount: amount,
            feeReserve: meltQuote.feeReserve,
            inputFee: 0,
            selection: .automatic,
            expiresAt: Self.expiry(mintQuote: mintQuote, meltQuote: meltQuote)
        )
        try await validate(plan, record: record, mintQuote: mintQuote, meltQuote: meltQuote)
        return plan
    }

    /// Quote the largest amount the source can move to the destination in one
    /// payment. Makes several quotes at both mints to find it, so call it for
    /// an explicit request only.
    func prepareMax(
        from sourceMintURL: String,
        to destinationMintURL: String
    ) async throws -> MintTransferPlan {
        let (source, destination) = try heldMints(from: sourceMintURL, to: destinationMintURL)
        guard source.balance > 0 else { throw MintTransferError.nothingToTransfer }

        let quotes: CrossMintQuotes
        do {
            quotes = try await createMaxQuotes(source.url, destination.url)
        } catch {
            if error.isInsufficientBalanceError { throw MintTransferError.nothingToTransfer }
            throw error
        }
        guard let amount = quotes.mintQuote.amount, amount > 0 else {
            _ = await removeUnusedQuotes(quotes.mintQuote.id, quotes.meltQuote.id)
            throw MintTransferError.nothingToTransfer
        }
        let record = MintTransferRecord(
            id: makeID(),
            sourceMintURL: source.url,
            destinationMintURL: destination.url,
            mintQuoteID: quotes.mintQuote.id,
            meltQuoteID: quotes.meltQuote.id,
            amount: amount,
            createdAt: now()
        )
        save(record)

        let selection = MintTransferPlan.maxMeltSelection(
            balance: source.balance,
            amount: amount,
            feeReserve: quotes.meltQuote.feeReserve,
            inputFee: quotes.inputFee
        )
        let plan = MintTransferPlan(
            id: record.id,
            mode: .max,
            sourceMintURL: source.url,
            destinationMintURL: destination.url,
            mintQuoteID: quotes.mintQuote.id,
            meltQuoteID: quotes.meltQuote.id,
            amount: amount,
            feeReserve: quotes.meltQuote.feeReserve,
            // An ordinary melt picks its own proofs, so its input fee is not
            // known until it runs — the same as any Lightning payment.
            inputFee: selection == .automatic ? 0 : quotes.inputFee,
            selection: selection,
            expiresAt: Self.expiry(mintQuote: quotes.mintQuote, meltQuote: quotes.meltQuote)
        )
        try await validate(plan, record: record, mintQuote: quotes.mintQuote, meltQuote: quotes.meltQuote)
        return plan
    }

    // MARK: - Executing

    /// Pay the destination's invoice from the source, then issue the ecash at
    /// the destination. Returns `.settling` when either leg is slow; the
    /// pending-quote sweeps then finish it, across relaunches.
    func execute(
        _ plan: MintTransferPlan,
        onStage: (MintTransferStage) -> Void = { _ in }
    ) async throws -> MintTransferOutcome {
        guard !plan.isExpired(at: now()) else {
            if let record = loadRecords().first(where: { $0.id == plan.id }), record.state == .draft {
                await discard(record)
            }
            throw MintTransferError.planExpired
        }
        // Commit before any money moves. From here the destination quote is
        // kept until it issues, whatever happens to this process.
        guard setState(.committed, forID: plan.id, from: .draft) else {
            throw MintTransferError.planStale
        }

        onStage(.paying)
        let payment: MeltPaymentResult
        do {
            payment = try await melt(plan.meltQuoteID, plan.sourceMintURL, plan.selection)
        } catch is MeltInputFeeChanged {
            await settleUnpaid(plan)
            throw MintTransferError.planStale
        } catch let recovery as MeltPaymentRecoveryError {
            if case .compensated = recovery {
                await settleUnpaid(plan)
                throw MintTransferError.paymentReturned
            }
            // Unknown outcome: stay committed so the sweeps keep checking.
            throw recovery
        } catch {
            await settleUnpaid(plan)
            throw error
        }

        guard payment.settlement == .settled else { return .settling(.payment) }

        onStage(.issuing)
        for attempt in 0..<Self.issuanceAttempts {
            if await issue(plan.mintQuoteID) {
                noteIssued(quoteID: plan.mintQuoteID)
                return .completed(amount: plan.amount, feePaid: payment.feePaid)
            }
            guard attempt < Self.issuanceAttempts - 1 else { break }
            do {
                try await pause(Self.issuanceInterval)
            } catch {
                break
            }
        }
        return .settling(.issuance)
    }

    // MARK: - Discarding

    /// Forget a plan the user backed out of. Safe to call at any point: once
    /// the melt has started it removes nothing.
    func discard(_ plan: MintTransferPlan) async {
        guard let record = loadRecords().first(where: { $0.id == plan.id }),
              record.state == .draft else { return }
        await discard(record)
    }

    /// Drafts made before `cutoff` belong to a review screen that no longer
    /// exists. A later draft is a plan on screen and is left alone.
    func discardAbandonedDrafts(before cutoff: Date) async {
        for record in loadRecords() where record.state == .draft && record.createdAt < cutoff {
            await discard(record)
        }
    }

    // MARK: - Maintenance

    /// A committed transfer stops being chased once its melt is seen to have
    /// ended unpaid. Its destination quote then follows the ordinary schedule,
    /// which still checks it until the invoice can no longer be paid.
    func failEndedTransfers(excluding inFlight: Set<String>) async {
        for record in loadRecords() where record.state == .committed && !inFlight.contains(record.id) {
            if await meltEndedUnpaid(record.meltQuoteID) {
                // Only from committed: the quote may have issued meanwhile.
                setState(.failed, forID: record.id, from: .committed)
            }
        }
    }

    /// Note that a transfer's destination quote issued, however that happened —
    /// the transfer itself, or a sweep long after it.
    func noteIssued(quoteID: String) {
        let records = loadRecords()
        guard let record = records.first(where: { $0.mintQuoteID == quoteID }),
              record.state != .completed else { return }
        saveRecords(records.setting(.completed, forID: record.id))
    }

    // MARK: - Internals

    private func save(_ record: MintTransferRecord) {
        saveRecords(loadRecords().upserting(record))
    }

    /// Returns false when the record is missing or, with `from`, not in that
    /// state — so a plan can only be committed once.
    @discardableResult
    private func setState(
        _ state: MintTransferRecord.State,
        forID id: String,
        from expected: MintTransferRecord.State? = nil
    ) -> Bool {
        let records = loadRecords()
        guard let record = records.first(where: { $0.id == id }) else { return false }
        if let expected, record.state != expected { return false }
        saveRecords(records.setting(state, forID: id))
        return true
    }

    /// Remove a draft's quotes and its record. If the quotes cannot be removed
    /// because the melt shows signs of having started, the record is promoted
    /// instead, so the destination quote stays tracked.
    private func discard(_ record: MintTransferRecord) async {
        if await removeUnusedQuotes(record.mintQuoteID, record.meltQuoteID) {
            saveRecords(loadRecords().filter { $0.id != record.id })
        } else {
            setState(.committed, forID: record.id, from: .draft)
        }
    }

    /// The melt ended without paying. If nothing at all was started the plan is
    /// simply forgotten; otherwise it is kept as failed, with its destination
    /// quote still checked until that invoice can no longer be paid.
    private func settleUnpaid(_ plan: MintTransferPlan) async {
        if await removeUnusedQuotes(plan.mintQuoteID, plan.meltQuoteID) {
            saveRecords(loadRecords().filter { $0.id != plan.id })
        } else {
            setState(.failed, forID: plan.id, from: .committed)
        }
    }

    private func heldMints(
        from sourceMintURL: String,
        to destinationMintURL: String
    ) throws -> (source: MintInfo, destination: MintInfo) {
        let held = mints()
        func find(_ url: String) -> MintInfo? {
            let identity = MintURLIdentity.normalized(url)
            return held.first { MintURLIdentity.normalized($0.url) == identity }
        }
        guard let source = find(sourceMintURL), let destination = find(destinationMintURL) else {
            throw WalletError.notInitialized
        }
        if let blocker = MintTransferEligibility.blocker(source: source, destination: destination) {
            throw MintTransferError.notEligible(blocker)
        }
        return (source, destination)
    }

    /// The quotes must describe the transfer that was asked for. A mint that
    /// answers with anything else is not paid.
    private func validate(
        _ plan: MintTransferPlan,
        record: MintTransferRecord,
        mintQuote: MintQuoteInfo,
        meltQuote: MeltQuoteInfo
    ) async throws {
        let mintQuoteMint = mintQuote.mintURL.map(MintURLIdentity.normalized)
        let valid = mintQuote.amount == plan.amount
            && meltQuote.amount == plan.amount
            && mintQuote.unit == "sat"
            && mintQuote.paymentMethod == .bolt11
            && (mintQuoteMint == nil || mintQuoteMint == MintURLIdentity.normalized(plan.destinationMintURL))
            && MintURLIdentity.normalized(meltQuote.mintUrl) == MintURLIdentity.normalized(plan.sourceMintURL)
        guard valid else {
            await discard(record)
            throw MintTransferError.quoteMismatch
        }
        // The native quote calls cannot be cancelled, so a caller that left
        // during them is honoured here.
        guard !Task.isCancelled else {
            await discard(record)
            throw CancellationError()
        }
    }

    private static func expiry(mintQuote: MintQuoteInfo, meltQuote: MeltQuoteInfo) -> Date? {
        let expiries = [mintQuote.expiry, meltQuote.expiry].compactMap { $0 }.filter { $0 > 0 }
        return expiries.min().map { Date(timeIntervalSince1970: TimeInterval($0)) }
    }
}
