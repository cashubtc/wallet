import Foundation
import Cdk

/// Asynchronous melt settlement (NUT-05).
///
/// When a mint accepts a melt with `Prefer: respond-async` — on-chain payments
/// especially can take minutes to settle — `confirmPreferAsync()` hands back a
/// `PendingMelt` instead of a final result. While the app lives, a waiter task
/// subscribes to the quote until it settles. Because iOS can suspend or kill
/// the process mid-wait, every accepted-pending quote is also persisted in
/// `walletStore` and re-checked via `syncPendingMeltQuotes()` on launch and
/// foreground; `checkMeltQuoteStatus` completes the underlying wallet saga
/// (releasing change / compensating proofs) when the mint reports a terminal
/// state. CDK's own `recoverIncompleteSagas()` at startup only single-polls a
/// pending melt and skips it if still pending, so this app-side record is what
/// guarantees the payment's bookkeeping eventually lands.
extension WalletManager {
    /// Wait in the background for an async-accepted melt and finish the
    /// bookkeeping when it settles. One waiter per quote; the waiter dies with
    /// the process and `syncPendingMeltQuotes()` takes over after relaunch.
    func watchPendingMelt(_ pendingMelt: PendingMelt, quoteId: String) {
        guard pendingMeltWaiters[quoteId] == nil else { return }
        // Waiters are cancelled and cleared at wallet boundaries
        // (`resetRuntimeState()`), but `pendingMelt.wait()` is an FFI future
        // that may not observe cancellation promptly — the generation check
        // below is what guarantees a late completion from the old wallet
        // can't touch a newly created/restored one.
        let generation = walletGeneration
        pendingMeltWaiters[quoteId] = Task { [weak self] in
            defer {
                if let self, self.walletGeneration == generation {
                    self.pendingMeltWaiters[quoteId] = nil
                }
            }
            do {
                let finalized = try await pendingMelt.wait()
                guard let self, !Task.isCancelled, self.walletGeneration == generation else { return }
                await self.finishPendingMelt(
                    quoteId: quoteId,
                    state: finalized.state,
                    preimage: finalized.preimage,
                    feePaid: finalized.feePaid.value
                )
            } catch {
                // Leave the quote tracked — syncPendingMeltQuotes retries later.
                AppLogger.wallet.error(
                    "Pending melt wait failed for quote \(quoteId, privacy: .public): \(String(describing: error), privacy: .public)"
                )
            }
        }
    }

    /// Poll mints for melts still recorded as pending — e.g. after a relaunch
    /// killed the in-process waiter. Cheap no-op when nothing is tracked.
    func syncPendingMeltQuotes() async {
        let tracked = walletStore.loadPendingMeltQuotes()
        guard !tracked.isEmpty, let repo = walletRepository else { return }
        // A wallet reset while we're polling must stop this pass: the tracked
        // record belongs to the previous wallet.
        let generation = walletGeneration

        var settledAny = false
        for (quoteId, mintUrlString) in tracked {
            guard walletGeneration == generation else { return }
            // An in-process waiter owns this quote's completion.
            guard pendingMeltWaiters[quoteId] == nil else { continue }
            do {
                let wallet = try await repo.getWallet(mintUrl: MintUrl(url: mintUrlString), unit: .sat)
                let quote = try await wallet.checkMeltQuoteStatus(quoteId: quoteId)
                guard walletGeneration == generation else { return }
                switch quote.state {
                case .paid:
                    recordFinalizedMelt(quoteId: quoteId, preimage: quote.paymentProof, feePaid: nil)
                    forgetPendingMeltQuote(quoteId: quoteId)
                    SentryService.breadcrumb("Async melt settled after resync", category: "wallet.lightning")
                    settledAny = true
                case .unpaid:
                    // Once a mint has accepted async processing, a fall back to
                    // unpaid is terminal: the payment failed and the saga
                    // compensated (proofs returned).
                    forgetPendingMeltQuote(quoteId: quoteId)
                    SentryService.breadcrumb("Async melt failed after resync", category: "wallet.lightning")
                    settledAny = true
                case .pending, .issued:
                    continue
                }
            } catch {
                AppLogger.wallet.error(
                    "Pending melt status check failed for quote \(quoteId, privacy: .public): \(String(describing: error), privacy: .public)"
                )
            }
        }

        if settledAny, walletGeneration == generation {
            await refreshBalance()
            await loadTransactions()
        }
    }

    /// Persist the durable facts of a settled melt (payment proof, actual fee).
    func recordFinalizedMelt(quoteId: String, preimage: String?, feePaid: UInt64?) {
        if let preimage {
            transactionService.savePreimage(quoteId: quoteId, preimage: preimage)
        }
        if let feePaid {
            transactionService.saveMeltFeePaid(quoteId: quoteId, feePaid: feePaid)
        }
    }

    func rememberPendingMeltQuote(quoteId: String, mintUrl: String) {
        var tracked = walletStore.loadPendingMeltQuotes()
        tracked[quoteId] = mintUrl
        walletStore.savePendingMeltQuotes(tracked)
    }

    func forgetPendingMeltQuote(quoteId: String) {
        var tracked = walletStore.loadPendingMeltQuotes()
        guard tracked.removeValue(forKey: quoteId) != nil else { return }
        walletStore.savePendingMeltQuotes(tracked)
    }

    private func finishPendingMelt(
        quoteId: String,
        state: QuoteState,
        preimage: String?,
        feePaid: UInt64
    ) async {
        if state == .paid {
            recordFinalizedMelt(quoteId: quoteId, preimage: preimage, feePaid: feePaid)
            SentryService.breadcrumb("Async melt settled", category: "wallet.lightning")
        } else {
            // Failed payment: the saga already compensated and returned the proofs.
            SentryService.breadcrumb("Async melt failed", category: "wallet.lightning")
        }
        forgetPendingMeltQuote(quoteId: quoteId)
        await refreshBalance()
        await loadTransactions()
    }
}
