import Foundation

extension WalletManager {
    // MARK: - Transfers between held mints

    /// Quote moving `amount` from one held mint to another. `amount` is what
    /// arrives; the source pays it plus the fee reserve.
    ///
    /// The quotes exist at both mints from here on. Pass the plan to
    /// `executeMintTransfer`, or to `discardMintTransferPlan` if the user backs
    /// out — never just drop it.
    func prepareMintTransfer(
        from sourceMintURL: String,
        to destinationMintURL: String,
        amount: UInt64
    ) async throws -> MintTransferPlan {
        // One lease for both quotes and the record, so a history load cannot
        // run in between and read the destination quote as a stray invoice.
        try await operationCoordinator.perform(kind: .transferQuote, resourceID: sourceMintURL) {
            try await self.mintTransferEngine(holdingLease: true)
                .prepare(from: sourceMintURL, to: destinationMintURL, amount: amount)
        }
    }

    /// Quote the largest amount the source can move to the destination in one
    /// payment. Makes several quotes at both mints to find it, so call it for
    /// an explicit request only.
    func prepareMaxMintTransfer(
        from sourceMintURL: String,
        to destinationMintURL: String
    ) async throws -> MintTransferPlan {
        try await operationCoordinator.perform(kind: .transferQuote, resourceID: sourceMintURL) {
            try await self.mintTransferEngine(holdingLease: true)
                .prepareMax(from: sourceMintURL, to: destinationMintURL)
        }
    }

    /// Run a prepared transfer: pay the destination's invoice from the source,
    /// then issue the ecash at the destination.
    ///
    /// Returns `.settling` when either leg is slow. Nothing more is needed from
    /// the caller then: the pending-quote sweeps finish it, across relaunches.
    func executeMintTransfer(
        _ plan: MintTransferPlan,
        onStage: (MintTransferStage) -> Void = { _ in }
    ) async throws -> MintTransferOutcome {
        mintTransfersInFlight.insert(plan.id)
        defer { mintTransfersInFlight.remove(plan.id) }
        do {
            // The two legs take separate leases; the assertion covers the gap.
            let outcome = try await withBackgroundWriteAssertion("mint-transfer") {
                try await self.mintTransferEngine(holdingLease: false).execute(plan, onStage: onStage)
            }
            SentryService.breadcrumb("Mint transfer \(outcome.breadcrumb)", category: "wallet.transfer")
            return outcome
        } catch {
            // A failed transfer may have removed its quotes or changed its row.
            await loadTransactions()
            throw error
        }
    }

    /// Forget a plan the user backed out of. Safe to call at any point: once
    /// the melt has started it removes nothing.
    func discardMintTransferPlan(_ plan: MintTransferPlan) async {
        await mintTransferEngine(holdingLease: false).discard(plan)
    }

    // MARK: - Maintenance hooks

    /// Whether `quoteID` is the destination quote of a transfer. Its issuance
    /// moves the user's own money, so it is never announced as a payment
    /// received.
    func isMintTransferQuote(_ quoteID: String) -> Bool {
        walletStore.loadMintTransfers().ownedMintQuoteIDs.contains(quoteID)
    }

    func noteMintTransferIssued(quoteID: String) {
        mintTransferEngine(holdingLease: true).noteIssued(quoteID: quoteID)
    }

    /// Drafts left by a previous launch have no review screen any more.
    func discardAbandonedMintTransferDraftsAssumingWalletOperationLease() async {
        await mintTransferEngine(holdingLease: true).discardAbandonedDrafts(before: startedAt)
    }

    func failEndedMintTransfersAssumingWalletOperationLease() async {
        await mintTransferEngine(holdingLease: true).failEndedTransfers(excluding: mintTransfersInFlight)
    }

    /// The engine over this wallet. Local database reads and quote removals go
    /// straight to the service when the caller already holds the wallet
    /// operation lease, and take it otherwise. Melting and issuing always take
    /// their own lease and are only reached without one held.
    private func mintTransferEngine(holdingLease: Bool) -> MintTransferEngine {
        MintTransferEngine(
            mints: { self.mints },
            createMintQuote: { amount, mintURL in
                try await self.lightningService.createMintQuote(
                    amount: amount,
                    method: .bolt11,
                    targetMintURL: mintURL
                )
            },
            createMeltQuote: { request, mintURL in
                try await self.lightningService.createMeltQuote(request: request, preferredMintURL: mintURL)
            },
            createMaxQuotes: { sourceMintURL, destinationMintURL in
                try await self.lightningService.createMaxCrossMintQuotes(
                    sourceMintURL: sourceMintURL,
                    destinationMintURL: destinationMintURL
                )
            },
            melt: { quoteID, mintURL, selection in
                try await self.meltTokens(quoteId: quoteID, mintUrl: mintURL, selection: selection)
            },
            issue: { mintQuoteID in
                await self.refreshPendingMintQuote(quoteId: mintQuoteID, force: true)?.hasSettledPayment == true
            },
            removeUnusedQuotes: { mintQuoteID, meltQuoteID in
                if holdingLease {
                    return await self.lightningService.removeUnusedQuotes(
                        mintQuoteID: mintQuoteID,
                        meltQuoteID: meltQuoteID
                    )
                }
                return (try? await self.operationCoordinator.perform(kind: .transferQuote) {
                    await self.lightningService.removeUnusedQuotes(
                        mintQuoteID: mintQuoteID,
                        meltQuoteID: meltQuoteID
                    )
                }) ?? false
            },
            meltEndedUnpaid: { meltQuoteID in
                await self.lightningService.meltEndedUnpaid(quoteID: meltQuoteID)
            },
            loadRecords: { self.walletStore.loadMintTransfers() },
            saveRecords: { self.walletStore.saveMintTransfers($0) }
        )
    }
}

private extension MintTransferOutcome {
    var breadcrumb: String {
        switch self {
        case .completed: "completed"
        case .settling(.payment): "payment settling"
        case .settling(.issuance): "issuance settling"
        }
    }
}
