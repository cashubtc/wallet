import SwiftUI

/// Ecash that left the wallet and has not been claimed. Shown when it holds
/// back a new send or Cashu Request payment from the same mint, and from
/// History's "Manage pending ecash".
///
/// It is built from the screens on either side of it rather than its own
/// vocabulary. While a token is outstanding it is Send's Pending Ecash screen:
/// the code on its white card, the amount, the facts, and quiet actions pinned
/// underneath. Checking, reclaiming and every settled outcome are the shared
/// payment status screen.
struct PendingSendRecoveryView: View {
    @EnvironmentObject private var walletManager: WalletManager
    @ObservedObject private var settings = SettingsManager.shared
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    @State private var recovery: PendingSendRecovery
    @State private var activity: Activity
    @State private var notice: Notice?
    @State private var reclaimTarget: PendingSend?
    @State private var reclaimed: Reclaimed?
    @State private var sharedToken: SharedToken?
    /// The token on show when several are out, by ID.
    @State private var page: String?
    /// Measured heights of what surrounds a single token's code, so the code
    /// can give way to them on a short screen.
    @State private var headerHeight: CGFloat = 0
    @State private var factsHeight: CGFloat = 0
    let onDone: () -> Void
    var onWorkingChanged: (Bool) -> Void = { _ in }

    private enum Activity { case idle, checking, reclaiming }

    /// Which of the four layouts is up. The two status ones share a
    /// `PaymentStatusView`, so the spinner turns into the check in place.
    private enum Face { case tokens, working, settled, unconfirmed }

    struct Notice: Equatable {
        var title: String?
        let message: String
        let severity: ErrorSeverity

        /// Good news is the app's confirmation toast; only a failure stays
        /// on screen.
        var isToast: Bool { severity != .error }
    }

    /// What a reclaim brought back, for the settled screen's amount and fee.
    private struct Reclaimed: Equatable {
        let amount: UInt64
        let fee: UInt64
    }

    private struct SharedToken: Identifiable {
        let token: String
        var id: String { token }
    }

    init(recovery: PendingSendRecovery, onDone: @escaping () -> Void, onWorkingChanged: @escaping (Bool) -> Void = { _ in }) {
        _recovery = State(initialValue: recovery)
        // An unverified snapshot is checked on arrival, so it opens on the
        // spinner instead of flashing the caution it may never need.
        _activity = State(initialValue: recovery.outcome == .unverified ? .checking : .idle)
        self.onDone = onDone
        self.onWorkingChanged = onWorkingChanged
    }

    private var isWorking: Bool { activity != .idle }

    private var face: Face {
        if activity == .reclaiming { return .working }
        switch recovery.outcome {
        case .pending where !recovery.sends.isEmpty: return .tokens
        case .completed, .returned: return .settled
        case .pending, .unverified: return activity == .checking ? .working : .unconfirmed
        }
    }

    var body: some View {
        // A real container: the faces fade through each other, which a Group
        // at the root of a navigation stack never animates.
        ZStack {
            switch face {
            case .tokens:
                tokensFace
                    .transition(SharedAxis.fadeThrough)
            case .working, .settled:
                statusFace
                    .transition(SharedAxis.fadeThrough)
            case .unconfirmed:
                unconfirmedFace
                    .transition(SharedAxis.fadeThrough)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .task { if recovery.outcome == .unverified { await resolve() } }
        .interactiveDismissDisabled(isWorking)
        .sheet(item: $reclaimTarget) { send in
            ActionConfirmationSheet(
                title: "Reclaim this ecash?",
                message: "If it hasn't been claimed, it returns to your wallet and the recipient can no longer use this token. Mint fees may apply.",
                actionLabel: "Reclaim",
                action: { Task { await resolve(reclaiming: send) } }
            )
        }
        .sheet(item: $sharedToken) { shared in
            CashuTokenShareSheet(token: shared.token)
        }
    }

    // MARK: - Pending Ecash

    private var tokensFace: some View {
        VStack(spacing: 0) {
            GeometryReader { geo in
                ScrollViewReader { scroll in
                    ScrollView {
                        VStack(spacing: Self.blockSpacing) {
                            VStack(spacing: 8) {
                                Text("Pending Ecash")
                                    .font(.title2.weight(.semibold))
                                    .multilineTextAlignment(.center)
                                    .accessibilityAddTraits(.isHeader)
                                Text(hasSeveralSends
                                    ? "You can send from this mint again once these are claimed or reclaimed."
                                    : "You can send from this mint again once this ecash is claimed or reclaimed.")
                                    .font(.callout)
                                    .foregroundStyle(.secondary)
                                    .multilineTextAlignment(.center)
                                    .fixedSize(horizontal: false, vertical: true)
                            }
                            .padding(.horizontal, 32)
                            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { headerHeight = $0 }

                            if hasSeveralSends {
                                tokenPager(qrSize: qrSize(in: geo.size))
                            } else if let send = recovery.sends.first {
                                tokenBlock(send, qrSize: qrSize(in: geo.size))
                                    .padding(.horizontal)
                            }
                        }
                        .padding(.top, Self.contentTopPadding)
                        .padding(.bottom, Self.contentBottomPadding)
                        .frame(maxWidth: .infinity)
                    }
                    .scrollBounceBehavior(.basedOnSize)
                    // A failed check or reclaim lands under the facts. Where
                    // the code can't give way for it (a short screen), it is
                    // brought into view once it is laid out.
                    .onChange(of: notice) { _, notice in
                        guard notice != nil else { return }
                        Task { @MainActor in
                            try? await Task.sleep(for: .milliseconds(50))
                            withAnimation(.smooth(duration: 0.3)) { scroll.scrollTo(Self.noticeID, anchor: .bottom) }
                        }
                    }
                }
            }

            tokenActions
        }
        // A reclaim can take away the token on show; the next one takes its place.
        .onChange(of: recovery.sends.map(\.id)) { _, ids in
            if let page, !ids.contains(page) { self.page = ids.first }
        }
    }

    private static let noticeID = "notice"
    private static let contentTopPadding: CGFloat = 8
    private static let contentBottomPadding: CGFloat = 16
    private static let blockSpacing: CGFloat = 24
    private static let qrCardPadding: CGFloat = 16
    private static let pageDotSize: CGFloat = 7

    /// 280pt of code inside the white card, as on Send and Receive. It gives
    /// up width before it meets the screen edge, and height so the amount and
    /// facts stay on screen above the actions, the way the receipts fit their
    /// code around their rows.
    private func qrSize(in viewport: CGSize) -> CGFloat {
        let widest = min(280, viewport.width - 64)
        let dots = hasSeveralSends ? Self.pageDotSize + Self.blockSpacing : 0
        let around = Self.contentTopPadding + headerHeight + Self.blockSpacing
            + Self.qrCardPadding * 2 + Self.blockSpacing + factsHeight + dots
            + Self.contentBottomPadding
        return max(120, min(widest, viewport.height - around))
    }

    /// Several tokens out: one at a time, swiped sideways, with a dot for each
    /// just under the code, the part that visibly changes. The dots stay put
    /// while the pages move. The actions below act on the token on show.
    private func tokenPager(qrSize: CGFloat) -> some View {
        ScrollView(.horizontal) {
            LazyHStack(alignment: .top, spacing: 0) {
                ForEach(Array(recovery.sends.enumerated()), id: \.element.id) { index, send in
                    tokenBlock(send, qrSize: qrSize)
                        .padding(.horizontal)
                        .containerRelativeFrame(.horizontal)
                        .accessibilityElement(children: .contain)
                        .accessibilityLabel("Token \(index + 1) of \(recovery.sends.count)")
                }
            }
            .scrollTargetLayout()
        }
        .scrollTargetBehavior(.paging)
        .scrollPosition(id: $page)
        .scrollIndicators(.hidden)
        .overlay(alignment: .top) {
            HStack(spacing: 8) {
                ForEach(recovery.sends) { send in
                    Circle()
                        .fill(send.id == shownSend?.id ? Color.primary : Color.secondary.opacity(0.4))
                        .frame(width: Self.pageDotSize, height: Self.pageDotSize)
                }
            }
            .animation(.smooth(duration: 0.2), value: shownSend?.id)
            // In the room each page leaves under its code card.
            .padding(.top, shownSend?.token == nil ? 0 : qrSize + Self.qrCardPadding * 2 + Self.blockSpacing)
            .accessibilityHidden(true)
        }
    }

    /// The token the actions act on: the page on show, or the only one.
    private var shownSend: PendingSend? {
        recovery.sends.first { $0.id == page } ?? recovery.sends.first
    }

    private func tokenBlock(_ send: PendingSend, qrSize: CGFloat) -> some View {
        VStack(spacing: Self.blockSpacing) {
            if let token = send.token {
                PaymentCodeCard(
                    content: token, size: qrSize, staticOnly: false,
                    onCopy: { copy(token) },
                    onShare: { sharedToken = SharedToken(token: token) }
                )
            }

            if hasSeveralSends {
                // Room for the page dots, which sit still over the pager.
                Color.clear.frame(height: Self.pageDotSize)
            }

            tokenFacts(send)
                .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
                    // Only the token on show places the code. Its first
                    // measurement sets it; a later one is a failure notice
                    // arriving, and the code makes room in step.
                    guard send.id == shownSend?.id else { return }
                    if factsHeight == 0 {
                        factsHeight = height
                    } else {
                        withAnimation(.smooth(duration: 0.3)) { factsHeight = height }
                    }
                }
        }
    }

    @ViewBuilder
    private var noticeLine: some View {
        if let notice {
            InlineNotice(message: notice.message, title: notice.title, severity: notice.severity)
                .frame(maxWidth: PaymentDetailMetrics.maxWidth, alignment: .leading)
                .id(Self.noticeID)
                .transition(.opacity)
        }
    }

    private func tokenFacts(_ send: PendingSend) -> some View {
        VStack(spacing: Self.blockSpacing) {
            amount(send)

            if send.token == nil {
                Text("This token can't be shown, but you can still check its status or reclaim it.")
                    .font(.callout)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 16)
            }

            VStack(spacing: 0) {
                if let sent = sentDate(of: send) {
                    PaymentDetailTextRow(label: "Date", value: sent.formatted(date: .abbreviated, time: .shortened))
                }
                PaymentDetailTextRow(label: "Mint", value: mintName)
                if let token = send.token {
                    // The receipts' way with an opaque value: the fixed short
                    // cut, and a tap copies all of it.
                    PaymentMetadataRow(
                        label: "Token", value: PaymentRequestDecoder.middleTruncated(token),
                        symbol: "doc.on.doc", hint: "Copies the ecash token to clipboard"
                    ) { copy(token) }
                }
            }
            .padding(.horizontal, 4)

            if send.id == shownSend?.id {
                noticeLine
            }
        }
    }

    @ViewBuilder
    private func amount(_ send: PendingSend) -> some View {
        if isSat {
            CurrencyAmountDisplay(
                sats: send.amount,
                primary: $settings.amountDisplayPrimary,
                role: .amountCompact
            )
        } else {
            AmountLockup(
                parts: AmountParts.parse(CurrencyAmount(value: send.amount, currency: unitCurrency).formatted()),
                role: .amountCompact,
                value: Double(send.amount)
            )
        }
    }

    private var tokenActions: some View {
        // Stacked at accessibility sizes, as the confirmation sheet's pair is.
        Group {
            if dynamicTypeSize.isAccessibilitySize {
                VStack(spacing: 12) {
                    checkButton
                    reclaimButton
                }
            } else {
                HStack(spacing: 12) {
                    checkButton
                    reclaimButton
                }
            }
        }
        .padding(.horizontal)
        .padding(.bottom, 16)
    }

    private var checkButton: some View {
        Button(action: { Task { await resolve() } }) {
            LoadingButtonLabel(title: "Check Status", isLoading: activity == .checking)
        }
        .flatSheetSecondaryButton()
        .disabled(isWorking)
        .accessibilityIdentifier("cashu.send.check-recovery")
        .accessibilityLabel(activity == .checking ? "Checking status" : "Check Status")
        .accessibilityInputLabels(["Check Status"])
    }

    private var reclaimButton: some View {
        Button("Reclaim") { reclaimTarget = shownSend }
            .flatSheetSecondaryButton()
            .disabled(isWorking || shownSend == nil)
            .accessibilityIdentifier("cashu.send.reclaim")
            .accessibilityHint(hasSeveralSends ? "Reclaims the token on show" : "")
    }

    // MARK: - Checking, reclaiming and settled

    private var statusFace: some View {
        PaymentStatusView(
            details: settledRows,
            phase: face == .settled ? .success : .processing,
            processingTitle: activity == .reclaiming ? "Reclaiming…" : "Checking…",
            successTitle: settledTitle,
            onDone: onDone,
            onRetry: {}
        )
    }

    private var settledTitle: String {
        if recovery.outcome == .completed { return "Payment Complete" }
        return reclaimed == nil ? "No Pending Ecash" : "Ecash Reclaimed"
    }

    private var settledRows: [PaymentStatusView.DetailRow] {
        var rows: [PaymentStatusView.DetailRow] = []
        if recovery.outcome == .returned, let reclaimed {
            rows.append(.init(label: "Amount", isAmount: true, value: formatted(reclaimed.amount)))
            if reclaimed.fee > 0 {
                rows.append(.init(label: "Fee", value: formatted(reclaimed.fee)))
            }
        }
        rows.append(.init(label: "Mint", value: mintName))
        return rows
    }

    // MARK: - Couldn't confirm

    /// The status screens' anatomy with the caution glyph, as on the pay
    /// confirm: nothing was lost, the wallet only needs another look. Its one
    /// action is Check Again, where the shared status screen offers Done.
    private var unconfirmedFace: some View {
        PayFlowScaffold {
            ConfirmCautionFace(
                message: "Couldn't Confirm Send",
                detail: notice?.message
                    ?? "The wallet couldn't confirm what happened to this ecash. Check again before sending from this mint."
            )
            .frame(minHeight: 220, alignment: .top)
        } details: {
            PaymentDetailTextRow(label: "Mint", value: mintName)
                .padding(.horizontal, 24)
        } footer: {
            Button("Check Again") { Task { await resolve() } }
                .flatSheetSecondaryButton()
                .accessibilityIdentifier("cashu.send.check-recovery")
                .padding(.horizontal)
            .padding(.bottom, 16)
        }
    }

    // MARK: - Facts

    private var hasSeveralSends: Bool { recovery.sends.count > 1 }
    private var isSat: Bool { recovery.unit.lowercased() == "sat" }
    private var unitCurrency: any Currency { CurrencyRegistry.currency(forMintUnit: recovery.unit) }

    private var mintName: String {
        MintInfo.displayName(for: recovery.mintURL, in: walletManager.mints)
    }

    /// History holds the send under its operation ID, which is all that says
    /// when this ecash left.
    private func sentDate(of send: PendingSend) -> Date? {
        walletManager.transactions.first { $0.sagaId == send.id }?.date
    }

    private func formatted(_ amount: UInt64) -> String {
        isSat
            ? AmountFormatter.sats(amount, useBitcoinSymbol: settings.useBitcoinSymbol)
            : CurrencyAmount(value: amount, currency: unitCurrency).formatted()
    }

    private func copy(_ token: String) {
        UIPasteboard.general.string = token
        HapticFeedback.notification(.success)
        ConfirmationToast.show("Copied ecash token")
    }

    // MARK: - Actions

    /// What to say after a check or reclaim that left ecash outstanding. A
    /// settled outcome says it on the status screen instead.
    static func notice(
        after snapshot: PendingSendRecovery,
        checkedOnly: Bool,
        reclaimFailure: String?,
        checkFailed: Bool,
        returned: String?
    ) -> Notice? {
        switch snapshot.outcome {
        case .completed, .returned:
            return nil
        case .pending, .unverified:
            if let reclaimFailure {
                return Notice(title: "Couldn't reclaim", message: reclaimFailure, severity: .error)
            }
            if checkFailed {
                return Notice(
                    title: "Couldn't check status",
                    message: "Check your connection and try again.",
                    severity: .error
                )
            }
            guard snapshot.outcome == .pending else { return nil }
            if let returned {
                return Notice(message: "Reclaimed \(returned)", severity: .success)
            }
            guard checkedOnly else { return nil }
            return Notice(message: "Not claimed yet", severity: .info)
        }
    }

    @MainActor
    private func resolve(reclaiming send: PendingSend? = nil) async {
        withAnimation(.smooth(duration: 0.3)) {
            activity = send == nil ? .checking : .reclaiming
            notice = nil
        }
        onWorkingChanged(true)

        var snapshot = recovery
        var returned: Reclaimed?
        var reclaimFailure: String?
        if let send {
            do {
                let amount = try await walletManager.reclaimPendingSend(
                    operationID: send.id, mintURL: recovery.mintURL, unit: recovery.unit
                )
                snapshot.sends.removeAll { $0.id == send.id }
                snapshot.outcome = .unverified
                returned = Reclaimed(amount: amount, fee: send.amount > amount ? send.amount - amount : 0)
            } catch {
                reclaimFailure = error.walletMessage.text
            }
        }

        var checkFailed = false
        do {
            snapshot = try await walletManager.checkSendRecovery(snapshot)
        } catch {
            checkFailed = true
        }

        let result = Self.notice(
            after: snapshot,
            checkedOnly: send == nil,
            reclaimFailure: reclaimFailure,
            checkFailed: checkFailed,
            returned: returned.map { formatted($0.amount) }
        )

        // The check publishes the balance and history, unanimated, right
        // before it returns. Landing in that same pass folds this swap into
        // the unanimated update and it renders as a cut, so it waits a pass.
        try? await Task.sleep(for: .milliseconds(1))
        withAnimation(.smooth(duration: 0.3)) {
            recovery = snapshot
            if snapshot.outcome == .returned, let returned { reclaimed = returned }
            if result?.isToast != true { notice = result }
            activity = .idle
        }
        if let result {
            if result.isToast {
                ConfirmationToast.show(result.message)
            } else {
                AccessibilityNotification.Announcement(result.title ?? result.message).post()
            }
        }
        onWorkingChanged(false)
    }
}
