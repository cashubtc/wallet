import CoreNFC
import SwiftUI

enum HomeActionAccessibility {
    static let receiveHint = """
    Opens the unified flow for a pasted ecash token or a new Cashu Request, \
    Lightning invoice, BOLT12 offer, or Bitcoin address
    """

    static let sendHint = """
    Opens the unified flow for ecash, Lightning addresses, BOLT11 invoices, \
    BOLT12 offers, Bitcoin addresses, or Cashu Requests
    """
}

struct MainWalletView: View {
    /// Called when the user taps "View all activity" — switches the tab
    /// container to the History tab. Lives at the call-site so
    /// MainWalletView stays decoupled from the Tab enum.
    var onViewAllHistory: () -> Void = {}
    /// The History tab is showing. It plays arrivals itself, so home takes
    /// them silently instead of replaying them on return.
    var isHistoryTabSelected = false

    @EnvironmentObject var walletManager: WalletManager
    @EnvironmentObject var navigationManager: NavigationManager
    @ObservedObject var settings = SettingsManager.shared
    @ObservedObject var priceService = PriceService.shared
    @ObservedObject private var appLock = AppLockManager.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.cashuFonts) private var fonts
    @Environment(\.scenePhase) private var scenePhase

    @State private var receivedFeedback = ReceivedBalanceFeedback()
    @State private var arrival = HomeArrivalCoordinator()
    /// False while a pushed page (Settings) or another tab hides the wallet.
    @State private var isRootOnScreen = false
    /// True from opening a transaction's sheet until it has finished leaving.
    @State private var isDetailSheetUp = false
    @State private var contactlessCoordinator = ContactlessPaymentCoordinator()
    @State private var selectedTransaction: WalletTransaction?
    @State private var isSheetDismissing = false
    @State private var topInsetHeight: CGFloat = 0
    /// Last-viewed home balance unit, persisted so the wallet reopens on it.
    /// Clamped back to "sat" whenever that unit no longer carries a balance.
    @AppStorage("homeBalanceUnit") private var storedHomeUnit: String = "sat"

    private let recentRowCap = 5
    /// Hero height (primary + status). Same whether single-unit or pager.
    ///
    /// Derived from the resolved type metrics rather than being a constant. It
    /// stays fixed for a given text size, so a unit swap or a fiat show/hide
    /// still cannot reflow the home canvas — that guarantee is the entire
    /// reason the reservation exists. But it now grows with the text size, so
    /// large-text users are no longer cropped by a box sized for the default.
    /// The old 94 also held an 18pt slot around a `.body`, whose line box is
    /// ~22pt, so the status line was clipping even at the default size.
    private var heroPagerHeight: CGFloat {
        CashuTextRole.amountHero.lineHeight(at: typeSize, fonts: fonts)
            + balanceLineSpacing + statusLineHeight
    }

    /// Reserved status-line slot under the primary amount.
    private var statusLineHeight: CGFloat {
        CashuTextRole.body.lineHeight(at: typeSize, fonts: fonts)
    }
    /// Move the converted line upward without changing the hero footprint:
    /// remove 2pt above it and reserve the same 2pt below it.
    private let balanceLineSpacing: CGFloat = 2
    private let convertedAmountBottomPadding: CGFloat = 2
    private let pageDotSize: CGFloat = 6
    /// Gap between hero and dots — always reserved with the dots slot.
    private let pageDotGap: CGFloat = 0

    private var isBottomSheetPresented: Bool {
        navigationManager.activeWalletSheet != nil || selectedTransaction != nil
    }

    /// Balances as home shows them: held while something covers home, then
    /// moved in the same beat as the rows.
    private var shownBalancesByUnit: [String: UInt64] {
        arrival.shown?.balancesByUnit ?? walletManager.balancesByUnit
    }

    private var shownSatBalance: UInt64 {
        arrival.shown?.satBalance ?? walletManager.balance
    }

    /// Units the home hero can page through: sat, then each held non-sat unit.
    private var homeUnits: [String] {
        HomeBalance.homeBalanceUnits(shownBalancesByUnit)
    }

    /// Page through every held currency, independently of mint advertisements.
    private var showsUnitPager: Bool {
        HomeBalance.showsUnitPager(
            balancesByUnit: shownBalancesByUnit
        )
    }

    /// TabView selection clamped to the currently available units.
    private var selectedHomeUnit: Binding<String> {
        Binding(
            get: { HomeBalance.resolvedUnit(storedHomeUnit, in: homeUnits) },
            set: { storedHomeUnit = $0 }
        )
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                recentContent
                    .frame(maxWidth: 760)
                    .frame(maxWidth: .infinity)
            }
            .scrollIndicators(.hidden)
            // Pushing Settings or switching tabs takes the wallet off screen.
            .onAppear { isRootOnScreen = true }
            .onDisappear { isRootOnScreen = false }
            // Rows dissolve into the pinned top section as they scroll up.
            .scrollEdgeFade(top: topInsetHeight)
            .refreshable {
                await walletManager.syncPendingMintQuotes(force: true)
                await walletManager.checkAllPendingTokens()
            }
            .safeAreaInset(edge: .top, spacing: 0) {
                fixedTopSection
                    .background(
                        GeometryReader { proxy in
                            Color.clear.preference(
                                key: TopInsetHeightKey.self,
                                value: proxy.size.height
                            )
                        }
                    )
            }
            .onPreferenceChange(TopInsetHeightKey.self) { topInsetHeight = $0 }
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    NavigationLink {
                        SettingsView()
                    } label: {
                        Image(systemName: "gearshape")
                            .font(.body.weight(.semibold))
                            .toolbarIconTapTarget()
                    }
                    .accessibilityLabel("Settings")
                    .accessibilityHint("Opens wallet settings")
                    .accessibilityIdentifier("wallet-settings-button")
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        navigationManager.activeWalletSheet = .scanner
                    } label: {
                        Image(systemName: "viewfinder")
                            .font(.body.weight(.semibold))
                            .toolbarIconTapTarget()
                    }
                    .accessibilityLabel("Scan QR Code")
                    .accessibilityHint("Opens the QR scanner")
                }
            }
            // The one flow-sheet slot. `onDismiss` fires after the dismiss
            // animation and promotes any surface parked by
            // `NavigationManager.present` — sheets replace, never stack.
            .sheet(
                item: $navigationManager.activeWalletSheet,
                onDismiss: { navigationManager.sheetDidDismiss() }
            ) { sheet in
                sheetView(for: sheet)
                    .observeBottomSheetDismissal { isSheetDismissing = $0 }
            }
            .sheet(item: $selectedTransaction, onDismiss: { isDetailSheetUp = false }) { transaction in
                TransactionDetailView(transaction: transaction)
                    .environmentObject(walletManager)
                    .observeBottomSheetDismissal { isSheetDismissing = $0 }
            }
            .task { await walletManager.loadTransactions() }
        }
        .bottomSheetBackdrop(
            isPresented: isBottomSheetPresented && !isSheetDismissing
        )
        .onChange(of: isBottomSheetPresented) { _, presented in
            if presented { isSheetDismissing = false }
        }
        .onReceive(NotificationCenter.default.publisher(for: .cashuTokenReceived)) { note in
            guard let amount = note.userInfo?["amount"] as? UInt64 else { return }
            // Only background receives (poster sets "homeHaptic") buzz and
            // announce here; in-flow receives own both on their confirmation
            // surface.
            arrival.receive(HomeReceipt(
                amount: amount,
                unit: note.userInfo?["unit"] as? String ?? "sat",
                homeOwned: note.userInfo?["homeHaptic"] as? Bool ?? false
            ))
        }
        .onChange(of: liveState, initial: true) { _, state in
            arrival.update(live: state.snapshot, rows: liveRecentItems, loaded: state.loaded)
        }
        .onChange(of: presence, initial: true) { _, newPresence in
            arrival.setPresence(newPresence)
        }
        .onAppear {
            arrival.onBeat = { playBeat($0) }
            arrival.onConfirm = { confirmReceipts($0) }
        }
        .onDisappear { receivedFeedback.clear() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .background { receivedFeedback.clear() }
        }
    }

    // MARK: - Fixed Top Section

    // Pinned above the scroll. Sits on the bare canvas so the masked scroll
    // content reads as floating beneath it.
    private var fixedTopSection: some View {
        VStack(spacing: 0) {
            balanceSection
                .padding(.top, 8)

            actionButtons
                .padding(.top, 16)
                .padding(.horizontal, 16)
                .padding(.bottom, 16)
        }
        .frame(maxWidth: 760)
        .frame(maxWidth: .infinity)
    }

    // MARK: - Balance Section

    private var balanceSection: some View {
        VStack(spacing: 0) {
            // Fixed footprint: hero + (gap + dots) always, whether the active
            // mint is single-unit or multi-unit — switching mints must not shove
            // Receive/Send / Recent up or down.
            VStack(spacing: pageDotGap) {
                Group {
                    let units = homeUnits
                    if !showsUnitPager {
                        unitBalanceHero("sat")
                    } else {
                        // Multi-unit: swipeable pager, one unit per page.
                        // Custom dots (not UIPageControl) for tight vertical margin.
                        TabView(selection: selectedHomeUnit) {
                            ForEach(units, id: \.self) { unit in
                                unitBalanceHero(unit)
                                    .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
                                    .tag(unit)
                            }
                        }
                        .tabViewStyle(.page(indexDisplayMode: .never))
                    }
                }
                .frame(height: heroPagerHeight, alignment: .top)

                ZStack {
                    if showsUnitPager {
                        unitPagerDots(homeUnits)
                    }
                }
                .frame(height: pageDotSize)
            }
            .padding(.top, 18)
        }
    }

    /// One unit's balance hero. Sat uses the configured fiat/sats ordering plus
    /// its converted/received sub-line; other units render directly in that currency
    /// (no fiat conversion — eur is already fiat). Status-line slot is always
    /// reserved so pages and single-unit mode share one height.
    @ViewBuilder
    private func unitBalanceHero(_ unit: String) -> some View {
        VStack(spacing: balanceLineSpacing) {
            if unit.lowercased() == "sat" {
                let sats = shownBalancesByUnit["sat"] ?? shownSatBalance
                let display = balanceDisplay(sats)
                let primaryValue = balanceNumericValue(sats, primary: display.effectivePrimary)
                let step = HomeBalanceCycle.current(
                    isHidden: settings.hideBalances,
                    effectivePrimary: display.effectivePrimary,
                    isSatPage: true
                )
                let next = HomeBalanceCycle.next(
                    after: step,
                    hasFiat: display.effectivePrimary == .fiat || display.secondary != nil,
                    isSatPage: true
                )
                balanceHeroButton(
                    unit: unit,
                    parts: display.primaryParts,
                    value: primaryValue,
                    label: "Balance: \(display.primary)",
                    next: next,
                    phrase: HomeBalanceCycle.actionPhrase(
                        for: next,
                        fiatText: display.effectivePrimary == .fiat ? display.primary : display.secondary,
                        unitName: "sats"
                    )
                )

                // Status line under the balance: a transient monochrome
                // received-delta beat takes over the fiat slot for 2.5s on receipt,
                // then fiat fades back. Same slot, so the swap doesn't reflow the
                // balance. (De-greened 2026-07-05 — the balance roll carries the moment.)
                balanceStatusLine(display, secondaryValue: balanceSecondaryNumericValue(sats, display: display))
            } else {
                let amount = shownBalancesByUnit[unit] ?? 0
                let currency = CurrencyRegistry.currency(forMintUnit: unit)
                let formatted = CurrencyAmount(value: amount, currency: currency).formatted()
                let parts = AmountParts.parse(formatted)
                let step = HomeBalanceCycle.current(
                    isHidden: settings.hideBalances,
                    effectivePrimary: .sats,
                    isSatPage: false
                )
                let next = HomeBalanceCycle.next(after: step, hasFiat: false, isSatPage: false)
                balanceHeroButton(
                    unit: unit,
                    parts: parts,
                    value: Double(amount),
                    label: "Balance: \(parts.spoken)",
                    next: next,
                    phrase: HomeBalanceCycle.actionPhrase(for: next, fiatText: nil, unitName: currency.code)
                )
                .animation(.snappy, value: amount)
                // Same reserved status slot as sat (no fiat conversion for non-sat).
                Color.clear.frame(height: statusLineHeight)
            }
        }
        .padding(.bottom, convertedAmountBottomPadding)
    }

    /// The tappable hero. Every page steps through the balance cycle, with or
    /// without a fiat line, so it is always one Button: the hidden and visible
    /// lockups swap inside its label, which keeps the Button's identity (and
    /// VoiceOver focus) through every step. Both lockups reserve the same line
    /// box, so the swap never reflows the hero.
    private func balanceHeroButton(
        unit: String,
        parts: AmountParts,
        value: Double,
        label: String,
        next: HomeBalanceStep,
        phrase: String
    ) -> some View {
        let hidden = settings.hideBalances
        return Button {
            cycleHomeBalance(to: next)
        } label: {
            ZStack {
                if hidden {
                    AmountLockup(parts: AmountFormatter.hiddenBalanceParts)
                        .transition(balanceMaskTransition)
                } else {
                    // Bitcoin ↔ fiat stays inside this branch, so the digits
                    // still roll; only hiding and showing blur.
                    AmountLockup(parts: parts, value: value, accessibilityPrefix: "Balance")
                        .transition(balanceMaskTransition)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(hidden ? HomeBalanceCycle.hiddenLabel : label)
        .accessibilityHint("Double tap to \(phrase)")
        .accessibilityAction(named: Text(phrase.prefix(1).uppercased() + phrase.dropFirst())) {
            cycleHomeBalance(to: next)
        }
        .accessibilityIdentifier("wallet-balance-\(unit.lowercased())")
    }

    private var balanceMaskTransition: AnyTransition {
        reduceMotion ? .opacity : AnyTransition(.blurReplace)
    }

    /// One step of the cycle. The haptic fires here rather than off a settings
    /// trigger, so fiat → hidden buzzes like every other step and a price that
    /// drops out doesn't.
    private func cycleHomeBalance(to next: HomeBalanceStep) {
        HapticFeedback.selection()
        withAnimation(reduceMotion ? .easeOut(duration: 0.2) : .snappy) {
            settings.applyHomeBalanceStep(next)
        }
    }

    /// Compact page dots under the unit pager (6pt dots, 6pt gap, active pill
    /// at 2.5× width). Parent always reserves [pageDotGap + pageDotSize].
    private func unitPagerDots(_ units: [String]) -> some View {
        let selected = selectedHomeUnit.wrappedValue
        return HStack(spacing: 6) {
            ForEach(units, id: \.self) { unit in
                let isSelected = unit == selected
                Capsule()
                    .fill(isSelected ? Color.accentColor : Color.primary.opacity(0.2))
                    .frame(
                        width: isSelected ? pageDotSize * 2.5 : pageDotSize,
                        height: pageDotSize
                    )
            }
        }
        .animation(reduceMotion ? nil : .snappy, value: selected)
        .accessibilityHidden(true)
    }

    // MARK: - Received Delta Beat

    /// The status line beneath the balance: the transient received-delta beat
    /// while a payment just landed, otherwise the fiat sub-amount. Always keeps
    /// [statusLineHeight] so hiding fiat never collapses the hero.
    @ViewBuilder
    private func balanceStatusLine(_ display: AmountDisplayText, secondaryValue: Double?) -> some View {
        ZStack {
            if let amount = receivedFeedback.amount {
                receivedDeltaBeat(amount)
                    .transition(reduceMotion ? .opacity : .asymmetric(insertion: .scale(scale: 0.9).combined(with: .opacity), removal: .opacity))
            } else if !walletManager.isRuntimeReady {
                Text("Preparing wallet…")
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .transition(.opacity)
            } else if !settings.hideBalances, let secondary = display.secondary {
                Text(secondary)
                    .font(.body)
                    .monospacedDigit()
                    .contentTransition(reduceMotion ? .opacity : .numericText(value: secondaryValue ?? 0))
                    .animation(reduceMotion ? .easeOut(duration: 0.2) : .snappy, value: secondaryValue)
                    .foregroundStyle(.secondary)
                    .transition(.opacity)
            }
        }
        .frame(height: statusLineHeight)
    }

    /// Quiet "+2,500" beat. Monochrome (`.secondary`) — no green, no checkmark,
    /// no bounce: the rolling balance above is the primary signal, this just
    /// names the exact amount that landed. Grouped via the canonical formatter,
    /// no unit (the balance beside it carries it), no directional arrow (the
    /// down-arrow stays exclusive to row badges). VoiceOver-hidden; the balance
    /// announces the new total. While balances are hidden it reads "+****":
    /// the same beat, without the amount.
    @ViewBuilder
    private func receivedDeltaBeat(_ amount: UInt64) -> some View {
        Group {
            if settings.hideBalances {
                HiddenDigitsText(sign: "+", role: semiboldBody)
            } else {
                Text("+\(settings.formatAmountShort(amount))")
            }
        }
        .monospacedDigit()
        .font(.body.weight(.semibold))
        .foregroundStyle(.secondary)
        .accessibilityHidden(true)
    }

    /// The beat's type, so its hidden asterisks drop to the right centre line.
    private var semiboldBody: CashuTextRole {
        var role = CashuTextRole.body
        role.weight = .semibold
        return role
    }

    // MARK: - Arrival Beat

    /// One beat: the new rows push in and the balance rolls under one
    /// animation, and the "+N", haptic and announcement land on the same
    /// frame. Reads reduce-motion live because the coordinator calls this
    /// outside a body pass.
    private func playBeat(_ beat: HomeBeat) {
        let reduceMotion = UIAccessibility.isReduceMotionEnabled
        // The system list insertion: rows below glide down, the new row fades
        // in where it sits. No bounce.
        withAnimation(reduceMotion ? .easeInOut(duration: 0.2) : .smooth(duration: 0.4)) {
            arrival.present(beat)
        }
        // The "+N" sits under the sat balance only; a non-sat receive
        // (eur/usd/…) rolls its own page without one rather than flash a
        // misleading "+N sat".
        if let sats = beat.deltas["sat"], sats > 0 {
            // Reuses the sanctioned payment-received celebration spring
            // (Motion §6); reduce-motion collapses it to a cross-fade. Shows
            // the beat and re-arms a 2.5s dismiss timer; receipts that land
            // while it shows add to it.
            receivedFeedback.show(
                amount: sats,
                animation: reduceMotion
                    ? .easeInOut(duration: 0.2)
                    : .spring(response: 0.5, dampingFraction: 0.7)
            )
        }
        confirmReceipts(beat.confirmations)
    }

    /// The "sats landed" haptic and a polite spoken announcement, reserved for
    /// background receives no visible surface confirms (npub.cash, a Lightning
    /// payment landing on its own, an unwatched Cashu request). In-flow
    /// receives already own both, so they never reach here.
    private func confirmReceipts(_ receipts: [HomeReceipt]) {
        guard !receipts.isEmpty else { return }
        HapticFeedback.notification(.success)
        var announcement = AttributedString(HomeReceipt.announcement(
            for: receipts,
            hideBalances: settings.hideBalances
        ))
        // Queued behind whatever VoiceOver is reading, never cutting it off.
        announcement.accessibilitySpeechAnnouncementPriority = .low
        AccessibilityNotification.Announcement(announcement).post()
    }

    // MARK: - Action Buttons (Receive + Send)

    /// Scan moved to the toolbar; the action row is a two-button pair.
    /// On iOS 26 these use Apple's native neutral Liquid Glass button style
    /// (`.buttonStyle(.glass)`), wrapped in a `GlassEffectContainer` so the two
    /// adjacent capsules sample light consistently. The native style owns its own
    /// interactive press/morph, so a single gesture drives each button. iOS 18–25
    /// falls back to the in-house `glassButton()` capsule.
    @ViewBuilder
    private var actionButtons: some View {
        if #available(iOS 26, *) {
            GlassEffectContainer(spacing: 12) {
                HStack(spacing: 12) {
                    actionButton(
                        "Receive",
                        identifier: "wallet-action-receive",
                        hint: HomeActionAccessibility.receiveHint
                    ) { navigationManager.activeWalletSheet = .receive }

                    actionButton(
                        "Send",
                        identifier: "wallet-action-send",
                        hint: HomeActionAccessibility.sendHint
                    ) { navigationManager.activeWalletSheet = .send(prefill: nil) }
                }
            }
            .disabled(!walletManager.isRuntimeReady)
        } else {
            HStack(spacing: 12) {
                Button { navigationManager.activeWalletSheet = .receive } label: {
                    Text("Receive")
                }
                .glassButton()
                .accessibilityIdentifier("wallet-action-receive")
                .accessibilityHint(HomeActionAccessibility.receiveHint)

                Button { navigationManager.activeWalletSheet = .send(prefill: nil) } label: {
                    Text("Send")
                }
                .glassButton()
                .accessibilityIdentifier("wallet-action-send")
                .accessibilityHint(HomeActionAccessibility.sendHint)
            }
            .disabled(!walletManager.isRuntimeReady)
        }
    }

    /// A single home action button rendered with Apple's native neutral
    /// Liquid Glass style, sized to fill half the action row.
    @available(iOS 26, *)
    private func actionButton(
        _ title: String,
        identifier: String,
        hint: String,
        action: @escaping () -> Void
    ) -> some View {
        Button(action: action) {
            Text(title)
                .font(.body.weight(.semibold))
                .frame(maxWidth: .infinity)
                .padding(.vertical, 6)
        }
        .buttonStyle(.glass)
        .controlSize(.large)
        .buttonBorderShape(.capsule)
        .accessibilityIdentifier(identifier)
        .accessibilityHint(hint)
    }

    // MARK: - Recent Activity

    @ViewBuilder
    private var recentContent: some View {
        let items = recentItems
        if items.isEmpty {
            Group {
                if walletManager.mints.isEmpty {
                    NativeEmptyState(
                        title: "Add a mint to get started",
                        systemImage: "bitcoinsign.bank.building",
                        description: "Mints custody your ecash. Add one to begin.",
                        actionTitle: "Add mint",
                        action: { navigationManager.activeWalletSheet = .connectMint }
                    )
                } else {
                    // Same shared component, size, and centered placement as the
                    // History empty state, but with its own tray icon and copy
                    // (recent-activity framing vs. History's clock + "history"). No
                    // "Recent" header here: with nothing to label it's redundant,
                    // and dropping it matches History's clean full-screen empty state.
                    NativeEmptyState(
                        title: "No Activity Yet",
                        systemImage: "tray",
                        description: "Your recent payments will show up here."
                    )
                }
            }
            .containerRelativeFrame(.vertical)
            .padding(.horizontal, 16)
        } else {
            VStack(spacing: 0) {
                recentList(items)
                    .padding(.top, 8)
                    .padding(.horizontal, 16)
                    // Reduce motion: the list cross-fades as a whole instead
                    // of rows sliding to make room.
                    .id(reduceMotion ? items.map(\.id) : [])
                    .transition(.opacity)

                // Tail spacer so the last row can scroll under the
                // Liquid Glass tab bar without sitting flush against it.
                Color.clear.frame(height: 32)
            }
        }
    }

    private func recentList(_ items: [WalletTransaction]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            sectionHeader("Recent")

            ForEach(items) { item in
                TransactionRowView(transaction: item, mints: walletManager.mints) {
                    HapticFeedback.selection()
                    isDetailSheetUp = true
                    selectedTransaction = item
                }
            }

            Button(action: onViewAllHistory) {
                HStack(spacing: 4) {
                    Text("View all activity")
                    Image(systemName: "chevron.right").font(.caption2.weight(.semibold))
                }
                .font(.body.weight(.medium))
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 16)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Switches to the History tab")
        }
    }

    private func sectionHeader(_ title: String) -> some View {
        Text(title)
            .cashuText(.overline)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 4)
            .padding(.top, 16)
            .padding(.bottom, 14)
    }

    // MARK: - Recent payments

    /// The rows home shows: the held set while a beat is pending, each row
    /// rendered from the wallet's current copy of it.
    private var recentItems: [WalletTransaction] {
        guard let shown = arrival.shown else { return liveRecentItems }
        let live = Dictionary(
            walletManager.transactions.map { ($0.id, $0) },
            uniquingKeysWith: { first, _ in first }
        )
        return shown.rows.compactMap { live[$0.id] ?? arrival.cachedRow(id: $0.id) }
    }

    private var liveRecentItems: [WalletTransaction] {
        HomeActivity.recentTransactions(
            from: walletManager.transactions,
            limit: recentRowCap
        )
    }

    private var liveState: LiveHomeState {
        LiveHomeState(
            snapshot: HomeSnapshot(
                rows: liveRecentItems.map { .init(id: $0.id, isIncoming: $0.type == .incoming) },
                balancesByUnit: walletManager.balancesByUnit,
                satBalance: walletManager.balance
            ),
            loaded: walletManager.hasLoadedTransactions
        )
    }

    private var presence: HomePresence {
        if isHistoryTabSelected { return .seenElsewhere }
        let covered = !isRootOnScreen
            || navigationManager.isFlowSurfaceOpen
            || isDetailSheetUp
            || scenePhase != .active
            || appLock.isLocked
        return covered ? .covered : .onScreen
    }

    // MARK: - Transaction row (slimmer than HistoryView's variant)

    // MARK: - Helpers

    private func balanceDisplay(_ sats: UInt64) -> AmountDisplayText {
        AmountFormatter.displayText(
            amountSats: sats,
            preferredPrimary: settings.homeBalancePrimary,
            showFiat: settings.showFiatBalance,
            btcPrice: priceService.btcPriceUSD,
            currencyCode: settings.bitcoinPriceCurrency,
            useBitcoinSymbol: settings.useBitcoinSymbol
        )
    }

    private func balanceNumericValue(_ sats: UInt64, primary: AmountDisplayPrimary) -> Double {
        primary == .fiat ? priceService.satsToFiat(sats) : Double(sats)
    }

    private func balanceSecondaryNumericValue(_ sats: UInt64, display: AmountDisplayText) -> Double? {
        guard display.secondary != nil else { return nil }
        return display.effectivePrimary == .fiat ? Double(sats) : priceService.satsToFiat(sats)
    }

    @ViewBuilder
    private func sheetView(for sheet: WalletSheet) -> some View {
        switch sheet {
        case .receive:
            // UnifiedReceiveView mirrors UnifiedSendView: a content-fit input sheet
            // with Scan · Ecash · Bitcoin. A pasted/scanned *payable* is really a
            // Send, so it hands the destination back to the Send flow via `onSend`.
            UnifiedReceiveView(
                onClose: { navigationManager.activeWalletSheet = nil },
                onSend: { destination in
                    navigationManager.activeWalletSheet = .send(prefill: destination)
                },
                // A pasted/scanned token opens the full-screen claim page; the
                // sheet closes first (parked handoff), so its X lands on the
                // wallet — never back on this input sheet.
                onOpenReceiveToken: { token in
                    navigationManager.present(.cover(.receiveToken(token)))
                },
                onReceiveLightning: {
                    navigationManager.activeWalletSheet = .receiveLightning
                }
            )
            .environmentObject(walletManager)
        case .send(let prefill):
            unifiedSendSheet(initialDestination: prefill)
        case .sendEdit(let prefill):
            unifiedSendSheet(
                initialDestination: prefill,
                autoAdvanceInitialDestination: false
            )
        case .sendAmount(let destination, let explanation):
            unifiedSendSheet(
                initialAmountDestination: destination,
                initialRouteExplanation: explanation,
                onEditDestination: {
                    navigationManager.present(
                        .sheet(.sendEdit(prefill: destination.rawInput))
                    )
                },
                onTransferToMint: { mintURL, destination in
                    navigationManager.present(
                        .sheet(.mintTransfer(openedFromMintURL: mintURL, pendingPayment: destination))
                    )
                }
            )
        case .mintTransfer(let openedFromMintURL, let pendingPayment):
            // Handed off from Send's can't-pay face; Pay invoice hands back
            // to Send's payment sheet the same way.
            MintTransferView(
                openedFromMintURL: openedFromMintURL,
                pendingPayment: pendingPayment,
                onPayPending: { payment in
                    navigationManager.present(.sheet(.sendAmount(payment)))
                }
            )
            .environmentObject(walletManager)
        case .cashuRequestPay(let summary):
            CashuPaymentRequestPayView(
                request: summary,
                onComplete: { navigationManager.activeWalletSheet = nil },
                onViewHistory: viewHistoryFromSheet
            )
            .environmentObject(walletManager)
            .presentationDetents([.large])
            .presentationDragIndicator(.visible)
            .walletSheetSurface(fillsScreen: true)
        case .scanner:
            // The scanner self-dismisses on a successful read and hands the
            // payload back; the routed surface presents only after this sheet
            // is gone — the scanner is never left open underneath.
            ScannerWrapperView(
                onScanned: { payload in
                    navigationManager.routeScannedPayload(payload, walletManager: walletManager)
                },
                classify: { payload in
                    navigationManager.classifyScannedPayload(payload, walletManager: walletManager)
                }
            )
            .environmentObject(walletManager)
            .presentationDetents([.large])
            .canvasSheetBackground()
        case .sendEcash:
            // Swapped into the sheet from Send's method row, so there is no
            // stack to pop: X and swipe-down both abandon to the wallet.
            SendView()
                .environmentObject(walletManager)
                .presentationDetents([.large])
        case .receiveLightning:
            // The request owns its compact detent; amount entry remains large.
            ReceiveLightningView()
                .environmentObject(walletManager)
        case .connectMint:
            // Same surface the Send sheet shows when there are no mints — the
            // detents and canvas background live inside it.
            ConnectMintSheet()
                .environmentObject(walletManager)
        case .discoverMints:
            MintDiscoverySheet()
            .environmentObject(walletManager)
            .walletSheetSurface(fillsScreen: true)
        }
    }

    /// All Send faces share one implementation, but the payment steps (amount,
    /// confirm, status) live in a separate fixed-`.large` sheet presentation —
    /// the Receive-modal convention, with an X / back arrow for chrome.
    /// Dismissing the compact input before opening it avoids racing keyboard
    /// removal against a detent resize, and mirrors the Send Ecash handoff.
    private func unifiedSendSheet(
        initialDestination: String? = nil,
        initialAmountDestination: SendAmountDestination? = nil,
        initialRouteExplanation: CashuRequestRouteExplanation? = nil,
        autoAdvanceInitialDestination: Bool = true,
        onEditDestination: (() -> Void)? = nil,
        onTransferToMint: ((String, SendAmountDestination) -> Void)? = nil
    ) -> some View {
        UnifiedSendView(
            initialDestination: initialDestination,
            initialAmountDestination: initialAmountDestination,
            autoAdvanceInitialDestination: autoAdvanceInitialDestination,
            onClose: { navigationManager.activeWalletSheet = nil },
            onReceive: { navigationManager.activeWalletSheet = .receive },
            onContactless: {
                navigationManager.activeWalletSheet = nil
                contactlessCoordinator.start(
                    walletManager: walletManager,
                    navigationManager: navigationManager
                )
            },
            // A token pasted into Send is a receive: bounce it to the
            // full-screen claim page, closing the Send sheet first.
            onOpenReceiveToken: { token in
                navigationManager.present(.cover(.receiveToken(token)))
            },
            onSendEcash: { navigationManager.activeWalletSheet = .sendEcash },
            onRoutePayment: { destination, explanation in
                navigationManager.present(.sheet(.sendAmount(destination, explanation: explanation)))
            },
            onEditDestination: onEditDestination,
            onTransferToMint: onTransferToMint,
            onViewHistory: viewHistoryFromSheet,
            initialRouteExplanation: initialRouteExplanation
        )
        .environmentObject(walletManager)
    }

    /// Close the payment sheet on the History tab, where a payment the mint
    /// hasn't confirmed yet shows as pending and resolves.
    private func viewHistoryFromSheet() {
        navigationManager.activeWalletSheet = nil
        onViewAllHistory()
    }
}

private struct LiveHomeState: Equatable {
    let snapshot: HomeSnapshot
    let loaded: Bool
}

private struct TopInsetHeightKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) {
        value = nextValue()
    }
}

#Preview {
    MainWalletView()
        .environmentObject(WalletManager())
        .environmentObject(NavigationManager())
}
