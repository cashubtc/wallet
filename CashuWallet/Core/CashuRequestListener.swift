import Foundation
import SwiftUI

/// An incoming NUT-18 payment held for an explicit user decision on the
/// receive screen — because its mint isn't tracked yet (claiming would
/// silently add it) or because auto-claim is disabled.
struct PendingCashuClaimApproval: Identifiable, Equatable {
    /// Gift-wrap event id; doubles as the processed-ids key.
    let id: String
    let tokenString: String
    let requestId: String?
    let mintUrl: String
    /// Sum of the payload's proof amounts, for display in the prompt.
    let amount: UInt64
    let memo: String?
}

/// NUT-18 receive-side listener. Foreground-only: opens a NIP-17 relay subscription
/// at app launch, decrypts gift wraps, parses PaymentRequestPayload from the inner
/// rumor, and forwards to the auto-claim path in WalletManager.
@MainActor
final class CashuRequestListener: ObservableObject {
    static let shared = CashuRequestListener()

    @Published private(set) var isRunning: Bool = false

    /// Payments waiting for the user's claim/decline decision (unknown mint,
    /// or auto-claim disabled).
    @Published private(set) var pendingApprovals: [PendingCashuClaimApproval] = []

    private var client: NostrInboxClient?
    private weak var walletManager: WalletManager?

    // Gift wraps are fetched over a generous fixed lookback window. NIP-59
    // backdates each gift wrap's `created_at` up to ~2 days, so a tight or
    // forward-advancing `since` floor silently drops later payments. We instead
    // re-scan a wide window every start and prevent re-processing by remembering
    // the gift-wrap event ids we've already handled.
    private let lookbackWindow: TimeInterval = 7 * 24 * 60 * 60
    private let processedIdsKey = StorageKeys.cashuRequestsProcessedNIP17Ids
    private let maxProcessedIds = 1000
    private var processedIds: Set<String> = []
    private var processedOrder: [String] = []

    private init() {}

    func attach(walletManager: WalletManager) {
        self.walletManager = walletManager
    }

    func start() async {
        guard !isRunning else { return }
        guard SettingsManager.shared.enablePaymentRequests else {
            AppLogger.wallet.notice("CashuRequestListener: payment requests disabled in settings — not starting")
            return
        }
        let nostr = NostrService.shared
        guard nostr.isInitialized,
              !nostr.publicKeyHex.isEmpty,
              let privHex = nostr.getPrivateKeyHex(),
              let privateKey = Data(hex: privHex) else {
            AppLogger.wallet.error("CashuRequestListener: NostrService not initialized")
            return
        }
        let relays = SettingsManager.shared.nostrRelays
        guard !relays.isEmpty else {
            AppLogger.wallet.error("CashuRequestListener: no Nostr relays configured — cannot receive Cashu Request payments")
            return
        }
        loadProcessedIds()
        let since = Int64(Date().timeIntervalSince1970 - lookbackWindow)

        let pubkeyHex = nostr.publicKeyHex
        let client = NostrInboxClient(
            pubkeyHex: pubkeyHex,
            relays: relays,
            since: since
        ) { [weak self] event in
            await self?.handle(event: event, recipientPrivateKey: privateKey)
        }
        self.client = client
        await client.start()
        isRunning = true
        AppLogger.wallet.notice("CashuRequestListener: started on \(relays.count) relays, pubkey=\(String(pubkeyHex.prefix(8)), privacy: .public), since=\(since)")
    }

    func stop() async {
        guard let client else { return }
        await client.stop()
        self.client = nil
        isRunning = false
    }

    /// Forget processed gift-wrap ids at a wallet boundary. The ids belong to
    /// the previous wallet's Nostr inbox; a new wallet has a new keypair, and a
    /// re-restored seed should re-attempt claims rather than skip them.
    func resetForWalletBoundary() {
        processedIds = []
        processedOrder = []
        pendingApprovals = []
        UserDefaults.standard.removeObject(forKey: processedIdsKey)
    }

    // MARK: - Event handling

    private func handle(event: NostrIncomingEvent, recipientPrivateKey: Data) async {
        guard event.kind == 1059 else { return }
        guard !processedIds.contains(event.id) else { return }
        AppLogger.wallet.notice("CashuRequestListener: gift wrap received id=\(String(event.id.prefix(8)), privacy: .public) createdAt=\(event.createdAt)")

        let rumor: NostrRumor
        do {
            rumor = try NIP17.unwrap(giftWrap: event, recipientPrivateKey: recipientPrivateKey)
        } catch {
            // Not encrypted for us (or an unrelated DM) — it can never succeed,
            // so mark it handled and stop reconsidering it.
            AppLogger.wallet.notice("CashuRequestListener: NIP-17 unwrap failed for \(String(event.id.prefix(8)), privacy: .public): \(String(describing: error), privacy: .public)")
            markProcessed(event.id)
            return
        }
        guard rumor.kind == 14 else {
            markProcessed(event.id)
            return
        }
        switch await tryClaim(rumorContent: rumor.content, eventId: event.id) {
        case .claimed, .unclaimable:
            markProcessed(event.id)
        case .transientFailure, .awaitingApproval:
            break  // leave unmarked so a later run retries / re-offers
        }
    }

    private enum ClaimOutcome {
        case claimed            // redeemed successfully
        case unclaimable        // malformed / un-redeemable payload — never retry
        case transientFailure   // redeem failed (mint/network) — retry later
        case awaitingApproval   // unknown mint — queued for explicit user approval
    }

    private func tryClaim(rumorContent content: String, eventId: String) async -> ClaimOutcome {
        guard let data = content.data(using: .utf8),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return .unclaimable
        }
        // NUT-18 PaymentRequestPayload:
        // { "id": "<request_uuid>", "memo": "...", "mint": "<url>", "unit": "sat", "proofs": [ ... ] }
        let requestId = json["id"] as? String
        guard let mintUrl = json["mint"] as? String,
              let proofs = json["proofs"] as? [[String: Any]] else {
            AppLogger.wallet.notice("CashuRequestListener: malformed PaymentRequestPayload")
            return .unclaimable
        }
        let unit = (json["unit"] as? String) ?? "sat"
        let memo = json["memo"] as? String
        guard let tokenString = buildV3Token(mint: mintUrl, proofs: proofs, unit: unit, memo: memo) else {
            AppLogger.wallet.notice("CashuRequestListener: could not build token from payload")
            return .unclaimable
        }
        guard let walletManager else {
            AppLogger.wallet.error("CashuRequestListener: walletManager not attached")
            return .transientFailure
        }

        // Silent claiming needs both: auto-claim enabled, and a mint the user
        // already trusts (claiming creates a CDK wallet for the mint and adds
        // it to the tracked list — never do that without consent). Everything
        // else queues for an explicit decision on the receive screen.
        let mintKnown = walletManager.isMintKnown(url: mintUrl)
        let autoClaim = SettingsManager.shared.receivePaymentRequestsAutomatically
        guard autoClaim && mintKnown else {
            enqueueApproval(
                PendingCashuClaimApproval(
                    id: eventId,
                    tokenString: tokenString,
                    requestId: requestId,
                    mintUrl: mintUrl,
                    amount: proofsTotalAmount(proofs),
                    memo: memo
                ),
                reason: mintKnown ? "auto-claim off" : "unknown mint"
            )
            return .awaitingApproval
        }

        return await claimNow(tokenString: tokenString, requestId: requestId)
    }

    private func claimNow(tokenString: String, requestId: String?) async -> ClaimOutcome {
        guard let walletManager else {
            AppLogger.wallet.error("CashuRequestListener: walletManager not attached")
            return .transientFailure
        }
        do {
            // A gift wrap can arrive exactly as the app backgrounds; hold a background-task
            // assertion so this SQLite-writing redeem finishes before suspension.
            let amount = try await withBackgroundWriteAssertion("cashu-request-claim") {
                try await walletManager.receiveCashuRequestPayment(
                    tokenString: tokenString,
                    requestId: requestId
                )
            }
            AppLogger.wallet.notice("CashuRequestListener: claimed \(amount) sat for request \(requestId ?? "—", privacy: .public)")
            return .claimed
        } catch {
            AppLogger.wallet.error("CashuRequestListener: redeem failed (will retry): \(String(describing: error), privacy: .public)")
            return .transientFailure
        }
    }

    // MARK: - Unknown-mint approval

    /// Cap so a spammer pushing payments from throwaway mints can't grow the
    /// queue without bound. Overflow events stay unprocessed and are re-offered
    /// on a later scan once the queue drains. Generous because with auto-claim
    /// off every legitimate payment queues here too.
    private static let maxPendingApprovals = 50

    private func enqueueApproval(_ approval: PendingCashuClaimApproval, reason: String) {
        guard !pendingApprovals.contains(where: { $0.id == approval.id }) else { return }
        guard pendingApprovals.count < Self.maxPendingApprovals else {
            AppLogger.wallet.notice("CashuRequestListener: approval queue full — deferring \(String(approval.id.prefix(8)), privacy: .public)")
            return
        }
        AppLogger.wallet.notice("CashuRequestListener: payment from \(approval.mintUrl, privacy: .public) queued for approval (\(reason, privacy: .public))")
        pendingApprovals.append(approval)
    }

    /// User accepted from the receive screen: claim the payment (this adds the
    /// mint to the wallet), then auto-claim any other queued payments from
    /// mints that are now known. Throws so the screen's failure state handles
    /// retry; the approval stays queued and unprocessed until a claim succeeds
    /// or the user declines.
    func claimApproved(_ approval: PendingCashuClaimApproval) async throws -> UInt64 {
        guard let walletManager else {
            throw WalletError.notInitialized
        }
        let amount = try await withBackgroundWriteAssertion("cashu-request-claim") {
            try await walletManager.receiveCashuRequestPayment(
                tokenString: approval.tokenString,
                requestId: approval.requestId
            )
        }
        pendingApprovals.removeAll { $0.id == approval.id }
        markProcessed(approval.id)
        AppLogger.wallet.notice("CashuRequestListener: user approved claim of \(amount) sat from \(approval.mintUrl, privacy: .public)")
        await claimEligibleQueuedApprovals()
        return amount
    }

    /// User declined: drop the payment and never ask about this event again.
    func decline(_ approval: PendingCashuClaimApproval) {
        pendingApprovals.removeAll { $0.id == approval.id }
        markProcessed(approval.id)
        AppLogger.wallet.notice("CashuRequestListener: user declined payment from \(approval.mintUrl, privacy: .public)")
    }

    /// Drain queued approvals that no longer need a decision: auto-claim is on
    /// and the mint is known (the user just approved a payment from that mint,
    /// added the mint manually, or re-enabled auto-claim). No-op in manual
    /// mode — there every payment gets its own confirmation.
    func claimEligibleQueuedApprovals() async {
        guard SettingsManager.shared.receivePaymentRequestsAutomatically else { return }
        guard let walletManager else { return }
        for approval in pendingApprovals where walletManager.isMintKnown(url: approval.mintUrl) {
            pendingApprovals.removeAll { $0.id == approval.id }
            switch await claimNow(tokenString: approval.tokenString, requestId: approval.requestId) {
            case .claimed, .unclaimable:
                markProcessed(approval.id)
            case .transientFailure, .awaitingApproval:
                break
            }
        }
    }

    private func proofsTotalAmount(_ proofs: [[String: Any]]) -> UInt64 {
        proofs.reduce(UInt64(0)) { total, proof in
            let amount = (proof["amount"] as? NSNumber)?.uint64Value ?? 0
            return total &+ amount
        }
    }

    // MARK: - De-duplication

    private func loadProcessedIds() {
        let stored = UserDefaults.standard.stringArray(forKey: processedIdsKey) ?? []
        processedOrder = stored
        processedIds = Set(stored)
    }

    private func markProcessed(_ id: String) {
        guard processedIds.insert(id).inserted else { return }
        processedOrder.append(id)
        if processedOrder.count > maxProcessedIds {
            let overflow = processedOrder.count - maxProcessedIds
            for removed in processedOrder.prefix(overflow) { processedIds.remove(removed) }
            processedOrder.removeFirst(overflow)
        }
        UserDefaults.standard.set(processedOrder, forKey: processedIdsKey)
    }

    /// Build a NUT-00 V3 cashu token string from a mint + proofs payload.
    /// Format: `cashuA` + base64url(no padding)(JSON({token:[{mint, proofs}], unit, memo})).
    private func buildV3Token(mint: String, proofs: [[String: Any]], unit: String?, memo: String?) -> String? {
        var token: [String: Any] = ["token": [["mint": mint, "proofs": proofs]]]
        if let unit { token["unit"] = unit }
        if let memo { token["memo"] = memo }
        guard let data = try? JSONSerialization.data(withJSONObject: token) else { return nil }
        return "cashuA" + Base64URL.encode(data)
    }
}
