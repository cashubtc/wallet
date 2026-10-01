import SwiftUI

/// Move ecash from one held mint to another: choose the two mints and an
/// amount, review the fee, transfer. The amount is what arrives; the source
/// pays it plus the network fee.
struct MintTransferView: View {
    /// The mint whose row this was opened from, when it was.
    var openedFromMintURL: String? = nil

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @EnvironmentObject var walletManager: WalletManager
    @ObservedObject private var settings = SettingsManager.shared
    @ObservedObject private var priceService = PriceService.shared

    private enum Step: Equatable {
        case entry
        case review
        case status
    }

    private struct EntryNotice: Equatable {
        let text: String
        let severity: ErrorSeverity
    }

    private struct ReviewFailure: Equatable {
        let message: String
        let detail: String?
        /// A shortfall is fixed by a smaller amount, never by asking again.
        let isShortfall: Bool
    }

    @State private var step: Step = .entry
    @State private var route: MintTransferRoute?
    @State private var amountString = ""
    @State private var picking: MintSelectorDirection?

    /// The quote pair a Max tap produced. While it is held the amount field
    /// shows its amount, and review reuses it rather than quoting again.
    @State private var maxPlan: MintTransferPlan?
    @State private var isFindingMax = false
    @State private var maxTask: Task<Void, Never>?
    /// Identifies the latest Max request, so a superseded one that returns late
    /// cannot clear the spinner of the one that replaced it.
    @State private var maxRequest = 0
    @State private var entryNotice: EntryNotice?
    /// A source Max found too small to pay any fee, at the balance it held
    /// then. It stays said on that mint's line until its balance changes.
    @State private var feeShortfall: FeeShortfall?

    private struct FeeShortfall: Equatable {
        let mintURL: String
        let balance: UInt64
    }

    @State private var plan: MintTransferPlan?
    @State private var reviewFailure: ReviewFailure?
    @State private var quoteTask: Task<Void, Never>?
    /// The quotes lapsed on the review screen and were replaced, so the fee
    /// on screen is not the one the user last read.
    @State private var requoted = false

    @State private var phase: PaymentStatusView.Phase = .processing
    @State private var outcome: MintTransferOutcome?
    /// The leg in flight while the transfer runs.
    @State private var stage: MintTransferStage?

    var body: some View {
        NavigationStack {
            Group {
                switch step {
                case .entry:
                    entryFace
                        .transition(stepTransition(edge: .leading))
                case .review:
                    reviewFace
                        .transition(stepTransition(edge: .trailing))
                case .status:
                    statusFace
                        .transition(.opacity)
                }
            }
            .animation(.smooth(duration: 0.3), value: step)
            .navigationBarTitleDisplayMode(.inline)
            .navigationTitle("Transfer")
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    switch step {
                    case .entry:
                        SheetCloseButton()
                    case .review:
                        ConnectMintBackButton(action: backToEntry)
                    case .status:
                        // The status face carries its own Done / Try Again.
                        EmptyView()
                    }
                }
            }
            .sheet(item: $picking) { direction in
                mintPicker(for: direction)
            }
            .onChange(of: entryUnit) { oldUnit, newUnit in
                amountString = AmountFormatter.entryConverted(raw: amountString, from: oldUnit, to: newUnit)
            }
        }
        .accessibilityIdentifier("mints-transfer-screen")
        // A stray swipe must not hide a transfer while money is moving.
        .interactiveDismissDisabled(phase == .processing && step == .status)
        .walletSheetSurface(fillsScreen: true)
        .onAppear(perform: openRoute)
        .onDisappear(perform: abandonQuotes)
    }

    private func stepTransition(edge: Edge) -> AnyTransition {
        reduceMotion ? .opacity : .asymmetric(
            insertion: .move(edge: edge).combined(with: .opacity),
            removal: .move(edge: edge).combined(with: .opacity)
        )
    }

    // MARK: - Mints

    private var sourceMint: MintInfo? { route.flatMap { mint($0.sourceMintURL) } }
    private var destinationMint: MintInfo? { route.flatMap { mint($0.destinationMintURL) } }

    /// Read live so balances follow the wallet while the sheet is up.
    private func mint(_ url: String) -> MintInfo? {
        let identity = MintURLIdentity.normalized(url)
        return walletManager.mints.first { MintURLIdentity.normalized($0.url) == identity }
    }

    private func openRoute() {
        guard route == nil else { return }
        route = MintTransferRoute.initial(
            mints: walletManager.mints,
            activeMintURL: walletManager.activeMint?.url,
            openedFromMintURL: openedFromMintURL
        )
    }

    /// With only two mints there is nothing to pick: the arrow is the control.
    private var canChooseMint: Bool { walletManager.mints.count > 2 }

    private var canSwap: Bool {
        guard let sourceMint, let destinationMint else { return false }
        return MintTransferEligibility.eligible(source: destinationMint, destination: sourceMint)
    }

    // MARK: - Amount

    /// The unit the keypad enters: fiat only when fiat is primary and a price
    /// is loaded (mirrors `CurrencyAmountDisplay.effectivePrimary`).
    private var entryUnit: AmountDisplayPrimary {
        (settings.amountDisplayPrimary == .fiat && priceService.btcPriceUSD > 0) ? .fiat : .sats
    }

    /// A Max amount is the quote's, exactly. Fiat entry is too coarse to carry
    /// it through the keypad string.
    private var amountSats: UInt64 {
        maxPlan?.amount ?? AmountFormatter.entrySats(raw: amountString, unit: entryUnit)
    }

    private var entryState: MintTransferEntry {
        guard let sourceMint, let destinationMint else { return .empty }
        return MintTransferEntry.validation(amount: amountSats, source: sourceMint, destination: destinationMint)
    }

    /// Keypad edits arrive through this binding; programmatic fills do not. A
    /// keystroke after Max means the amount is no longer the quoted maximum.
    private var keypadAmount: Binding<String> {
        Binding(
            get: { amountString },
            set: { newValue in
                amountString = newValue
                dropMaxPlan()
                entryNotice = nil
            }
        )
    }

    private func formatSats(_ sats: UInt64) -> String {
        AmountFormatter.sats(sats, useBitcoinSymbol: settings.useBitcoinSymbol)
    }

    // MARK: - Entry face

    @ViewBuilder
    private var entryFace: some View {
        if let sourceMint, let destinationMint {
            if walletManager.mints.allSatisfy({ $0.balance == 0 }) {
                NativeEmptyState(
                    title: "Nothing to transfer yet",
                    systemImage: "arrow.left.arrow.right",
                    description: "Receive some ecash before you can move it between mints."
                )
            } else {
                entryContent(source: sourceMint, destination: destinationMint)
            }
        } else {
            NativeEmptyState(
                title: "Add another mint",
                systemImage: "arrow.left.arrow.right",
                description: "A transfer moves ecash between two of your mints."
            )
        }
    }

    private func entryContent(source: MintInfo, destination: MintInfo) -> some View {
        GeometryReader { proxy in
            VStack(spacing: 0) {
                if dynamicTypeSize.isAccessibilitySize {
                    // At accessibility sizes the pad alone takes most of the
                    // screen. The amount and the mints scroll above it rather
                    // than being squeezed until they overlap.
                    ScrollView {
                        VStack(spacing: 12) {
                            amountDisplay(role: .amountCompact)
                            entryNoticeView
                            route(source: source, destination: destination, showsDestinationBalance: true)
                        }
                        .padding(.top, 8)
                    }
                    .scrollBounceBehavior(.basedOnSize)
                } else {
                    // The pad and the two mints are fixed; the amount takes
                    // what is left. On a short screen the destination gives up
                    // its balance line so the amount still has room.
                    ZStack {
                        amountHero
                        VStack {
                            Spacer(minLength: 0)
                            entryNoticeView
                        }
                    }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .animation(reduceMotion ? .easeInOut(duration: 0.2) : .snappy(duration: 0.25), value: entryNotice)
                    .animation(reduceMotion ? .easeInOut(duration: 0.2) : .snappy(duration: 0.25), value: entryState)
                    .animation(reduceMotion ? .easeInOut(duration: 0.2) : .snappy(duration: 0.25), value: isWholeBalance)

                    route(source: source, destination: destination, showsDestinationBalance: proxy.size.height >= 600)
                        .padding(.bottom, 8)
                }

                NumberPadAmountInput(amountString: keypadAmount, unit: entryUnit)
                    .padding(.horizontal, NumberPadMetrics.gutter)

                Button(action: review) {
                    Text("Continue")
                }
                // Quiet tonal fill, as the review's Transfer is: nothing on
                // this sheet is a payment to someone else.
                .flatSheetSecondaryButton()
                .disabled(entryState != .ready || isFindingMax)
                .accessibilityIdentifier("mints-transfer-continue")
                .padding(.horizontal)
                .padding(.top, 16)
                .padding(.bottom, 16)
            }
        }
    }

    private func route(source: MintInfo, destination: MintInfo, showsDestinationBalance: Bool) -> some View {
        MintTransferRouteView(
            source: source,
            destination: destination,
            sourceBalanceText: formatSats(source.balance),
            destinationBalanceText: formatSats(destination.balance),
            sourceProblem: sourceProblem,
            destinationProblem: destinationProblem,
            showsDestinationBalance: showsDestinationBalance,
            isFindingMax: isFindingMax,
            // Gated on a spendable balance: an empty mint has no maximum.
            onUseMax: source.balance > 0 && entryState != .blocked(.sourceCannotSend) ? useMax : nil,
            onChooseSource: canChooseMint ? { choose(.source) } : nil,
            onChooseDestination: canChooseMint ? { choose(.destination) } : nil,
            onSwap: canSwap ? swap : nil
        )
        .padding(.horizontal, NumberPadMetrics.gutter)
    }

    /// The amount steps down the ladder rather than crowding the mints.
    private var amountHero: some View {
        ViewThatFits(in: .vertical) {
            amountDisplay(role: .amountHero)
            amountDisplay(role: .amountConfirm)
            amountDisplay(role: .amountCompact)
        }
    }

    private func amountDisplay(role: CashuTextRole) -> some View {
        CurrencyAmountDisplay(
            sats: amountSats,
            primary: $settings.amountDisplayPrimary,
            role: role,
            entryRaw: amountString,
            isDimmed: entryState == .overBalance
        )
    }

    /// Notes about the amount only. A problem with a mint is said on that
    /// mint's line in the route (`sourceProblem` / `destinationProblem`).
    @ViewBuilder
    private var entryNoticeView: some View {
        if entryState == .overBalance {
            // The source row states what is available, so the notice does not
            // repeat it.
            notice("Insufficient balance", severity: .caution)
        } else if let entryNotice {
            notice(entryNotice.text, severity: entryNotice.severity)
        } else if isWholeBalance {
            // The fee comes on top of the amount, so the whole balance cannot
            // arrive. Said here, not after a quote has been asked for.
            // It also teaches the gesture: the balance is the maximum.
            notice("Fees are added on top. Tap the balance to move everything.", severity: .info)
        }
    }

    private var isWholeBalance: Bool {
        guard entryState == .ready, maxPlan == nil, sourceProblem == nil, let sourceMint else { return false }
        return amountSats == sourceMint.balance
    }

    private var sourceProblem: String? {
        if entryState == .blocked(.sourceCannotSend) { return "can't send over Lightning" }
        guard let sourceMint, let feeShortfall,
              MintURLIdentity.normalized(feeShortfall.mintURL) == MintURLIdentity.normalized(sourceMint.url),
              feeShortfall.balance == sourceMint.balance
        else { return nil }
        return Self.feeShortfallText
    }

    private var destinationProblem: String? {
        entryState == .blocked(.destinationCannotReceive) ? "can't receive over Lightning" : nil
    }

    private static let feeShortfallText = "too little to cover the fee"

    private func notice(_ text: String, severity: ErrorSeverity) -> some View {
        InlineNotice(message: text, severity: severity, isCentered: true)
            .padding(.horizontal)
            .padding(.bottom, 8)
            .transition(.opacity)
    }

    // MARK: - Entry actions

    private func swap() {
        guard let route else { return }
        HapticFeedback.selection()
        changeRoute(to: route.swapped)
        if let sourceMint, let destinationMint {
            AccessibilityNotification.Announcement(
                "From \(sourceMint.name) to \(destinationMint.name)"
            ).post()
        }
    }

    private func choose(_ direction: MintSelectorDirection) {
        HapticFeedback.selection()
        picking = direction
    }

    /// Moving either end invalidates a Max quote, which was sized for the old
    /// pair. A typed amount means the same thing on any pair and is kept.
    private func changeRoute(to newRoute: MintTransferRoute) {
        guard newRoute != route else { return }
        maxTask?.cancel()
        maxRequest += 1
        isFindingMax = false
        if maxPlan != nil {
            dropMaxPlan()
            amountString = ""
        }
        entryNotice = nil
        // The route's slots morph to their new mints themselves.
        route = newRoute
    }

    private func dropMaxPlan() {
        guard let abandoned = maxPlan else { return }
        maxPlan = nil
        Task { await walletManager.discardMintTransferPlan(abandoned) }
    }

    private func mintPicker(for direction: MintSelectorDirection) -> some View {
        let options = walletManager.mints.map { mint in
            let unavailable: String? = switch direction {
            case .source:
                MintTransferEligibility.canSend(mint) ? nil : "Can't send over Lightning"
            case .destination:
                MintTransferEligibility.canReceive(mint) ? nil : "Can't receive over Lightning"
            }
            return MintTransferMintPicker.Option(mint: mint, unavailableReason: unavailable)
        }
        let selected = direction == .source ? route?.sourceMintURL : route?.destinationMintURL
        return MintTransferMintPicker(
            direction: direction,
            options: options,
            selectedMintURL: selected ?? "",
            onSelect: { mint in
                guard let route else { return }
                changeRoute(to: route.choosing(mint.url, as: direction))
            }
        )
    }

    private func useMax() {
        guard let route, !isFindingMax else { return }
        HapticFeedback.impact(.light)
        dropMaxPlan()
        entryNotice = nil
        isFindingMax = true
        maxRequest += 1
        let request = maxRequest
        maxTask = Task {
            defer { if maxRequest == request { isFindingMax = false } }
            do {
                let quoted = try await walletManager.prepareMaxMintTransfer(
                    from: route.sourceMintURL,
                    to: route.destinationMintURL
                )
                // The native quote cannot be cancelled. If the mints changed
                // or the sheet closed while it ran, the result is not wanted.
                guard !Task.isCancelled, self.route == route else {
                    await walletManager.discardMintTransferPlan(quoted)
                    return
                }
                maxPlan = quoted
                amountString = AmountFormatter.entryConverted(
                    raw: String(quoted.amount), from: .sats, to: entryUnit
                )
                if let balance = sourceMint?.balance, quoted.amount < balance {
                    entryNotice = EntryNotice(
                        text: "Amount adjusted for fees and mint limits.",
                        severity: .info
                    )
                }
            } catch is CancellationError {
                return
            } catch MintTransferError.nothingToTransfer {
                guard !Task.isCancelled, let source = mint(route.sourceMintURL) else { return }
                // The fee alone outweighs this mint's balance: a fact about the
                // mint, said on its line, where the balance stops offering a
                // maximum.
                withAnimation(reduceMotion ? .easeInOut(duration: 0.2) : .snappy(duration: 0.25)) {
                    feeShortfall = FeeShortfall(mintURL: source.url, balance: source.balance)
                }
                HapticFeedback.notification(.warning)
                AccessibilityNotification.Announcement(
                    "\(source.name): \(Self.feeShortfallText)"
                ).post()
            } catch {
                guard !Task.isCancelled else { return }
                let message = error.walletMessage
                entryNotice = EntryNotice(text: message.text, severity: message.severity)
            }
        }
    }

    // MARK: - Review face

    private func review() {
        guard entryState == .ready, let route else { return }
        HapticFeedback.impact(.light)
        reviewFailure = nil
        if let maxPlan, !maxPlan.isExpired() {
            // Already quoted: nothing to wait for.
            plan = maxPlan
            step = .review
            return
        }
        // Read before the Max quote is dropped: the amount is its amount.
        let amount = amountSats
        dropMaxPlan()
        plan = nil
        step = .review
        requestQuote(route: route, amount: amount)
    }

    private func requestQuote(route: MintTransferRoute, amount: UInt64) {
        quoteTask?.cancel()
        plan = nil
        reviewFailure = nil
        quoteTask = Task {
            do {
                let quoted = try await walletManager.prepareMintTransfer(
                    from: route.sourceMintURL,
                    to: route.destinationMintURL,
                    amount: amount
                )
                guard !Task.isCancelled, step == .review else {
                    await walletManager.discardMintTransferPlan(quoted)
                    return
                }
                plan = quoted
                if requoted {
                    HapticFeedback.notification(.warning)
                    AccessibilityNotification.Announcement(Self.requotedNotice).post()
                }
            } catch is CancellationError {
                return
            } catch {
                guard !Task.isCancelled else { return }
                requoted = false
                reviewFailure = failure(for: error)
            }
        }
    }

    private func failure(for error: Error) -> ReviewFailure {
        if case MintTransferError.insufficientBalance(let required, let available) = error {
            return ReviewFailure(
                message: "Not enough balance.",
                detail: "This mint holds \(formatSats(available)); the transfer reserves up to \(formatSats(required)).",
                isShortfall: true
            )
        }
        return ReviewFailure(
            message: error.userFacingWalletMessage,
            detail: nil,
            isShortfall: error.isInsufficientBalanceError
        )
    }

    private func backToEntry() {
        HapticFeedback.selection()
        quoteTask?.cancel()
        // A Max quote stays held so Continue is instant again; a typed one is
        // specific to this visit.
        if let plan, plan.id != maxPlan?.id {
            Task { await walletManager.discardMintTransferPlan(plan) }
        }
        plan = nil
        reviewFailure = nil
        requoted = false
        step = .entry
    }

    private static let requotedNotice = "The fee was updated. Check it and transfer again."

    private var reviewFace: some View {
        let quotePending = plan == nil && reviewFailure == nil
        return VStack(spacing: 0) {
            PayFlowScaffold {
                VStack(spacing: 8) {
                    if let reviewFailure {
                        cautionFace(message: reviewFailure.message, detail: reviewFailure.detail)
                            .transition(.opacity)
                    } else if let plan {
                        CurrencyAmountDisplay(sats: plan.amount, primary: $settings.amountDisplayPrimary)
                            .transition(.opacity)
                    } else {
                        SpinnerRing()
                            .transition(.opacity)
                    }
                }
            } details: {
                if let plan, reviewFailure == nil {
                    reviewRows(plan)
                }
            } footer: {
                EmptyView()
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)

            if requoted, plan != nil, reviewFailure == nil {
                InlineNotice(message: Self.requotedNotice, severity: .info, isCentered: true)
                    .padding(.horizontal)
                    .padding(.bottom, 8)
                    .transition(.opacity)
            }

            Group {
                if quotePending {
                    // Reserve the button's footprint while the spinner owns
                    // the wait — no second spinner in the button.
                    Button(action: {}) { Text(verbatim: " ") }
                        .glassButton()
                        .disabled(true)
                        .opacity(0)
                        .accessibilityHidden(true)
                } else if let reviewFailure {
                    if reviewFailure.isShortfall {
                        Button(action: backToEntry) { Text("Change Amount") }
                            .flatSheetSecondaryButton()
                    } else {
                        Button(action: retryQuote) { Text("Retry Quote") }
                            .flatSheetSecondaryButton()
                    }
                } else if let plan {
                    // Names the amount that arrives. Quiet tonal fill, like
                    // Continue: moving between your own mints is no payment.
                    Button(action: transfer) { Text("Transfer \(formatSats(plan.amount))") }
                        .flatSheetSecondaryButton()
                        .accessibilityIdentifier("mints-transfer-commit")
                }
            }
            .padding(.horizontal)
            .padding(.bottom, 16)
        }
        .animation(.smooth(duration: 0.3), value: plan != nil)
        .animation(.smooth(duration: 0.3), value: reviewFailure)
        .animation(.smooth(duration: 0.3), value: requoted)
    }

    /// Preflight caution in the status screens' anatomy. Always the orange
    /// triangle: a failed quote has spent nothing.
    private func cautionFace(message: String, detail: String?) -> some View {
        VStack(spacing: 16) {
            Image(systemName: "exclamationmark.triangle.fill")
                .font(.statusGlyph)
                .foregroundStyle(.orange)

            VStack(spacing: 8) {
                Text(message)
                    .font(.title2.weight(.semibold))
                    .multilineTextAlignment(.center)

                if let detail {
                    Text(detail)
                        .font(.callout)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                }
            }
            .padding(.horizontal, 32)
        }
        .accessibilityElement(children: .combine)
    }

    private func reviewRows(_ plan: MintTransferPlan) -> some View {
        VStack(spacing: 0) {
            mintRow(label: "From", mintURL: plan.sourceMintURL)
            mintRow(label: "To", mintURL: plan.destinationMintURL)
            detailRow(label: "Network fee", value: formatSats(plan.feeUpperBound))
            detailRow(label: "Total", value: formatSats(plan.total))
        }
        .padding(.top, 16)
        .padding(.horizontal)
    }

    private func mintRow(label: String, mintURL: String) -> some View {
        let name = MintInfo.displayName(for: mintURL, in: walletManager.mints)
        return PaymentDetailPair(label: label) {
            Text(name)
                .fontWeight(.regular)
                .truncationMode(.middle)
        }
        .paymentDetailRow()
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(label): \(name)")
    }

    private func detailRow(label: String, value: String) -> some View {
        PaymentDetailPair(label: label) {
            Text(value)
                .fontWeight(.regular)
                .truncationMode(.tail)
        }
        .paymentDetailRow()
        .accessibilityElement(children: .combine)
    }

    private func retryQuote() {
        guard let route else { return }
        HapticFeedback.selection()
        requestQuote(route: route, amount: amountSats)
    }

    // MARK: - Transfer

    private func transfer() {
        guard let plan, let route else { return }
        guard !plan.isExpired() else {
            // The quotes lapsed while the review was open. Quote again and
            // show the fee that now applies instead of failing the commit,
            // and say so: the button did not simply fail to respond.
            if maxPlan?.id == plan.id { maxPlan = nil }
            Task { await walletManager.discardMintTransferPlan(plan) }
            requoted = true
            requestQuote(route: route, amount: plan.amount)
            return
        }
        HapticFeedback.impact(.medium)
        // The plan is spent whatever happens next.
        if maxPlan?.id == plan.id { maxPlan = nil }
        requoted = false
        outcome = nil
        stage = nil
        phase = .processing
        step = .status
        Task {
            do {
                outcome = try await walletManager.executeMintTransfer(plan) { leg in
                    // Reported from the engine's executor.
                    Task { @MainActor in stage = leg }
                }
                phase = .success
            } catch {
                let message = error.walletMessage
                phase = .failure(
                    message: message.text,
                    isCaution: message.severity == .caution,
                    isTerminal: message.recoverability == .terminal
                )
            }
        }
    }

    private var isSettling: Bool {
        if case .settling = outcome { return true }
        return false
    }

    private var sourceName: String {
        plan.map { MintInfo.displayName(for: $0.sourceMintURL, in: walletManager.mints) } ?? "The mint"
    }

    private var destinationName: String {
        plan.map { MintInfo.displayName(for: $0.destinationMintURL, in: walletManager.mints) } ?? "The mint"
    }

    /// Where the ecash is while it moves. The user's money is between two
    /// custodians here; the line says which one holds it.
    private var stageMessage: String {
        switch stage {
        case .paying, nil:
            return "Leaving \(sourceName)"
        case .issuing:
            return "Arriving at \(destinationName)"
        }
    }

    private var settlingMessage: String? {
        switch outcome {
        case .settling(.issuance):
            return "\(destinationName) is still issuing your ecash. It will arrive automatically."
        case .settling(.payment):
            return "Still leaving \(sourceName). Your funds are safe."
        case .completed, nil:
            return nil
        }
    }

    private var statusFace: some View {
        PaymentStatusView(
            details: statusRows,
            phase: phase,
            processingTitle: "Transferring…",
            successTitle: isSettling ? "Transfer Processing" : "Transfer Complete",
            failureTitle: "Transfer Failed",
            settlementPending: isSettling,
            settlementMessage: settlingMessage,
            processingMessage: stageMessage,
            showsDetailsWhileProcessing: true,
            onDone: { dismiss() },
            onRetry: retryTransfer
        )
    }

    private var statusRows: [PaymentStatusView.DetailRow] {
        guard let plan else { return [] }
        var rows: [PaymentStatusView.DetailRow] = [
            .init(label: "Amount", isAmount: true, value: formatSats(plan.amount)),
            .init(label: "From", value: MintInfo.displayName(for: plan.sourceMintURL, in: walletManager.mints)),
            .init(label: "To", value: MintInfo.displayName(for: plan.destinationMintURL, in: walletManager.mints)),
        ]
        if case .completed(_, let feePaid) = outcome {
            // A receipt records what happened; a zero fee is omitted.
            if feePaid > 0 {
                rows.append(.init(label: "Network fee", value: formatSats(feePaid)))
            }
        } else {
            rows.append(.init(label: "Network fee", value: formatSats(plan.feeUpperBound)))
        }
        return rows
    }

    /// The failed plan's quotes are gone or spoken for, so trying again means
    /// quoting again. A typed amount can be re-quoted as it stands; a maximum
    /// has to be found afresh.
    private func retryTransfer() {
        guard let failed = plan, let route else { return }
        plan = nil
        if failed.mode == .max {
            amountString = ""
            withAnimation(.smooth(duration: 0.3)) { step = .entry }
        } else {
            withAnimation(.smooth(duration: 0.3)) { step = .review }
            requestQuote(route: route, amount: failed.amount)
        }
    }

    // MARK: - Leaving

    /// Quotes that were never executed exist at both mints; tell the wallet to
    /// forget them. Committed plans are untouched by a discard.
    private func abandonQuotes() {
        maxTask?.cancel()
        quoteTask?.cancel()
        let abandoned = [plan, maxPlan].compactMap { $0 }
        guard !abandoned.isEmpty else { return }
        let walletManager = walletManager
        Task {
            for plan in abandoned {
                await walletManager.discardMintTransferPlan(plan)
            }
        }
    }
}

extension MintSelectorDirection: Identifiable {
    var id: Self { self }
}
