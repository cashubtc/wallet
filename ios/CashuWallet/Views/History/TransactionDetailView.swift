import SwiftUI

struct TransactionDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var walletManager: WalletManager
    /// Snapshot at open; [transaction] prefers the live wallet row so a
    /// successful open-check can flip Pending → Completed without dismissing.
    private let seed: WalletTransaction
    @ObservedObject var settings = SettingsManager.shared

    @State private var copyButtonText = "Copy"
    @State private var showShareSheet = false

    init(transaction: WalletTransaction) {
        self.seed = transaction
    }

    /// Live row from the wallet when present; falls back to the open-time seed.
    /// After a mint, CDK replaces the pending quote-id row with a new transaction
    /// id that still carries `quoteId` — follow that so status flips in place.
    private var transaction: WalletTransaction {
        walletManager.transactions.resolveForDetail(
            openId: seed.id,
            openQuoteId: seed.quoteId ?? seed.id
        ) ?? seed
    }

    /// Returns the content to display as a QR code.
    private var qrContent: String? {
        if let token = transaction.token { return token }
        if let invoice = transaction.invoice { return invoice }
        return nil
    }

    /// Content for the bottom Copy button. Unlike `qrContent`, this also covers a
    /// *settled* ecash token as a copyable receipt — the string is a record of
    /// what was received/sent even though its proofs are spent. QR and Share stay
    /// gated on `showsQR` so the app never re-presents a spent token as a
    /// scannable/shareable payment artifact; only the passive Copy is extended.
    /// See DESIGN.md → the settled-ecash receipt carve-out.
    private var copyableContent: String? {
        if showsQR { return qrContent }
        if transaction.kind == .ecash, let token = transaction.token { return token }
        return nil
    }

    /// A reusable BOLT12 offer — its bech32 human-readable prefix is `lno`.
    private var isReusableOffer: Bool {
        transaction.invoice?.lowercased().hasPrefix("lno") == true
    }

    /// Whether the stored request is still worth showing as a QR. A record of a
    /// *settled* one-shot invoice shouldn't reoffer a dead payment code, so the QR
    /// (and its Copy / Share) appears only while the content is still actionable.
    private var showsQR: Bool {
        switch transaction.kind {
        case .ecash:
            // Governs the scannable/shareable artifacts (QR hero + top Share).
            // A claimed token is spent, so only an unclaimed (pending) send is
            // still worth re-presenting. The passive Copy button is separate — it
            // extends to settled tokens as a receipt via `copyableContent`.
            // An unclaimed *incoming* token is money to claim, not a payment
            // code to hand out — its detail leads with the Receive button.
            if transaction.isPendingReceiveToken { return false }
            return transaction.token != nil && transaction.status == .pending
        case .lightning:
            guard transaction.invoice != nil else { return false }
            return transaction.status == .pending || isReusableOffer
        case .onchain:
            // An on-chain address stays fundable, so its QR is left as-is.
            return transaction.invoice != nil
        }
    }

    private var qrContentTypeLabel: String {
        switch transaction.kind {
        case .ecash:     return "token"
        case .lightning: return "request"
        case .onchain:   return "address"
        }
    }

    private var qrContentAccessibilityLabel: String {
        switch transaction.kind {
        case .ecash:     return "ecash token"
        case .lightning: return "payment request"
        case .onchain:   return "bitcoin address"
        }
    }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 24) {
                        // Hero: an actionable QR (unclaimed token / pending or
                        // reusable invoice), else a state glyph that bounces in on
                        // open — green check when completed, red X when failed;
                        // nothing while a no-QR transaction is still pending.
                        heroSlot

                        // Amount hero — always crisp `.primary`; the glyph above
                        // carries the state colour.
                        Group {
                            if !isSatUnit {
                                Text(formattedNativeAmount)
                                    .font(.system(size: showsQR ? 32 : 48, weight: .semibold, design: .rounded))
                                    .monospacedDigit()
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.5)
                                    .accessibilityLabel("Amount: \(formattedNativeAmount)")
                            } else if transaction.kind == .onchain {
                                Text(AmountFormatter.sats(transaction.amount, useBitcoinSymbol: settings.useBitcoinSymbol))
                                    .font(.system(size: showsQR ? 32 : 48, weight: .semibold, design: .rounded))
                                    .monospacedDigit()
                                    .lineLimit(1)
                                    .minimumScaleFactor(0.5)
                                    .accessibilityLabel("Amount: \(transaction.amount) sats")
                            } else {
                                CurrencyAmountDisplay(
                                    sats: transaction.amount,
                                    primary: $settings.amountDisplayPrimary,
                                    primarySize: showsQR ? 32 : 48
                                )
                                .accessibilityLabel("Amount: \(transaction.amount) sats")
                            }
                        }
                        .padding(.top, heroSlotIsEmpty ? 32 : 0)

                        // Detail rows on canvas with hairline dividers, led by
                        // Status + Date. Type is omitted — the nav title names it.
                        VStack(spacing: 0) {
                            ForEach(Array(detailRows.enumerated()), id: \.offset) { index, row in
                                detailRow(icon: row.icon, label: row.label, value: row.value)
                                if index < detailRows.count - 1 { canvasDivider }
                            }
                        }
                        .padding(.top, 8)
                        .padding(.horizontal, 4)
                    }
                    .padding(.horizontal)
                }

                if let explorerURL = onchainExplorerURL {
                    Link("View in block explorer", destination: explorerURL)
                        .textLinkButton()
                        .padding(.vertical, 12)
                }

                // Single primary action — Copy. Appears for an actionable
                // artifact (unclaimed token / pending or reusable invoice /
                // on-chain address) and, as a receipt, for a settled ecash token.
                // Share stays top-right in the toolbar, gated on `showsQR`, so a
                // spent token is never re-presented as a shareable payment code.
                // See DESIGN.md → Share-At-Top Rule + settled-ecash receipt carve-out.
                if let content = copyableContent {
                    Button(action: { copyContent(content) }) {
                        Text(copyButtonText)
                    }
                    .glassButton()
                    .padding(.horizontal)
                    .padding(.bottom, 16)
                    .accessibilityLabel(copyButtonText == "Copied" ? "Copied" : "Copy \(qrContentTypeLabel)")
                    .accessibilityHint("Copies the \(qrContentAccessibilityLabel) to clipboard")
                }
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    SheetCloseButton()
                }
                ToolbarItem(placement: .principal) {
                    Text(transaction.displayTitle).font(.headline)
                }
                if showsQR {
                    ToolbarItem(placement: .topBarTrailing) {
                        Button(action: { showShareSheet = true }) {
                            Image(systemName: "square.and.arrow.up")
                                .toolbarIconTapTarget()
                        }
                        .accessibilityLabel("Share")
                    }
                }
            }
            .sheet(isPresented: $showShareSheet) {
                if let token = transaction.token {
                    CashuTokenShareSheet(token: token)
                } else if let invoice = transaction.invoice {
                    ShareSheet(items: [invoice])
                }
            }
            // Single-quote check on open (not the full pending list). Re-checks
            // this mint quote against the mint and mints if already paid —
            // Android TransactionDetailScreen parity.
            .task(id: seed.id) {
                guard let quoteId = seed.mintQuoteIdForStatusRefresh else { return }
                _ = await walletManager.refreshPendingMintQuote(quoteId: quoteId)
            }
        }
    }

    // MARK: - Subviews

    /// The hero above the amount. An actionable request shows its QR; otherwise a
    /// state glyph bounces in on open — green check (completed) / red X (failed),
    /// same size as the payment-success screen. A pending, no-QR tx shows nothing.
    @ViewBuilder
    private var heroSlot: some View {
        if showsQR, let content = qrContent {
            QRCodeView(
                content: content,
                showControls: false,
                // Lightning invoices / Bitcoin addresses are standard QR formats;
                // ecash tokens are long and benefit from UR-animated encoding.
                staticOnly: transaction.kind != .ecash
            )
            .frame(width: 280, height: 280)
            .padding(16)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 20))
            .padding(.top, 8)
            .contextMenu {
                Button(action: { copyContent(content) }) {
                    Label("Copy", systemImage: "doc.on.doc")
                }
                Button(action: { showShareSheet = true }) {
                    Label("Share", systemImage: "square.and.arrow.up")
                }
            }
        } else if transaction.status == .completed {
            // Static glyph — no `.symbolEffect(.bounce)`. This is historical review
            // (a detail screen re-opened often), not the live payment-received moment
            // that owns the bounce (DESIGN.md §6). The status already happened.
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(.green)
                .padding(.top, 24)
                .accessibilityLabel("Completed")
        } else if transaction.status == .failed {
            Image(systemName: "xmark.circle.fill")
                .font(.system(size: 64))
                .foregroundStyle(.red)
                .padding(.top, 24)
                .accessibilityLabel("Failed")
        }
    }

    /// True when the hero renders nothing (a no-QR transaction still pending, or
    /// an expired invoice — deliberately quiet, no glyph), so the amount gets top
    /// breathing room instead of butting against the nav bar.
    private var heroSlotIsEmpty: Bool {
        !showsQR && transaction.isUnsettled
    }

    /// The lifecycle word for the Status row. Direction/rail come from the nav
    /// title, so this only names the state: completed → Claimed/Paid/Confirmed.
    private var statusFieldValue: String {
        switch transaction.status {
        case .completed:
            switch transaction.kind {
            case .ecash:     return "Claimed"
            case .lightning: return "Paid"
            case .onchain:   return "Confirmed"
            }
        case .pending: return "Pending"
        case .failed:  return "Failed"
        case .expired: return "Expired"
        }
    }

    /// A monochrome row glyph for the Status row (row icons are all `.secondary`).
    private var statusFieldIcon: String {
        switch transaction.status {
        case .completed: return "checkmark.circle"
        case .pending:   return "clock"
        case .failed:    return "xmark.circle"
        case .expired:   return "clock.badge.xmark"
        }
    }

    /// Detail rows as data, led by Status + Date, so the hairline interleaving stays
    /// correct as later rows drop out. Unit is gone (`unitLabel` is always BTC/SAT);
    /// the settled Request string is gone (its live form is the QR/Copy). On-chain
    /// keeps Address / Transaction ID (still actionable).
    private var detailRows: [(icon: String, label: String, value: String)] {
        var rows: [(icon: String, label: String, value: String)] = [
            (statusFieldIcon, "Status", statusFieldValue),
            ("calendar", "Date", transaction.date.formatted(date: .abbreviated, time: .shortened)),
        ]
        if transaction.fee > 0 {
            rows.append(("arrow.up.arrow.down", "Fee", formattedNativeFee))
        }
        if transaction.kind == .onchain {
            if let mintUrl = transaction.mintUrl {
                rows.append(("bitcoinsign.bank.building", "Mint", extractMintHost(mintUrl)))
            }
            if let request = transaction.invoice {
                rows.append(("qrcode", "Address", request))
            }
            if let preimage = transaction.preimage {
                rows.append(("checkmark.seal", "Transaction ID", preimage))
            }
        } else {
            if let mintUrl = transaction.mintUrl {
                rows.append(("bitcoinsign.bank.building", "Mint", extractMintHost(mintUrl)))
            }
            if let preimage = transaction.preimage {
                rows.append(("key", "Payment Proof", preimage))
            }
        }
        return rows
    }

    private var isSatUnit: Bool {
        transaction.unit.caseInsensitiveCompare("sat") == .orderedSame
    }

    private var formattedNativeAmount: String {
        CurrencyAmount(
            value: transaction.amount,
            currency: CurrencyRegistry.currency(forMintUnit: transaction.unit)
        ).formatted()
    }

    private var formattedNativeFee: String {
        if isSatUnit { return "\(transaction.fee) sat" }
        return CurrencyAmount(
            value: transaction.fee,
            currency: CurrencyRegistry.currency(forMintUnit: transaction.unit)
        ).formatted()
    }

    private func detailRow(icon: String, label: String, value: String) -> some View {
        HStack {
            Label(label, systemImage: icon)
                .foregroundStyle(.secondary)
            Spacer()
            Text(value)
                .fontWeight(.medium)
                .multilineTextAlignment(.trailing)
                .lineLimit(1)
                .truncationMode(.middle)
                .textSelection(.enabled)
        }
        .font(.subheadline)
        .padding(.horizontal, 4)
        .padding(.vertical, 14)
    }

    private var canvasDivider: some View {
        Rectangle()
            .fill(Color(.separator))
            .frame(height: 0.5)
            .padding(.leading, 28)
    }

    // MARK: - Helpers

    private func extractMintHost(_ url: String) -> String {
        URL(string: url)?.host ?? url
    }

    private var onchainExplorerURL: URL? {
        guard transaction.kind == .onchain else { return nil }
        if let txid = transaction.preimage {
            return OnchainExplorer.transactionWebURL(
                for: txid,
                address: transaction.invoice,
                mintURL: transaction.mintUrl
            )
        }
        guard let address = transaction.invoice else { return nil }
        return OnchainExplorer.addressWebURL(for: address, mintURL: transaction.mintUrl)
    }

    // MARK: - Actions

    private func copyContent(_ content: String) {
        UIPasteboard.general.string = content
        HapticFeedback.notification(.success)
        copyButtonText = "Copied"
        DispatchQueue.main.asyncAfter(deadline: .now() + 3) {
            copyButtonText = "Copy"
        }
    }
}
