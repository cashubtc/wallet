import SwiftUI
import AVFoundation
import Cdk
import os

enum ScannerFrameOutcome: Equatable, Sendable {
    case progress(Double, generation: UInt64)
    case frameError(String, generation: UInt64)
    case completed(String, fromAnimatedUR: Bool, generation: UInt64)

    var generation: UInt64 {
        switch self {
        case .progress(_, let generation),
             .frameError(_, let generation),
             .completed(_, _, let generation):
            return generation
        }
    }
}

/// Decodes QR payloads (including NUT-16 `ur:` animated sequences via CDK's
/// `TokenUrDecoder`). Confined to `ScannerViewModel.metadataQueue` so CDK FFI
/// never runs on the main thread; `@unchecked Sendable` reflects that
/// serial-queue confinement.
final class ScannerFrameProcessor: @unchecked Sendable {
    private var decoder: TokenUrDecoder?
    private var isAcceptingFrames = true
    private var generation: UInt64 = 0

    func reset(generation: UInt64) {
        self.generation = generation
        decoder = nil
        isAcceptingFrames = true
    }

    func disarm() {
        isAcceptingFrames = false
    }

    func process(code: String) -> ScannerFrameOutcome? {
        guard isAcceptingFrames else { return nil }
        let content = code.trimmingCharacters(in: .whitespacesAndNewlines)

        if content.lowercased().hasPrefix("ur:") {
            let activeDecoder: TokenUrDecoder
            if let existing = decoder {
                activeDecoder = existing
            } else {
                let created = TokenUrDecoder()
                decoder = created
                activeDecoder = created
            }

            do {
                try activeDecoder.receive(part: content)
            } catch {
                return .frameError("Couldn't read that animated QR frame.", generation: generation)
            }

            let total = activeDecoder.fragmentCount()
            let progress: Double?
            if total > 0, let resolved = activeDecoder.resolvedFragmentCount() {
                progress = min(Double(resolved) / Double(total), 0.99)
            } else {
                progress = nil
            }

            guard activeDecoder.complete() else {
                return progress.map { .progress($0, generation: generation) }
            }

            do {
                guard let token = try activeDecoder.token() else {
                    return progress.map { .progress($0, generation: generation) }
                }
                let encoded = token.encode()
                isAcceptingFrames = false
                return .completed(encoded, fromAnimatedUR: true, generation: generation)
            } catch {
                return .frameError("That animated QR doesn't contain a Cashu token.", generation: generation)
            }
        } else {
            decoder = nil
            isAcceptingFrames = false
            return .completed(content, fromAnimatedUR: false, generation: generation)
        }
    }
}

@MainActor
final class ScannerViewModel: ObservableObject {
    struct CompletedScan: Equatable, Sendable {
        let id = UUID()
        let content: String
        let fromAnimatedUR: Bool
    }

    @Published var scanProgress: Double = 0
    @Published var isScanning = true
    @Published var errorMessage: String?
    /// Severity of `errorMessage`. `.error` paints the alarm-red toast; `.info`
    /// renders a neutral material so a success confirmation (e.g. "copied") isn't
    /// styled as a failure.
    // Caution, not error: a QR that isn't what we wanted didn't break
    // anything. Genuine failures raise this explicitly.
    @Published var noticeSeverity: ErrorSeverity = .caution
    @Published private(set) var completedScan: CompletedScan?

    /// Dedicated serial background queue for `AVCaptureMetadataOutput` callbacks
    /// and `TokenUrDecoder` FFI decoding so neither runs on Main.
    nonisolated let metadataQueue = DispatchQueue(
        label: "com.cashu.me.qr-scanner-metadata",
        qos: .userInitiated
    )

    /// Confined to `metadataQueue`.
    private nonisolated let processor = ScannerFrameProcessor()
    private var scanGeneration: UInt64 = 0
    private var scheduledTask: Task<Void, Never>?

    func reset() {
        scheduledTask?.cancel()
        scheduledTask = nil
        scanGeneration &+= 1
        let generation = scanGeneration
        scanProgress = 0
        isScanning = true
        errorMessage = nil
        noticeSeverity = .caution
        completedScan = nil
        let processor = self.processor
        metadataQueue.async {
            processor.reset(generation: generation)
        }
    }

    func disarm() {
        scheduledTask?.cancel()
        scheduledTask = nil
        scanGeneration &+= 1
        isScanning = false
        let processor = self.processor
        metadataQueue.async {
            processor.disarm()
        }
    }

    func scheduleReset(after duration: Duration) {
        scheduledTask?.cancel()
        scheduledTask = Task { [weak self] in
            try? await Task.sleep(for: duration)
            guard !Task.isCancelled else { return }
            self?.reset()
        }
    }

    func scheduleDismiss(after duration: Duration, action: @escaping @MainActor () -> Void) {
        scheduledTask?.cancel()
        scheduledTask = Task {
            try? await Task.sleep(for: duration)
            guard !Task.isCancelled else { return }
            action()
        }
    }

    func cancelScheduledTasks() {
        scheduledTask?.cancel()
        scheduledTask = nil
    }

    /// Invoked directly on `metadataQueue` by `AVCaptureMetadataOutput`; runs
    /// `TokenUrDecoder` FFI off Main and dispatches only UI updates to Main.
    nonisolated func handleMetadataCode(_ code: String) {
        guard let outcome = processor.process(code: code) else { return }
        DispatchQueue.main.async { [weak self] in
            MainActor.assumeIsolated {
                self?.apply(outcome)
            }
        }
    }

    private func apply(_ outcome: ScannerFrameOutcome) {
        guard outcome.generation == scanGeneration else { return }
        switch outcome {
        case .progress(let progress, _):
            scanProgress = progress
            errorMessage = nil
        case .frameError(let message, _):
            noticeSeverity = .caution
            errorMessage = message
        case .completed(let content, let fromAnimatedUR, _):
            guard isScanning else { return }
            completedScan = CompletedScan(content: content, fromAnimatedUR: fromAnimatedUR)
        }
    }
}

enum CameraAuthorizationState: Equatable, Sendable {
    case checking
    case requesting
    case authorized
    case denied
    case restricted

    static func from(
        authorizationStatus: AVAuthorizationStatus,
        usesTestCamera: Bool = false
    ) -> CameraAuthorizationState {
        if usesTestCamera {
            return .authorized
        }
        switch authorizationStatus {
        case .authorized:
            return .authorized
        case .notDetermined:
            return .checking
        case .denied:
            return .denied
        case .restricted:
            return .restricted
        @unknown default:
            return .restricted
        }
    }
}

private struct ScannerCameraStatusView: View {
    let title: String
    let message: String
    var actionTitle: String?
    var showsProgress = false
    var action: (() -> Void)?

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            VStack(spacing: 16) {
                Image(systemName: "camera.fill")
                    .font(.system(size: 42))
                    .accessibilityHidden(true)

                Text(title)
                    .font(.title2.weight(.semibold))

                Text(message)
                    .font(.body)
                    .multilineTextAlignment(.center)
                    .foregroundStyle(.white.opacity(0.75))

                if showsProgress {
                    ProgressView()
                        .tint(.white)
                        .accessibilityLabel("Requesting camera access")
                }

                if let actionTitle, let action {
                    Button(action: action) {
                        Text(actionTitle)
                            .font(.body.weight(.semibold))
                            .foregroundStyle(.black)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 16)
                            .background(.white, in: Capsule())
                    }
                    .buttonStyle(PressableButtonStyle())
                    .frame(maxWidth: 320)
                }
            }
            .foregroundStyle(.white)
            .padding(32)
            .frame(maxWidth: 440)
            .accessibilityElement(children: .contain)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

struct ScannerWrapperView: View {
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @Environment(\.scenePhase) private var scenePhase

    /// Handles a scanned or quick-filled payload in a single pass. `.accept`
    /// dismisses the scanner; `.reject` shows the message inline and re-arms
    /// (junk QR stays scannable); `.notice` shows a neutral toast and closes
    /// (any side effect, e.g. copying a mint URL, belongs to the handler).
    private let handleIntake: (String) -> ScanIntake

    /// Optional override for the instruction shown under the viewfinder.
    private let promptText: String?

    /// Optional quick-fill chips rendered over the camera (e.g. "Lock to my
    /// key", "Paste key"). Evaluated on appear (and foreground return) using
    /// presence checks only; each chip resolves its payload lazily on tap so
    /// opening the scanner never reads the clipboard.
    private let quickFills: (() -> [ScannerQuickFill])?

    struct ScannerQuickFill: Identifiable {
        let id = UUID()
        let title: String
        let systemImage: String
        let resolveValue: () -> String?

        init(title: String, systemImage: String, value: String) {
            self.title = title
            self.systemImage = systemImage
            self.resolveValue = { value }
        }

        init(title: String, systemImage: String, resolveValue: @escaping () -> String?) {
            self.title = title
            self.systemImage = systemImage
            self.resolveValue = resolveValue
        }
    }

    @StateObject private var scannerModel = ScannerViewModel()
    @State private var resolvedQuickFills: [ScannerQuickFill] = []
    @State private var cameraAuthorizationState: CameraAuthorizationState
    @State private var cameraFailureMessage: String?

    private static var defaultUsesTestCamera: Bool {
        #if DEBUG
        IntegrationTestConfig.shouldUseDeterministicUIRuntime
        #else
        false
        #endif
    }

    private var usesTestCamera: Bool { Self.defaultUsesTestCamera }

    init(
        onScanned: @escaping (String) -> Void,
        promptText: String? = nil,
        quickFills: (() -> [ScannerQuickFill])? = nil
    ) {
        self.handleIntake = { content in
            onScanned(content)
            return .accept
        }
        self.promptText = promptText
        self.quickFills = quickFills
        _cameraAuthorizationState = State(
            initialValue: CameraAuthorizationState.from(
                authorizationStatus: AVCaptureDevice.authorizationStatus(for: .video),
                usesTestCamera: Self.defaultUsesTestCamera
            )
        )
    }

    init(
        onIntake: @escaping (String) -> ScanIntake,
        promptText: String? = nil,
        quickFills: (() -> [ScannerQuickFill])? = nil
    ) {
        self.handleIntake = onIntake
        self.promptText = promptText
        self.quickFills = quickFills
        _cameraAuthorizationState = State(
            initialValue: CameraAuthorizationState.from(
                authorizationStatus: AVCaptureDevice.authorizationStatus(for: .video),
                usesTestCamera: Self.defaultUsesTestCamera
            )
        )
    }

    var body: some View {
        NavigationStack {
            ZStack {
                Color.black.ignoresSafeArea()

                switch cameraAuthorizationState {
                case .checking, .requesting:
                    ScannerCameraStatusView(
                        title: "Camera Access",
                        message: "Waiting for permission to use the camera.",
                        showsProgress: true
                    )
                case .authorized:
                    if let cameraFailureMessage {
                        ScannerCameraStatusView(
                            title: "Camera Unavailable",
                            message: cameraFailureMessage,
                            actionTitle: "Try Again",
                            action: retryCamera
                        )
                    } else if usesTestCamera {
                        // Keep native scanner controls and routing; substitute
                        // only hardware capture in simulator UI journeys.
                        Text("Camera Ready").foregroundStyle(.white)
                    } else {
                        LegacyQRScannerView(
                            metadataQueue: scannerModel.metadataQueue,
                            onResult: { [scannerModel] code in
                                scannerModel.handleMetadataCode(code)
                            },
                            onFailure: { error in
                                cameraFailureMessage = error
                            },
                            onAppear: {
                                // A visible scanner must be an armed scanner.
                                // The camera surface can (re)appear without a
                                // fresh model — a cancelled interactive sheet
                                // dismissal, a stacked cover closing, or SwiftUI
                                // reusing retained sheet state — and without this
                                // reset the preview looks live while `isScanning`
                                // stays false and every scan is silently dropped.
                                scannerModel.reset()
                            }
                        )
                        .ignoresSafeArea()
                    }
                case .denied:
                    ScannerCameraStatusView(
                        title: "Camera Access Needed",
                        message: "Camera access is turned off. Enable it in Settings to scan QR codes.",
                        actionTitle: "Open Settings",
                        action: openCameraSettings
                    )
                case .restricted:
                    ScannerCameraStatusView(
                        title: "Camera Unavailable",
                        message: "Camera access is restricted on this device. Check Screen Time or device-management settings."
                    )
                }

                // Overlay
                if cameraAuthorizationState == .authorized && cameraFailureMessage == nil {
                    VStack {
                        Spacer()

                        if scannerModel.scanProgress > 0 && scannerModel.scanProgress < 1.0 {
                            // Progress UI for animated QR
                            VStack(spacing: 8) {
                                Text("Scanning Animated QR...")
                                    .font(.headline)
                                    .foregroundStyle(.white)

                                ProgressView(value: scannerModel.scanProgress, total: 1.0)
                                    .progressViewStyle(LinearProgressViewStyle(tint: .accentColor))
                                    .frame(height: 8)
                                    .padding(.horizontal)

                                Text("\(Int(scannerModel.scanProgress * 100))%")
                                    .font(.caption)
                                    .foregroundStyle(.white.opacity(0.8))
                            }
                            .padding()
                            .background(Color.black.opacity(0.8))
                            .clipShape(.rect(cornerRadius: 16))
                            .padding(.bottom, 50)
                            .padding(.horizontal, 40)
                        } else {
                            VStack(spacing: 12) {
                                ForEach(resolvedQuickFills) { fill in
                                    Button {
                                        HapticFeedback.selection()
                                        guard let value = fill.resolveValue()?.trimmingCharacters(in: .whitespacesAndNewlines),
                                              !value.isEmpty else {
                                            return
                                        }
                                        processCompleteContent(value)
                                    } label: {
                                        HStack(spacing: 8) {
                                            Image(systemName: fill.systemImage)
                                            Text(fill.title)
                                        }
                                        .font(.subheadline.weight(.semibold))
                                        .foregroundStyle(.white)
                                        .padding(.horizontal, 18)
                                        .padding(.vertical, 11)
                                        .background(.ultraThinMaterial, in: Capsule())
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityHint("Use this instead of scanning")
                                }

                                Text(promptText ?? "Scan Cashu Token, Payment Request, or Bitcoin Address")
                                    .foregroundStyle(.white)
                                    .font(.caption)
                                    .padding()
                                    .background(Color.black.opacity(0.6))
                                    .clipShape(.rect(cornerRadius: 20))
                            }
                            .padding(.horizontal, 24)
                            .padding(.bottom, 50)
                        }
                    }
                }
                
                if let error = scannerModel.errorMessage {
                    VStack {
                        Spacer()
                        // Floats over the camera preview, so it is the floating
                        // channel's own component. This screen used to hand-roll
                        // the banner's exact body — material, glyph, combined
                        // accessibility element — which is how it drifted to a
                        // solid red slab in the first place. Position it; don't
                        // restyle it.
                        ErrorBannerView(
                            message: error,
                            severity: scannerModel.noticeSeverity
                        )
                        .padding(.horizontal)
                        .padding(.bottom, 100)
                    }
                }
            }
            // The scanner canvas is always dark, independent of the app's
            // appearance. Resolve materials and semantic foregrounds against
            // that surface so light mode cannot produce dark-on-dark labels.
            .environment(\.colorScheme, .dark)
            .navigationTitle("Scan QR Code")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarColorScheme(.dark, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    SheetCloseButton()
                        .foregroundStyle(.white)
                }
            }
            .onAppear {
                resolvedQuickFills = quickFills?() ?? []
                refreshCameraAuthorization()
            }
            .onDisappear {
                scannerModel.cancelScheduledTasks()
            }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active {
                    resolvedQuickFills = quickFills?() ?? []
                    refreshCameraAuthorization()
                }
            }
            .onChange(of: scannerModel.completedScan) { _, completed in
                guard let completed else { return }
                if completed.fromAnimatedUR {
                    let generator = UIImpactFeedbackGenerator(style: .medium)
                    generator.impactOccurred()
                }
                processCompleteContent(completed.content)
            }
        }
    }

    private func refreshCameraAuthorization() {
        let nextState = CameraAuthorizationState.from(
            authorizationStatus: AVCaptureDevice.authorizationStatus(for: .video),
            usesTestCamera: usesTestCamera
        )
        if nextState == .checking {
            requestCameraAuthorization()
        } else {
            cameraAuthorizationState = nextState
        }
    }

    private func requestCameraAuthorization() {
        guard cameraAuthorizationState != .requesting else { return }
        cameraAuthorizationState = .requesting
        Task { @MainActor in
            let granted = await AVCaptureDevice.requestAccess(for: .video)
            cameraAuthorizationState = granted ? .authorized : .denied
        }
    }

    private func openCameraSettings() {
        guard let settingsURL = URL(string: UIApplication.openSettingsURLString) else { return }
        openURL(settingsURL)
    }

    private func retryCamera() {
        cameraFailureMessage = nil
        refreshCameraAuthorization()
    }

    private func processCompleteContent(_ content: String) {
        guard scannerModel.isScanning else { return }
        scannerModel.disarm()

        switch handleIntake(content) {
        case .accept:
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.success)
            dismiss()
        case .reject(let message):
            scannerModel.errorMessage = message
            HapticFeedback.notification(.error)
            scannerModel.scheduleReset(after: .seconds(2))
        case .notice(let message):
            let generator = UINotificationFeedbackGenerator()
            generator.notificationOccurred(.success)
            // A confirmation, not a failure — render it on a neutral toast.
            scannerModel.noticeSeverity = .info
            scannerModel.errorMessage = message
            scannerModel.scheduleDismiss(after: .milliseconds(1500)) {
                dismiss()
            }
        }
    }
}

/// Native detail row used whenever a Cashu Request changes rail or enters a
/// recovery route. Grouping keeps VoiceOver's route label and value together.
struct CashuRequestRouteExplanationRow: View {
    let explanation: CashuRequestRouteExplanation

    var body: some View {
        PaymentDetailPair(label: "Route") {
            Text(explanation.localizedValue)
                .fontWeight(.regular)
                .truncationMode(.tail)
        }
        .paymentDetailRow()
        .accessibilityElement(children: .combine)
    }
}

struct CashuPaymentRequestPayView: View {
    let request: CashuPaymentRequestSummary
    var onComplete: (() -> Void)?
    /// Closes the sheet on the History tab, where an unconfirmed payment resolves.
    var onViewHistory: (() -> Void)?

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var walletManager: WalletManager
    @ObservedObject private var settings = SettingsManager.shared
    @ObservedObject private var priceService = PriceService.shared

    @State private var customAmountString = ""
    @State private var isPaying = false
    @State private var errorMessage: String?
    @State private var errorSeverity: ErrorSeverity = .error
    /// Drives the full-screen processing → success → failure status screen.
    /// nil while the user is still on the confirm screen.
    @State private var paymentPhase: PaymentStatusView.Phase?
    /// The failed payment left the wallet but the mint hasn't confirmed it yet.
    @State private var paymentAwaitsMint = false
    @State private var sendRecovery: PendingSendRecovery?
    @State private var activeRouteExplanation: CashuRequestRouteExplanation?
    @State private var showingMintPicker = false
    @State private var selectedMint: MintInfo?

    /// Which requested mint URL to add & fund when the request can't be paid from
    /// current ecash and it names more than one mint (the user picks).
    @State private var selectedAddMintURL: String?
    @State private var addMintChooserPresented = false
    /// Set when no held mint can fund the transfer — drives the Lightning top-up QR.
    @State private var topUpContext: TopUpContext?

    @State private var feeState: FeeState = .idle
    @State private var feeTask: Task<Void, Never>?
    /// Cache of each mint's input-fee ppk so live amount entry doesn't refetch
    /// keysets on every keystroke; ppk doesn't depend on the amount.
    @State private var feePpkByMint: [String: UInt64] = [:]

    /// Resolved fee for the current mint + amount. `.free` is exact (the mint
    /// charges no swap fee); `.amount` is the exact fee for a fee-charging mint.
    private enum FeeState: Equatable {
        case idle        // no amount yet — nothing to price
        case loading
        case free
        case amount(UInt64)
        case unavailable // couldn't determine
    }

    var body: some View {
        NavigationStack {
            Group {
              if let sendRecovery {
                PendingSendRecoveryView(recovery: sendRecovery, onDone: { dismiss() }, onWorkingChanged: { isPaying = $0 })
              } else if let paymentPhase {
                statusView(paymentPhase)
                    .transition(.opacity)
              } else {
                // Keep the source selector with the amount, as in Receive.
                // Amount entry reserves its keypad in the footer.
                PayFlowScaffold(
                    contentLayout: request.amount == nil ? .centered : .anchoredScrollable
                ) {
                    VStack(spacing: 8) {
                        if showsMintIdentityHeader {
                            mintHeader
                                .padding(.horizontal)
                        }
                        amountSection
                        if request.amount != nil,
                           request.isSatUnit,
                           let selected = pickerSelectedMint {
                            paymentMintSelector(selected)
                                .padding(.horizontal, NumberPadMetrics.gutter)
                        }
                    }
                } details: {
                    requestDetailsSection

                    // Transient warnings sit below the request facts (flexible zone)
                    // so they never push the details anchor.
                    if !request.isSatUnit {
                        InlineNotice(
                            message: "This wallet can only pay sat-denominated Cashu Requests.",
                            severity: .caution
                        )
                        .padding(.top, 12)
                        .padding(.horizontal)
                    }

                    if let errorMessage {
                        InlineNotice(message: errorMessage, severity: errorSeverity)
                            .padding(.top, 12)
                            .padding(.horizontal)
                    }
                } footer: {
                    VStack(spacing: 16) {
                        if request.amount == nil {
                            VStack(spacing: 8) {
                                if request.isSatUnit {
                                    amountEntryFeeRow

                                    if let selected = selectedPaymentMint {
                                        paymentMintSelector(selected, isAmountEntry: true)
                                    }
                                }

                                NumberPadAmountInput(amountString: $customAmountString, unit: entryUnit)
                            }
                            .padding(.horizontal, NumberPadMetrics.gutter)
                        }

                        Button(action: payRequest) {
                            LoadingButtonLabel(title: payButtonTitle, isLoading: isPaying)
                        }
                        .flatSheetSecondaryButton()
                        .disabled(!canPay)
                        .accessibilityLabel(payButtonTitle)
                        .accessibilityValue(isPaying ? "In progress" : "")
                        .padding(.horizontal)
                        .padding(.bottom, 16)
                        .sheet(isPresented: $addMintChooserPresented) {
                            AddMintToPaySheet(mints: request.mints) { mintURL in
                                selectedAddMintURL = mintURL
                                if let amount = paymentAmount, amount > 0 {
                                    runAcquireAndPay(targetMintURL: mintURL, amount: amount)
                                }
                            }
                            .environmentObject(walletManager)
                        }
                    }
                }
              }
            }
            .animation(.smooth(duration: 0.3), value: paymentPhase != nil)
            .navigationTitle("Pay Cashu Request")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    // No dismissing mid-authorization (payment is in flight).
                    if paymentPhase != .processing {
                        SheetCloseButton()
                    }
                }
            }
            .sheet(isPresented: $showingMintPicker) {
                MintSelectorSheet(
                    selectedMint: $selectedMint,
                    mints: candidateMints,
                    minimumAmount: paymentAmount,
                    onSelect: { mint in
                        selectedMint = mint
                        errorMessage = nil
                    }
                )
                .environmentObject(walletManager)
                .presentationDetents([.medium])
            }
            .sheet(item: $topUpContext) { context in
                CashuTopUpInvoiceSheet(context: context, onComplete: {
                    topUpContext = nil
                    onComplete?()
                    dismiss()
                })
                .environmentObject(walletManager)
                .canvasSheetBackground()
            }
            .onAppear {
                syncSelectedMint()
                recomputeFee()
            }
            .onChange(of: customAmountString) {
                syncSelectedMint()
            }
            .onChange(of: walletManager.activeMint?.id) {
                syncSelectedMint()
            }
            .onChange(of: selectedPaymentMint?.id) {
                recomputeFee()
            }
            .onChange(of: paymentAmount) {
                recomputeFee()
            }
            .onChange(of: entryUnit) { oldUnit, newUnit in
                customAmountString = AmountFormatter.entryConverted(raw: customAmountString, from: oldUnit, to: newUnit)
            }
            .onDisappear {
                feeTask?.cancel()
            }
        }
        // Preserve native swipe-to-dismiss everywhere except the brief interval
        // where proofs are being reserved or delivered.
        .interactiveDismissDisabled(isPaying)
    }

    private var showsMintIdentityHeader: Bool {
        guard request.isSatUnit else { return false }
        if request.amount == nil {
            return selectedPaymentMint == nil
        }
        return pickerSelectedMint == nil
    }

    private func paymentMintSelector(_ mint: MintInfo, isAmountEntry: Bool = false) -> some View {
        let onUseMax: (() -> Void)? = isAmountEntry && mint.balance > 0 ? {
            useMaximumAmount(from: mint)
        } : nil

        return AmountEntryMintSelector(
            direction: .source,
            mint: mint,
            balanceText: isAmountEntry ? AmountFormatter.sats(
                mint.balance,
                useBitcoinSymbol: settings.useBitcoinSymbol
            ) : nil,
            onUseMax: onUseMax,
            onChooseMint: candidateMints.count > 1 ? {
                HapticFeedback.selection()
                showingMintPicker = true
            } : nil
        )
    }

    private func useMaximumAmount(from mint: MintInfo) {
        HapticFeedback.selection()
        customAmountString = AmountFormatter.entryConverted(
            raw: String(mint.balance),
            from: .sats,
            to: entryUnit
        )
    }

    @ViewBuilder
    private var amountSection: some View {
        if let amount = request.amount, request.isSatUnit {
            CurrencyAmountDisplay(
                sats: amount,
                primary: $settings.amountDisplayPrimary
            )
        } else if let amount = request.amount {
            VStack(spacing: 6) {
                Text("\(amount)")
                    .cashuAmount(.amountHero, value: Double(amount))
                Text(request.unit ?? "unknown unit")
                    .font(.headline)
                    .foregroundStyle(.secondary)
            }
        } else {
            CurrencyAmountDisplay(
                sats: customAmountSats,
                primary: $settings.amountDisplayPrimary,
                entryRaw: customAmountString
            )
        }
    }

    /// A requester-supplied memo, shown as a read-only "Memo" row in the
    /// request-details section under the amount. Nil when the request has no
    /// real description — the old screen rendered a hardcoded "Cashu payment"
    /// placeholder, which said nothing.
    private var requestMemo: String? {
        guard let description = request.description?.trimmingCharacters(in: .whitespacesAndNewlines),
              !description.isEmpty else { return nil }
        return description
    }

    /// How the request's mint is offered, derived from `request.mints`
    /// (empty = any, 1 = required, ≥2 = alternatives) and what the user holds.
    private enum MintPresentation {
        /// Flexible / multi-mint request with ≥1 qualifying held mint — generic
        /// header plus a switchable From row carrying `selected`.
        case picker(mints: [MintInfo], selected: MintInfo)
        /// Exactly one required mint that the user holds — named in the header,
        /// no From row (there's nothing to switch).
        case fixed(MintInfo)
        /// No usable held mint — warning header, Pay disabled.
        /// `requiredHosts` is the requested mint host(s); empty means an
        /// any-mint request and the user holds no mints at all.
        case unavailable(requiredHosts: [String])
    }

    private var mintPresentation: MintPresentation {
        // `selectedPaymentMint` is nil exactly when `candidateMints` is empty,
        // i.e. no held mint satisfies the request — covers both a required mint
        // we don't hold and a flexible request we can't service.
        guard let selected = selectedPaymentMint else {
            return .unavailable(requiredHosts: request.mints.map(extractMintHost))
        }
        // The count check sits after the nil-guard, so a single required mint we
        // *don't* hold falls through to `.unavailable`, not `.fixed`.
        if request.mints.count == 1 {
            return .fixed(selected)
        }
        return .picker(mints: candidateMints, selected: selected)
    }

    private enum HeaderIcon {
        case mint(MintInfo)   // a mint we hold — show its avatar
        case generic          // any-mint / unavailable — generic mint glyph
    }

    /// The mint-identity header derived from how the request constrains the
    /// mint. `.fixed` shows the pinned mint; `.picker` shows a generic "Any
    /// mint" / "Multiple mints" badge (the actual source sits in the From row);
    /// `.unavailable` shows a warning because the user can't pay.
    private var headerContent: (icon: HeaderIcon, name: String, subtitle: String, isWarning: Bool) {
        switch mintPresentation {
        case .fixed(let mint):
            if needsAcquire {
                return (.mint(mint), mint.name, "Balance too low — fund to pay", false)
            }
            return (.mint(mint), mint.name, "Required mint", false)
        case .picker:
            // Unreachable — the any/multi case now renders the top mint pill, not
            // this header (mintHeader is only shown when pickerSelectedMint == nil).
            // Kept for switch exhaustiveness.
            if request.mints.isEmpty {
                return (.generic, "Any mint", "Pay from any mint", false)
            } else {
                return (.generic, "Multiple mints", "Pay from one of \(request.mints.count)", false)
            }
        case .unavailable(let hosts):
            // Recoverable: we can add the required mint and fund it — not a warning.
            if needsAcquire {
                if hosts.count == 1, let host = hosts.first {
                    return (.generic, host, "Tap Add & pay to fund it", false)
                }
                return (.generic, "Add a mint", "This request accepts \(hosts.count) mints", false)
            }
            if hosts.isEmpty {
                return (.generic, "Any mint", "Add a mint to pay", true)
            } else if hosts.count == 1 {
                return (.generic, hosts[0], "Not in your wallet", true)
            } else {
                return (.generic, "Multiple mints", "You hold none of these", true)
            }
        }
    }

    /// The selected paying mint for an any/multi-mint (`.picker`) request — the
    /// mint shown in the top pill. Non-nil iff the request isn't pinned to a
    /// single mint; also gates the centered header (shown only when this is nil).
    private var pickerSelectedMint: MintInfo? {
        if case .picker(_, let selected) = mintPresentation { return selected }
        return nil
    }

    /// Family-style detail rows beneath the amount. Amountless requests keep the
    /// live fee beside the keypad so it cannot disappear behind the fixed input;
    /// fixed-amount requests keep it here because they have no number pad.
    @ViewBuilder
    private var requestDetailsSection: some View {
        if request.isSatUnit {
            let memo = requestMemo

            VStack(spacing: 0) {
                if let routeExplanation {
                    CashuRequestRouteExplanationRow(explanation: routeExplanation)
                }
                if let memo {
                    detailRow(label: "Memo", value: memo)
                }
                if request.amount != nil {
                    feesRow
                }
            }
            .padding(.top, 16)
            .padding(.horizontal)
        }
    }

    /// Centered mint-identity header: icon, name, and a quiet subtitle. Compacts
    /// (smaller icon, tighter type) when the number pad is present so the
    /// any-amount screen still fits.
    private var mintHeader: some View {
        let content = headerContent
        let compact = request.amount == nil

        return VStack(spacing: compact ? 8 : 12) {
            headerIcon(content.icon, size: compact ? 44 : 60)
            VStack(spacing: 3) {
                Text(content.name)
                    .font(compact ? .headline : .title3.weight(.semibold))
                    .foregroundStyle(content.isWarning ? Color.orange : Color.primary)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(content.subtitle)
                    .font(.subheadline)
                    .foregroundStyle(content.isWarning ? Color.orange : Color.secondary)
            }
        }
        .frame(maxWidth: .infinity)
        .multilineTextAlignment(.center)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("\(content.name), \(content.subtitle)")
    }

    @ViewBuilder
    private func headerIcon(_ icon: HeaderIcon, size: CGFloat) -> some View {
        switch icon {
        case .mint(let mint):
            MintAvatarView(iconUrl: mint.iconUrl, name: mint.name, size: size)
        case .generic:
            Circle()
                .fill(.quaternary)
                .frame(width: size, height: size)
                .overlay(
                    Image(systemName: "bitcoinsign.bank.building")
                        .font(.system(size: size * 0.4))
                        .foregroundStyle(.secondary)
                )
                .overlay(Circle().strokeBorder(Color.primary.opacity(0.08), lineWidth: 0.5))
        }
    }

    /// Fee row. "No fee" is exact (the mint charges no swap fee); a sat value is
    /// the exact fee for a fee-charging mint; "—" before an amount exists.
    private var feesRow: some View {
        PaymentDetailPair(label: "Fees") {
            feeValueText
        }
        .paymentDetailRow()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Fees")
        .accessibilityValue(feeAccessibilityValue)
    }

    /// Compact, non-scrolling amount-entry metadata. It reserves its place while
    /// the debounced estimate is loading, so neither the mint row nor keypad jumps.
    private var amountEntryFeeRow: some View {
        PaymentDetailPair(label: "Estimated fee") {
            feeValueText
        }
        .paymentDetailRow()
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Estimated fee")
        .accessibilityValue(feeAccessibilityValue)
    }

    @ViewBuilder
    private var feeValueText: some View {
        if needsAcquire {
            // Funding the mint routes over Lightning, which always carries a fee;
            // the exact reserve is confirmed during the transfer and in History.
            Text("Network fee").fontWeight(.regular).foregroundStyle(.secondary)
        } else {
            switch feeState {
            case .loading:
                ProgressView().controlSize(.mini)
            case .free:
                Text("No fee").fontWeight(.regular)
            case .amount(let fee):
                Text(AmountFormatter.sats(fee, useBitcoinSymbol: settings.useBitcoinSymbol))
                    .fontWeight(.regular)
            case .idle:
                Text("—").foregroundStyle(.secondary)
            case .unavailable:
                Text("Unavailable").foregroundStyle(.secondary)
            }
        }
    }

    private var feeAccessibilityValue: String {
        if needsAcquire { return "Network fee" }
        switch feeState {
        case .idle: return "Not yet calculated"
        case .loading: return "Calculating"
        case .free: return "No fee"
        case .amount(let fee):
            return AmountFormatter.sats(fee, useBitcoinSymbol: settings.useBitcoinSymbol)
        case .unavailable: return "Unavailable"
        }
    }

    /// Recompute the fee for the current mint + amount. Uses the cached per-mint
    /// ppk to short-circuit the common zero-fee case without a network call;
    /// only a fee-charging mint triggers the exact `prepareSend` estimate.
    private func recomputeFee() {
        feeTask?.cancel()

        guard request.isSatUnit,
              let mint = selectedPaymentMint,
              let amount = paymentAmount, amount > 0 else {
            feeState = .idle
            return
        }

        if let ppk = feePpkByMint[mint.url], ppk == 0 {
            feeState = .free
            return
        }

        feeState = .loading
        let mintURL = mint.url
        feeTask = Task { @MainActor in
            // Debounce live typing so we don't price every keystroke.
            try? await Task.sleep(nanoseconds: 250_000_000)
            if Task.isCancelled { return }

            let ppk: UInt64?
            if let cached = feePpkByMint[mintURL] {
                ppk = cached
            } else {
                ppk = await walletManager.mintInputFeePpk(mintURL: mintURL)
                if let ppk { feePpkByMint[mintURL] = ppk }
            }
            if Task.isCancelled { return }

            guard let ppk else {
                feeState = .unavailable
                return
            }
            if ppk == 0 {
                feeState = .free
                return
            }

            let fee = await walletManager.estimateCashuPaymentFee(amountSats: amount, mintURL: mintURL)
            if Task.isCancelled { return }
            feeState = fee.map { $0 == 0 ? .free : .amount($0) } ?? .unavailable
        }
    }

    /// Read-only detail row matching the app's `detailRow` vocabulary
    /// (TransactionDetailView, CashuRequestDetailView). Memo text is prose, so it
    /// wraps once and tail-truncates rather than middle-truncating like an ID.
    private func detailRow(label: String, value: String) -> some View {
        PaymentDetailPair(label: label) {
            Text(value)
                .fontWeight(.regular)
                .truncationMode(.tail)
        }
        .paymentDetailRow()
        .accessibilityElement(children: .combine)
    }

    /// Host of a mint URL for display when we hold no `MintInfo` for it
    /// (matches ReceiveLightningView / SendView).
    private func extractMintHost(_ url: String) -> String {
        URL(string: url)?.host ?? url
    }

    /// The mint URL to acquire ecash at when the request can't be paid from
    /// current ecash: a held-but-underfunded required mint → that mint; no held
    /// mint → a requested mint to add (the user's pick, else the first). Nil when
    /// already payable, or when there's no mint to target (any-mint request with
    /// nothing held).
    private var acquireTargetURL: String? {
        guard request.isSatUnit, let amount = paymentAmount, amount > 0 else { return nil }
        if let mint = selectedPaymentMint {
            return mint.balance >= amount ? nil : mint.url
        }
        guard !request.mints.isEmpty else { return nil }
        if request.mints.count == 1 { return request.mints.first }
        return selectedAddMintURL ?? request.mints.first
    }

    private var acquireTargetHost: String? { acquireTargetURL.map(extractMintHost) }

    /// The dead-end is recoverable — we can add/fund the target mint and pay.
    private var needsAcquire: Bool { acquireTargetURL != nil }

    /// Whether the target mint isn't in the wallet yet (affects CTA wording).
    private var acquireAddsNewMint: Bool { selectedPaymentMint == nil }

    private var routeExplanation: CashuRequestRouteExplanation? {
        guard request.isSatUnit, paymentAmount != nil else {
            return CashuRequestRouteExplanation(state: .unavailable)
        }
        guard needsAcquire else {
            return CashuRequestRouteExplanation(state: .compatibleMint)
        }
        let state: CashuRequestRouteExplanation.State = acquireAddsNewMint
            ? .addRequestedMint(targetMintURL: acquireTargetURL)
            : .topUpTargetMint(targetMintURL: acquireTargetURL)
        return CashuRequestRouteExplanation(state: state)
    }

    private var payButtonTitle: String {
        guard needsAcquire else { return "Pay" }
        if acquireAddsNewMint {
            if request.mints.count > 1 && selectedAddMintURL == nil {
                return "Add a mint & pay"
            }
            return acquireTargetHost.map { "Add \($0) & pay" } ?? "Add mint & pay"
        }
        return acquireTargetHost.map { "Fund \($0) & pay" } ?? "Fund mint & pay"
    }

    private var canPay: Bool {
        guard !isPaying, request.isSatUnit else { return false }
        guard let amount = paymentAmount, amount > 0 else { return false }
        if needsAcquire { return true }
        guard let mint = selectedPaymentMint else { return false }
        return mint.balance >= amount
    }

    /// The unit the keypad is entering in: fiat only when fiat is primary AND a
    /// price is loaded, else sats (mirrors `CurrencyAmountDisplay.effectivePrimary`).
    private var entryUnit: AmountDisplayPrimary {
        (settings.amountDisplayPrimary == .fiat && priceService.btcPriceUSD > 0) ? .fiat : .sats
    }

    /// Satoshis represented by the typed custom amount, interpreted per `entryUnit`.
    private var customAmountSats: UInt64 {
        AmountFormatter.entrySats(raw: customAmountString, unit: entryUnit)
    }

    private var paymentAmount: UInt64? {
        request.amount ?? (customAmountSats > 0 ? customAmountSats : nil)
    }

    private var candidateMints: [MintInfo] {
        guard !request.mints.isEmpty else {
            return walletManager.mints
        }

        let requestedMintURLs = Set(request.mints.map(normalizedMintURL))
        return walletManager.mints.filter {
            requestedMintURLs.contains(normalizedMintURL($0.url))
        }
    }

    private var selectedPaymentMint: MintInfo? {
        if let selectedMint,
           let refreshedMint = candidateMints.first(where: { $0.id == selectedMint.id }) {
            return refreshedMint
        }

        return recommendedPaymentMint()
    }

    private func syncSelectedMint() {
        if let selectedMint,
           candidateMints.contains(where: { $0.id == selectedMint.id }) {
            return
        }

        selectedMint = recommendedPaymentMint()
    }

    private func recommendedPaymentMint() -> MintInfo? {
        guard !candidateMints.isEmpty else { return nil }

        let candidates: [MintInfo]
        if let amount = paymentAmount, amount > 0 {
            let affordable = candidateMints.filter { $0.balance >= amount }
            candidates = affordable.isEmpty ? candidateMints : affordable
        } else {
            candidates = candidateMints
        }

        if let activeMint = walletManager.activeMint,
           let activeCandidate = candidates.first(where: { $0.id == activeMint.id }) {
            return activeCandidate
        }

        return candidates.sorted { lhs, rhs in
            if lhs.balance == rhs.balance {
                return lhs.name.localizedCaseInsensitiveCompare(rhs.name) == .orderedAscending
            }
            return lhs.balance > rhs.balance
        }.first
    }

    private func normalizedMintURL(_ urlString: String) -> String {
        MintURLIdentity.normalized(urlString)
    }

    private func payRequest() {
        // Can't pay from current ecash — add/fund the target mint, then pay.
        if needsAcquire {
            if acquireAddsNewMint, request.mints.count > 1, selectedAddMintURL == nil {
                addMintChooserPresented = true   // let the user pick which mint to add
                return
            }
            guard let target = acquireTargetURL, let amount = paymentAmount, amount > 0 else { return }
            runAcquireAndPay(targetMintURL: target, amount: amount)
            return
        }

        guard canPay, let mint = selectedPaymentMint else { return }

        activeRouteExplanation = routeExplanation
        isPaying = true
        errorMessage = nil
        HapticFeedback.impact(.medium)
        withAnimation(.smooth(duration: 0.3)) { paymentPhase = .processing }

        Task { @MainActor in
            do {
                try await walletManager.payCashuPaymentRequest(
                    encoded: request.encoded,
                    customAmountSats: request.amount == nil ? paymentAmount : nil,
                    preferredMintURL: mint.url
                )
                // Every creq payment lands on the shared success face, matching
                // the Lightning/on-chain payment vocabulary.
                withAnimation(.smooth(duration: 0.3)) { paymentPhase = .success }
            } catch {
                sendRecovery = error as? PendingSendRecovery
                let walletMessage = error.walletMessage
                errorMessage = walletMessage.text
                errorSeverity = walletMessage.severity
                paymentAwaitsMint = walletMessage.awaitsMintConfirmation
                withAnimation(.smooth(duration: 0.3)) {
                    paymentPhase = .failure(
                        message: walletMessage.text,
                        isCaution: walletMessage.severity == .caution,
                        isTerminal: walletMessage.recoverability == .terminal
                    )
                }
            }

            isPaying = false
        }
    }

    /// Add/fund the target mint over Lightning, then pay the request. Falls back
    /// to a top-up QR (`NeedsExternalTopUp`) when no held mint can bankroll it.
    private func runAcquireAndPay(targetMintURL: String, amount: UInt64) {
        activeRouteExplanation = routeExplanation
        isPaying = true
        errorMessage = nil
        HapticFeedback.impact(.medium)
        withAnimation(.smooth(duration: 0.3)) { paymentPhase = .processing }

        Task { @MainActor in
            do {
                try await walletManager.addMintAndPayCashuRequest(
                    request,
                    amount: amount,
                    targetMintURL: targetMintURL,
                    onStage: { _ in }
                )
                withAnimation(.smooth(duration: 0.3)) { paymentPhase = .success }
            } catch let topUp as NeedsExternalTopUp {
                // No held mint can fund it — clear the status screen first, then show
                // the Lightning top-up invoice sheet.
                withAnimation(.smooth(duration: 0.3)) { paymentPhase = nil }
                try? await Task.sleep(nanoseconds: 350_000_000)
                topUpContext = TopUpContext(
                    summary: request,
                    amount: amount,
                    targetMintURL: topUp.targetMintURL,
                    quote: topUp.targetQuote
                )
            } catch is MintSettling {
                let text = "Still settling — your balance will update shortly. Try again in a moment."
                errorMessage = text
                errorSeverity = .caution
                paymentAwaitsMint = false
                withAnimation(.smooth(duration: 0.3)) { paymentPhase = .failure(message: text, isCaution: true) }
            } catch {
                sendRecovery = error as? PendingSendRecovery
                let walletMessage = error.walletMessage
                errorMessage = walletMessage.text
                errorSeverity = walletMessage.severity
                paymentAwaitsMint = walletMessage.awaitsMintConfirmation
                withAnimation(.smooth(duration: 0.3)) {
                    paymentPhase = .failure(
                        message: walletMessage.text,
                        isCaution: walletMessage.severity == .caution,
                        isTerminal: walletMessage.recoverability == .terminal
                    )
                }
            }

            isPaying = false
        }
    }

    /// Full-height processing → success → failure status. Preserves the payment
    /// facts (amount / mint / fee / memo) as rows. onDone completes like the old
    /// overlay's onDismiss did; onRetry returns to the confirm screen.
    private func statusView(_ phase: PaymentStatusView.Phase) -> some View {
        // Fixed slot order — every row is present from the first frame, so values that
        // resolve late (the fee, or the mint in the acquire path) fill their reserved
        // slot in place instead of inserting and shoving the rows below them.
        var rows: [PaymentStatusView.DetailRow] = [
            .init(
                label: "Amount",
                isAmount: true,
                value: paymentAmount.map { "\($0) sat" } ?? "",
                isPending: paymentAmount == nil
            ),
            statusMintRow,
        ]
        if let explanation = activeRouteExplanation {
            rows.append(.init(
                label: "Route",
                value: explanation.localizedValue
            ))
        }
        rows.append(statusFeeRow)
        if let memo = requestMemo {
            rows.append(.init(label: "Memo", value: memo))
        }
        return PaymentStatusView(
            details: rows,
            phase: phase,
            awaitingMint: paymentAwaitsMint,
            onViewHistory: onViewHistory,
            onDone: {
                onComplete?()
                dismiss()
            },
            onRetry: { withAnimation(.smooth(duration: 0.3)) { paymentPhase = nil } }
        )
    }

    /// The paying mint as a detail row, always present so its slot is reserved. Shows
    /// the held mint's name; in the acquire path (mint not held yet) it shows the
    /// target host so the slot still has a real value, and only spins if neither is known.
    private var statusMintRow: PaymentStatusView.DetailRow {
        if let mint = selectedPaymentMint {
            return .init(label: "Mint", value: mint.name)
        }
        if let host = acquireTargetHost {
            return .init(label: "Mint", value: host)
        }
        return .init(label: "Mint", value: "", isPending: true)
    }

    /// The swap fee as a detail row, always present so its slot is reserved. Mirrors
    /// the confirm screen's `feeValueText`: a spinner while the fee computes, then the
    /// value; acquiring a mint routes over Lightning, whose reserve is confirmed later.
    private var statusFeeRow: PaymentStatusView.DetailRow {
        if needsAcquire {
            return .init(label: "Fees", value: "Network fee")
        }
        switch feeState {
        case .loading:
            return .init(label: "Fees", value: "", isPending: true)
        case .free:
            return .init(label: "Fees", value: "No fee")
        case .amount(let fee):
            return .init(
                label: "Fees",
                value: AmountFormatter.sats(fee, useBitcoinSymbol: settings.useBitcoinSymbol)
            )
        case .idle:
            return .init(label: "Fees", value: "—")
        case .unavailable:
            return .init(label: "Fees", value: "Unavailable")
        }
    }
}

struct LegacyQRScannerView: UIViewControllerRepresentable {
    var metadataQueue: DispatchQueue
    var onResult: @Sendable (String) -> Void
    var onFailure: @MainActor (String) -> Void
    /// Fires every time the camera surface (re)appears — including the cases
    /// SwiftUI can't see: cancelled interactive dismissals and stacked covers
    /// closing. Callers use it to re-arm scan intake.
    var onAppear: @MainActor () -> Void

    func makeUIViewController(context: Context) -> QRScannerViewController {
        let controller = QRScannerViewController(metadataQueue: metadataQueue)
        controller.delegate = context.coordinator
        return controller
    }

    func updateUIViewController(_ uiViewController: QRScannerViewController, context: Context) {
        context.coordinator.update(
            onResult: onResult,
            onFailure: onFailure,
            onAppear: onAppear
        )
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(onResult: onResult, onFailure: onFailure, onAppear: onAppear)
    }

    private struct MetadataHandlerBox: Sendable {
        var handler: @Sendable (String) -> Void
    }

    final class Coordinator: NSObject, QRScannerViewControllerDelegate, @unchecked Sendable {
        private let onResultLock: OSAllocatedUnfairLock<MetadataHandlerBox>
        @MainActor private var onFailure: (String) -> Void
        @MainActor private var onAppear: () -> Void

        @MainActor
        init(
            onResult: @escaping @Sendable (String) -> Void,
            onFailure: @escaping @MainActor (String) -> Void,
            onAppear: @escaping @MainActor () -> Void
        ) {
            self.onResultLock = OSAllocatedUnfairLock(initialState: MetadataHandlerBox(handler: onResult))
            self.onFailure = onFailure
            self.onAppear = onAppear
        }

        @MainActor
        func update(
            onResult: @escaping @Sendable (String) -> Void,
            onFailure: @escaping @MainActor (String) -> Void,
            onAppear: @escaping @MainActor () -> Void
        ) {
            onResultLock.withLock { $0.handler = onResult }
            self.onFailure = onFailure
            self.onAppear = onAppear
        }

        nonisolated func didFound(code: String) {
            let handler = onResultLock.withLock { $0.handler }
            handler(code)
        }

        @MainActor func didFail(error: String) {
            onFailure(error)
        }

        @MainActor func scannerDidAppear() {
            onAppear()
        }
    }
}

protocol QRScannerViewControllerDelegate: AnyObject, Sendable {
    nonisolated func didFound(code: String)
    @MainActor func didFail(error: String)
    @MainActor func scannerDidAppear()
}

private struct WeakScannerDelegateBox: @unchecked Sendable {
    weak var value: (any QRScannerViewControllerDelegate)?
}

private struct FrameBoundsHandlerBox: Sendable {
    var handler: (@MainActor @Sendable (CGRect?) -> Void)?
}

/// Owns `AVCaptureSession` configuration and start/stop lifecycle off the main
/// thread. All capture-session mutations execute serially on `sessionQueue`,
/// while `AVCaptureMetadataOutput` delivers callbacks on `metadataQueue`.
private final class CaptureSessionController: NSObject, AVCaptureMetadataOutputObjectsDelegate, @unchecked Sendable {
    let captureSession = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.cashu.me.qr-scanner-session", qos: .userInitiated)
    private let metadataQueue: DispatchQueue
    private let desiredRunning = OSAllocatedUnfairLock(initialState: false)
    private let onFrameBoundsLock = OSAllocatedUnfairLock(initialState: FrameBoundsHandlerBox(handler: nil))
    private let onMetadata: @Sendable (String) -> Void

    // Accessed exclusively on `sessionQueue`.
    private var isConfigured = false

    // Accessed exclusively on `metadataQueue`.
    private var hasVisibleFrame = false

    init(
        metadataQueue: DispatchQueue,
        onMetadata: @escaping @Sendable (String) -> Void
    ) {
        self.metadataQueue = metadataQueue
        self.onMetadata = onMetadata
        super.init()
    }

    func configure(
        onConfigured: @escaping @MainActor @Sendable () -> Void,
        onFrameBounds: @escaping @MainActor @Sendable (CGRect?) -> Void,
        onFailure: @escaping @MainActor @Sendable (String) -> Void
    ) {
        onFrameBoundsLock.withLock { $0.handler = onFrameBounds }

        sessionQueue.async { [weak self] in
            guard let self, !isConfigured else { return }

            captureSession.beginConfiguration()
            let failureMessage = configureInputsAndOutputs()
            captureSession.commitConfiguration()

            if let failureMessage {
                DispatchQueue.main.async {
                    MainActor.assumeIsolated {
                        onFailure(failureMessage)
                    }
                }
                return
            }

            isConfigured = true
            DispatchQueue.main.async {
                MainActor.assumeIsolated {
                    onConfigured()
                }
            }
        }
    }

    func setRunning(_ shouldRun: Bool) {
        desiredRunning.withLock { $0 = shouldRun }
        if shouldRun {
            sessionQueue.async { [weak self] in
                guard let self,
                      isConfigured,
                      desiredRunning.withLock({ $0 }),
                      !captureSession.isRunning else { return }
                captureSession.startRunning()
            }
        } else {
            sessionQueue.async { [self] in
                guard isConfigured,
                      !desiredRunning.withLock({ $0 }),
                      captureSession.isRunning else { return }
                captureSession.stopRunning()
            }
        }
    }

    /// Runs exclusively on `sessionQueue` inside `beginConfiguration()` / `commitConfiguration()`.
    private func configureInputsAndOutputs() -> String? {
        guard let videoCaptureDevice = AVCaptureDevice.default(for: .video) else {
            return "Your device doesn't support video capture."
        }

        let videoInput: AVCaptureDeviceInput
        do {
            videoInput = try AVCaptureDeviceInput(device: videoCaptureDevice)
        } catch {
            return error.localizedDescription
        }

        guard captureSession.canAddInput(videoInput) else {
            return "Could not add video input."
        }
        captureSession.addInput(videoInput)

        let metadataOutput = AVCaptureMetadataOutput()
        guard captureSession.canAddOutput(metadataOutput) else {
            return "Could not add metadata output."
        }
        captureSession.addOutput(metadataOutput)
        metadataOutput.setMetadataObjectsDelegate(self, queue: metadataQueue)

        guard metadataOutput.availableMetadataObjectTypes.contains(.qr) else {
            return "Your device doesn't support QR code scanning."
        }
        metadataOutput.metadataObjectTypes = [.qr]
        return nil
    }

    func metadataOutput(
        _ output: AVCaptureMetadataOutput,
        didOutput metadataObjects: [AVMetadataObject],
        from connection: AVCaptureConnection
    ) {
        if let readableObject = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
           let stringValue = readableObject.stringValue {
            hasVisibleFrame = true
            let normalizedBounds = readableObject.bounds
            if let onFrameBounds = onFrameBoundsLock.withLock({ $0.handler }) {
                DispatchQueue.main.async {
                    MainActor.assumeIsolated {
                        onFrameBounds(normalizedBounds)
                    }
                }
            }
            onMetadata(stringValue)
        } else if hasVisibleFrame {
            hasVisibleFrame = false
            if let onFrameBounds = onFrameBoundsLock.withLock({ $0.handler }) {
                DispatchQueue.main.async {
                    MainActor.assumeIsolated {
                        onFrameBounds(nil)
                    }
                }
            }
        }
    }
}

class QRScannerViewController: UIViewController {
    weak var delegate: (any QRScannerViewControllerDelegate)? {
        didSet {
            let current = delegate
            metadataDelegate.withLock { $0.value = current }
        }
    }
    var previewLayer: AVCaptureVideoPreviewLayer!
    var qrCodeFrameView: UIView?

    private let metadataDelegate: OSAllocatedUnfairLock<WeakScannerDelegateBox>
    private nonisolated let sessionController: CaptureSessionController

    init(metadataQueue: DispatchQueue) {
        let delegateBox = OSAllocatedUnfairLock(initialState: WeakScannerDelegateBox())
        self.metadataDelegate = delegateBox
        self.sessionController = CaptureSessionController(
            metadataQueue: metadataQueue,
            onMetadata: { code in
                delegateBox.withLock { $0.value }?.didFound(code: code)
            }
        )
        super.init(nibName: nil, bundle: nil)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) {
        fatalError("init(coder:) has not been implemented")
    }

    deinit {
        sessionController.setRunning(false)
    }

    override func viewDidLoad() {
        super.viewDidLoad()

        view.backgroundColor = UIColor.black

        // Attach the preview layer on Main immediately; session input/output
        // configuration runs asynchronously on `sessionQueue`.
        previewLayer = AVCaptureVideoPreviewLayer(session: sessionController.captureSession)
        previewLayer.frame = view.layer.bounds
        previewLayer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(previewLayer)

        // Initialize QR Code Frame View
        let frameView = UIView()
        frameView.layer.borderColor = UIColor.tintColor.cgColor
        frameView.layer.borderWidth = 2
        view.addSubview(frameView)
        view.bringSubviewToFront(frameView)
        qrCodeFrameView = frameView

        sessionController.configure(
            onConfigured: { [weak self] in
                self?.updatePreviewOrientation()
            },
            onFrameBounds: { [weak self] normalizedBounds in
                self?.updateQRCodeFrame(normalizedBounds: normalizedBounds)
            },
            onFailure: { [weak self] error in
                self?.delegate?.didFail(error: error)
            }
        )
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        if let previewLayer = previewLayer {
            previewLayer.frame = view.layer.bounds
        }
        updatePreviewOrientation()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)

        // Re-arm intake on every (re)appearance: a consumed scan sets
        // `isScanning = false`, and appearances that don't rebuild the view
        // (cancelled interactive dismissal, a stacked cover closing, a reused
        // sheet) would otherwise leave a live camera that silently drops scans.
        delegate?.scannerDidAppear()

        // A cancelled interactive sheet dismissal fires viewWillDisappear (which
        // stops the session) and then viewWillAppear without reloading the view,
        // so the session must be restarted here or the preview stays frozen.
        sessionController.setRunning(true)
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)

        sessionController.setRunning(false)
    }

    private func updatePreviewOrientation() {
        if let connection = previewLayer?.connection, connection.isVideoRotationAngleSupported(90) {
            connection.videoRotationAngle = 90
        }
    }

    private func updateQRCodeFrame(normalizedBounds: CGRect?) {
        guard let normalizedBounds else {
            qrCodeFrameView?.frame = .zero
            return
        }
        if let converted = previewLayer?.layerRectConverted(fromMetadataOutputRect: normalizedBounds) {
            qrCodeFrameView?.frame = converted
        }
    }

    override var prefersStatusBarHidden: Bool {
        return true
    }

    override var supportedInterfaceOrientations: UIInterfaceOrientationMask {
        return .portrait
    }
}

/// Context for the Lightning top-up sheet: fund a freshly-added mint by paying
/// its invoice, then mint proofs and pay the pending Cashu request.
struct TopUpContext: Identifiable {
    let id = UUID()
    let summary: CashuPaymentRequestSummary
    let amount: UInt64
    let targetMintURL: String
    let quote: MintQuoteInfo
}

/// Presents the target mint's bolt11 as a QR so the user can top up over
/// Lightning from an external wallet. Polls the quote; once paid, mints the
/// proofs and pays the Cashu request, then calls `onComplete`. Used when no held
/// mint can bankroll the transfer (the `NeedsExternalTopUp` fallback).
struct CashuTopUpInvoiceSheet: View {
    let context: TopUpContext
    var onComplete: () -> Void

    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var walletManager: WalletManager
    @ObservedObject private var settings = SettingsManager.shared

    @State private var phase: Phase = .awaitingPayment
    @State private var monitorTask: Task<Void, Never>?
    @State private var errorMessage: String?
    @State private var errorSeverity: ErrorSeverity = .error
    /// VoiceOver Share action for the QR: ShareLink can't be invoked
    /// imperatively, so the accessibility action presents the share sheet.
    @State private var showShareSheet = false

    private enum Phase: Equatable {
        case awaitingPayment   // showing the QR, polling the quote
        case paying            // payment detected — minting + paying the request
        case done
    }

    private var host: String { URL(string: context.targetMintURL)?.host ?? context.targetMintURL }

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ScrollView {
                    VStack(spacing: 24) {
                        header

                        QRCodeView(
                            content: context.quote.request,
                            showControls: false,
                            staticOnly: true,
                            onCopy: copyInvoice,
                            onShare: { showShareSheet = true }
                        )
                            .frame(width: 280, height: 280)
                            .padding(16)
                            .background(Color.white, in: RoundedRectangle(cornerRadius: 20))
                            .padding(.top, 8)
                            .contextMenu {
                                Button(action: copyInvoice) {
                                    Label("Copy", systemImage: "doc.on.doc")
                                }
                                ShareLink(item: context.quote.request) {
                                    Label("Share", systemImage: "square.and.arrow.up")
                                }
                            }
                            .sheet(isPresented: $showShareSheet) {
                                ShareSheet(items: [context.quote.request])
                            }

                        CurrencyAmountDisplay(sats: context.amount, primary: $settings.amountDisplayPrimary)

                        if let explanation = CashuRequestRouteExplanation(
                            state: .topUpTargetMint(targetMintURL: context.targetMintURL)
                        ) {
                            CashuRequestRouteExplanationRow(explanation: explanation)
                                .padding(.horizontal)
                        }

                        statusRow
                            .animation(.smooth(duration: 0.3), value: phase)

                        if let errorMessage {
                            InlineNotice(message: errorMessage, severity: errorSeverity)
                                .padding(.horizontal)
                                .transition(.opacity)
                        }
                    }
                    .padding(.top, 12)
                    .frame(maxWidth: .infinity)
                }

                Button("Copy Invoice", action: copyInvoice)
                .glassButton()
                .disabled(phase != .awaitingPayment)
                .padding(.horizontal)
                .padding(.bottom, 16)
            }
            .navigationTitle("Top up to pay")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    SheetCloseButton()
                }
            }
            .onAppear { startMonitoring() }
            .onDisappear { monitorTask?.cancel() }
        }
        .flatBottomSheetSurface()
    }

    private var header: some View {
        VStack(spacing: 6) {
            Text(host)
                .font(.headline)
                .lineLimit(1)
                .truncationMode(.middle)
            Text("Pay this invoice to fund the mint and complete the request.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding(.horizontal)
    }

    @ViewBuilder
    private var statusRow: some View {
        // Phase swaps fade through (the app-wide .smooth(0.3) payment
        // choreography) instead of popping per poll beat.
        switch phase {
        case .awaitingPayment:
            EmptyView()
        case .paying:
            HStack(spacing: 8) {
                ProgressView().controlSize(.small)
                Text("Payment received — paying request…")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            .transition(.opacity)
        case .done:
            // Monochrome, not green — green is reserved for the 64pt hero success
            // checks (DESIGN.md retired the small worded green ✓ badge).
            Label("Sent", systemImage: "checkmark.circle.fill")
                .font(.subheadline)
                .foregroundStyle(.primary)
                .transition(.opacity)
        }
    }

    private func copyInvoice() {
        UIPasteboard.general.string = context.quote.request
        HapticFeedback.notification(.success)
        ConfirmationToast.show("Copied payment request")
    }

    /// Poll the target mint quote until it's paid, then mint + pay the request.
    private func startMonitoring() {
        monitorTask?.cancel()
        monitorTask = Task { @MainActor in
            let delaysSec: [UInt64] = [3, 3, 4, 5, 5, 6, 8]   // backoff, then steady 10s
            var index = 0
            while !Task.isCancelled {
                let sleepSec = index < delaysSec.count ? delaysSec[index] : 10
                try? await Task.sleep(nanoseconds: sleepSec * 1_000_000_000)
                if Task.isCancelled { return }
                index += 1

                let state: MintQuoteState
                do {
                    state = try await walletManager.checkMintQuote(quoteId: context.quote.id).state
                } catch {
                    continue   // transient — keep polling
                }
                guard state == .paid || state == .issued else { continue }

                withAnimation(.smooth(duration: 0.3)) { phase = .paying }
                do {
                    try await walletManager.finishTopUpAndPayCashuRequest(
                        context.summary,
                        amount: context.amount,
                        targetMintURL: context.targetMintURL,
                        targetQuoteId: context.quote.id
                    )
                    withAnimation(.smooth(duration: 0.3)) { phase = .done }
                    HapticFeedback.notification(.success)
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                    onComplete()
                } catch {
                    let walletMessage = error.walletMessage
                    withAnimation(.smooth(duration: 0.3)) {
                        errorMessage = walletMessage.text
                        errorSeverity = walletMessage.severity
                        phase = .awaitingPayment   // let the retries/History backstop settle it
                    }
                }
                return
            }
        }
    }
}
