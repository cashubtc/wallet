import SwiftUI

struct LightningAddressSettingsSection: View {
    @EnvironmentObject var walletManager: WalletManager
    @ObservedObject var npcService = NPCService.shared
    @ObservedObject private var settings = SettingsManager.shared
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    @Binding var isCheckingPayments: Bool
    @Binding var showMintPicker: Bool

    @State private var showAddressQR = false
    @State private var retryingSetup = false
    @State private var setupError: String?

    var body: some View {
        LazyVStack(spacing: 0) {
            SettingsSectionGroup("Lightning Address") {
                Toggle("Enable Lightning Address", isOn: $npcService.isEnabled)
                    .padding(.horizontal, 4)
                    .padding(.vertical, 14)

                if npcService.isEnabled && npcService.isInitialized {
                    addressRow
                }
            }

            statusFooter

            if npcService.isEnabled && npcService.isInitialized {
                SettingsSectionGroup("Preferences") {
                    Toggle("Auto-claim payments", isOn: $npcService.automaticClaim)
                        .padding(.horizontal, 4)
                        .padding(.vertical, 14)

                    if !walletManager.mints.isEmpty {
                        receivingMintRow
                    }
                }

                SettingsSectionFooter {
                    Text("Incoming payments are minted as ecash at your chosen mint.")
                }

                SettingsSectionGroup(nil) {
                    checkForPaymentsRow
                }

                if !settings.checkIncomingInvoices {
                    SettingsSectionFooter {
                        Text("To check for payments, allow incoming invoice checks in Privacy settings.")
                    }
                }
            } else if npcService.isEnabled && !npcService.isInitialized {
                SettingsSectionGroup(nil) {
                    VStack(spacing: 12) {
                        InlineNotice(
                            message: setupError ?? "Wallet not fully initialized. Try setup again to finish your Lightning address.",
                            severity: .error
                        )
                        Button {
                            guard !retryingSetup else { return }
                            retryingSetup = true
                            setupError = nil
                            Task { @MainActor in
                                defer { retryingSetup = false }
                                do {
                                    try await walletManager.retryLightningAddressSetup()
                                } catch {
                                    setupError = "Lightning address setup couldn't finish. Try again or restart the app."
                                }
                            }
                        } label: {
                            HStack {
                                if retryingSetup { ProgressView() }
                                Text("Try setup again")
                            }
                        }
                        .glassButton(prominent: true)
                        .disabled(retryingSetup)
                    }
                    .padding(.horizontal, 4)
                    .padding(.vertical, 14)
                }
            }
        }
        .task { await npcService.initializeIfEnabled() }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: npcService.isEnabled)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: npcService.errorMessage)
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.2), value: settings.checkIncomingInvoices)
        .backdropSheet(isPresented: $showMintPicker) {
            // Titled after the row that opened it, so the sheet reads as a
            // continuation of the tap rather than a new context.
            MintPickerSheet(
                title: "Receiving mint",
                mints: walletManager.mints,
                selectedMintUrl: $npcService.selectedMintUrl,
                onSelect: { mintUrl in
                    Task {
                        do {
                            try await npcService.changeMint(to: mintUrl)
                        } catch is CancellationError {
                            // The address was disabled or the wallet changed.
                        } catch {
                            npcService.errorMessage = ActionErrorMessages.message(for: error, context: .lightningMint)
                        }
                    }
                }
            )
        }
        .fullScreenCover(isPresented: $showAddressQR) {
            LightningAddressReceiveView(address: npcService.lightningAddress)
                .canvasSheetBackground()
        }
    }

    // MARK: - Status footer + helpers

    @ViewBuilder
    private var statusFooter: some View {
        if !npcService.isEnabled {
            SettingsSectionFooter {
                Text("Receive Lightning payments to your wallet using a Lightning address.")
            }
        } else if let error = npcService.errorMessage {
            SettingsSectionFooter {
                InlineNotice(message: error, severity: .error)
            }
        } else if !npcService.isInitialized && retryingSetup {
            SettingsSectionFooter {
                Text("Setting up Lightning address…")
            }
        }
    }

    // MARK: - Address row

    private var addressRow: some View {
        HStack(spacing: 8) {
            Button { HapticFeedback.selection(); showAddressQR = true } label: {
                Text(npcService.lightningAddress)
                    .font(.system(.body, design: .monospaced))
                    .foregroundStyle(.primary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Lightning address: \(npcService.lightningAddress)")
            .accessibilityHint("Shows QR code. Long-press for copy and share.")
            .contextMenu {
                Button(action: copyLightningAddress) {
                    Label("Copy address", systemImage: "doc.on.doc")
                }
                ShareLink(item: npcService.lightningAddress) {
                    Label("Share address", systemImage: "square.and.arrow.up")
                }
            }

            HStack(spacing: 0) {
                Button(action: copyLightningAddress) {
                    Image(systemName: "doc.on.doc")
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Copy Lightning address")

                Button { HapticFeedback.selection(); showAddressQR = true } label: {
                    Image(systemName: "qrcode")
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel("Show Lightning address QR code")
            }
            .font(.body.weight(.medium))
            .foregroundStyle(.secondary)
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 14)
    }

    // MARK: - Receiving Mint row

    private var receivingMintRow: some View {
        Button {
            HapticFeedback.selection()
            showMintPicker = true
        } label: {
            HStack(spacing: 12) {
                Text("Receiving mint")
                    .font(.body)
                    .foregroundStyle(.primary)

                Spacer(minLength: 8)

                Text(selectedMintDisplayName)
                    .font(.body)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
                    .truncationMode(.middle)

                Image(systemName: "chevron.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.tertiary)
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Receiving mint: \(selectedMintDisplayName)")
        .accessibilityHint("Choose which mint claims incoming Lightning payments")
    }

    private var selectedMintDisplayName: String {
        if let url = npcService.selectedMintUrl,
           let mint = walletManager.mints.first(where: { $0.url == url }) {
            return mint.name
        }
        return "Select a mint"
    }

    // MARK: - Check for Payments row

    private var checkForPaymentsRow: some View {
        Button(action: checkForPayments) {
            HStack(spacing: 14) {
                Group {
                    if isCheckingPayments {
                        ProgressView()
                            .controlSize(.small)
                    } else {
                        Image(systemName: "arrow.clockwise")
                            .font(.body.weight(.semibold))
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(width: 28)

                VStack(alignment: .leading, spacing: 2) {
                    Text("Check for payments")
                        .font(.body)
                        .foregroundStyle(.primary)
                    Text(lastCheckedCaption)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }

                Spacer(minLength: 8)
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isCheckingPayments || !settings.checkIncomingInvoices)
        .opacity(settings.checkIncomingInvoices ? 1.0 : 0.5)
        .accessibilityLabel("Check for new payments. \(lastCheckedCaption).")
    }

    private var lastCheckedCaption: String {
        if let lastCheck = npcService.lastCheck {
            return "Last checked \(formatRelativeTime(lastCheck))"
        }
        return "Not checked yet"
    }

    // MARK: - Actions

    private func copyLightningAddress() {
        UIPasteboard.general.string = npcService.lightningAddress
        HapticFeedback.selection()
        ConfirmationToast.show("Copied Lightning address")
    }

    private func checkForPayments() {
        isCheckingPayments = true
        HapticFeedback.selection()
        Task {
            await npcService.checkAndClaimPayments()
            await MainActor.run {
                isCheckingPayments = false
            }
        }
    }

    private func formatRelativeTime(_ date: Date) -> String {
        let formatter = RelativeDateTimeFormatter()
        formatter.unitsStyle = .abbreviated
        return formatter.localizedString(for: date, relativeTo: Date())
    }
}

/// Shared by Lightning settings and Receive Bitcoin; this modal owns
/// focused polling, so copy/share and dismissal keep their existing behavior.
struct LightningAddressReceiveView: View {
    let address: String
    @Environment(\.dismiss) private var dismiss
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject private var npcService = NPCService.shared
    @ObservedObject private var settings = SettingsManager.shared
    @State private var openedAt = Date()
    @State private var receipt: NPCPaymentReceipt?
    @State private var pendingReceipt: NPCPaymentReceipt?

    var body: some View {
        LightningAddressReceiveContent(
            address: address,
            receivedAmount: receipt.map { AmountFormatter.sats($0.amount, useBitcoinSymbol: settings.useBitcoinSymbol) },
            statusMessage: statusMessage
        )
        .task(id: scenePhase == .active && receipt == nil) {
            guard scenePhase == .active, receipt == nil else { return }
            await npcService.monitorPayments(address: address, openedAt: openedAt)
        }
        .onReceive(NotificationCenter.default.publisher(for: .npcPaymentReceived)) { notification in
            guard receipt == nil, let payment = notification.userInfo?["receipt"] as? NPCPaymentReceipt,
                  payment.belongsToReceiveSession(address: address, openedAt: openedAt) else { return }
            receipt = payment
        }
        .onReceive(NotificationCenter.default.publisher(for: .npcPaymentPending)) { notification in
            guard let payment = notification.userInfo?["receipt"] as? NPCPaymentReceipt,
                  payment.belongsToReceiveSession(address: address, openedAt: openedAt) else { return }
            pendingReceipt = payment
        }
        .onChange(of: npcService.isEnabled) { _, enabled in if !enabled { dismiss() } }
        .onChange(of: npcService.lightningAddress) { _, value in if value != address { dismiss() } }
    }

    private var statusMessage: String? {
        if !settings.checkIncomingInvoices {
            return "Payment checks are off in Privacy settings."
        }
        if let error = npcService.errorMessage { return error }
        if !npcService.automaticClaim {
            if let pendingReceipt {
                return "Payment detected: \(AmountFormatter.sats(pendingReceipt.amount, useBitcoinSymbol: settings.useBitcoinSymbol)). Auto-claim is off."
            }
            return "Auto-claim is off. Enable it in Lightning settings to add payments to your wallet."
        }
        return nil
    }
}

/// The modal and its navigation chrome stay mounted while only the body changes.
struct LightningAddressReceiveContent: View {
    let address: String
    var receivedAmount: String? = nil
    var statusMessage: String? = nil
    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        NavigationStack {
            Group {
                if let receivedAmount {
                    PaymentStatusView(
                        details: [.init(label: "Amount", isAmount: true, value: receivedAmount)],
                        phase: .success,
                        successTitle: "Payment Received!",
                        onDone: { dismiss() },
                        onRetry: {}
                    )
                    .accessibilityIdentifier("lightning-address-payment-received")
                    .transition(.opacity)
                } else {
                    waitingContent.transition(.opacity)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .animation(reduceMotion ? nil : .smooth(duration: 0.3), value: receivedAmount != nil)
            .navigationTitle("Lightning Address")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) { SheetCloseButton() }
            }
        }
        .onChange(of: receivedAmount) { _, amount in
            if let amount {
                AccessibilityNotification.Announcement("Payment received. \(amount)").post()
            }
        }
    }

    private var waitingContent: some View {
        GeometryReader { geometry in
            ScrollView {
                VStack(spacing: 24) {
                    QRCodeView(content: address, showControls: false)
                        .padding()
                        .frame(width: min(280, geometry.size.width - 48),
                               height: min(280, geometry.size.width - 48))
                        .background(Color.white)
                        .clipShape(.rect(cornerRadius: 16))
                    Text(address)
                        .font(.system(.subheadline, design: .monospaced))
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .padding(.horizontal, 24)
                        .accessibilityLabel("Lightning address")
                        .accessibilityValue(address)
                    if let statusMessage {
                        Text(statusMessage)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.horizontal, 24)
                    }
                }
                .frame(maxWidth: .infinity, minHeight: geometry.size.height)
            }
            .scrollBounceBehavior(.basedOnSize)
        }
        .safeAreaInset(edge: .bottom) {
            HStack(spacing: 12) {
                Button("Copy") {
                    UIPasteboard.general.string = address
                    ConfirmationToast.show("Copied lightning address")
                }
                .flatSheetSecondaryButton()
                ShareLink(item: address) { Text("Share") }
                    .glassButton(prominent: true)
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 16)
        }
    }
}
