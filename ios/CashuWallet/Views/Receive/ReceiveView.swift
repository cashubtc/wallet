import SwiftUI
import UIKit

struct ReceiveView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var walletManager: WalletManager
    @ObservedObject private var settings = SettingsManager.shared

    @State private var selectedOption: ReceiveOption?
    @State private var lockedReceiveEncoded: String?

    enum ReceiveOption: String, Identifiable {
        case paste, scan, lightning, lockedKey
        var id: String { rawValue }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button(action: { selectedOption = .paste }) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Paste Ecash Token")
                                Text("Paste a token from clipboard")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "doc.on.clipboard")
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                    .accessibilityLabel("Paste Ecash Token")
                    .accessibilityHint("Paste a cashu token from clipboard to receive ecash")
                    .accessibilityAddTraits(.isButton)

                    Button(action: { selectedOption = .scan }) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Scan QR Code")
                                Text("Scan a token, payment request, or address")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "viewfinder")
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                    .accessibilityLabel("Scan QR Code")
                    .accessibilityHint("Opens camera to scan a token or invoice QR code")
                    .accessibilityAddTraits(.isButton)

                    Button(action: { selectedOption = .lightning }) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Payment Request")
                                Text("Create an invoice, offer, or address")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "bolt.fill")
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                    .accessibilityLabel("Payment Request")
                    .accessibilityHint("Creates a lightning invoice, BOLT12 offer, or bitcoin address to receive sats")
                    .accessibilityAddTraits(.isButton)

                    Button(action: { presentReceiveLockedKey() }) {
                        Label {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Receive Locked Ecash")
                                Text("Receive ecash only you can claim")
                                    .font(.subheadline)
                                    .foregroundStyle(.secondary)
                            }
                        } icon: {
                            Image(systemName: "lock")
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                    .accessibilityLabel("Receive Locked Ecash")
                    .accessibilityHint("Shows a request so someone can send ecash only you can claim")
                    .accessibilityAddTraits(.isButton)
                }
            }
            .listStyle(.plain)
            .navigationTitle("Receive")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                }
            }
            .sheet(item: $selectedOption) { option in
                Group {
                    switch option {
                    case .paste:
                        ReceiveEcashView()
                            .environmentObject(walletManager)
                    case .scan:
                        ScannerWrapperView()
                            .environmentObject(walletManager)
                    case .lightning:
                        ReceiveLightningView()
                            .environmentObject(walletManager)
                    case .lockedKey:
                        Group {
                            if let encoded = lockedReceiveEncoded {
                                QRCodeDetailSheet(title: "Receive Locked Ecash", content: encoded)
                            } else {
                                lockedKeyUnavailable
                            }
                        }
                        .presentationDetents([.medium, .large])
                    }
                }
                .canvasSheetBackground()
            }
        }
    }

    /// Builds a NUT-18 Cashu payment request locked to the wallet's primary
    /// (seed-derived) key, so anyone who pays it sends ecash only this wallet can
    /// claim. Routes the proofs back over Nostr.
    private func presentReceiveLockedKey() {
        HapticFeedback.selection()
        lockedReceiveEncoded = LockedReceiveRequest.build()
        selectedOption = .lockedKey
    }

    private var lockedKeyUnavailable: some View {
        VStack(spacing: 12) {
            Image(systemName: "key.slash")
                .font(.largeTitle)
                .foregroundStyle(.secondary)
            Text("Couldn't create a request")
                .font(.headline)
            Text("This needs your wallet set up with a Nostr relay. Check Settings → Nostr, then try again.")
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
        }
        .padding()
    }
}

struct ReceiveEcashView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var walletManager: WalletManager
    @ObservedObject private var settings = SettingsManager.shared

    var sheetDetent: Binding<PresentationDetent>? = nil

    @State private var tokenInput = ""
    @State private var errorMessage: String?
    @State private var navigateToDetail = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var validatedToken: String?
    @State private var currentRequest: CashuRequest?
    @State private var showingScanner = false

    var body: some View {
        NavigationStack {
            ZStack {
                if let request = currentRequest {
                    CashuRequestDetailView(request: request, onClose: { dismiss() })
                        .environmentObject(walletManager)
                        .transition(.opacity)
                } else {
                    formContent
                        .transition(.opacity)
                }
            }
            .animation(.easeInOut(duration: 0.25), value: currentRequest?.id)
        }
    }

    @ViewBuilder
    private var formContent: some View {
        VStack(spacing: 16) {
                ZStack(alignment: .bottomTrailing) {
                    ZStack(alignment: .topLeading) {
                        // TokenTextEditor (not TextEditor): cashuA base64url uses
                        // "-" which word-wrap treats as a break, leaving short
                        // jagged lines. Android fills each line edge-to-edge.
                        TokenTextEditor(text: $tokenInput)
                            .padding(.horizontal, 12)
                            .padding(.vertical, 12)
                            .accessibilityLabel("Ecash token input")
                            .accessibilityHint("Enter or paste a cashu ecash token")

                        if tokenInput.isEmpty {
                            Text("cashuB…")
                                .font(.system(.body, design: .monospaced))
                                .foregroundStyle(.tertiary)
                                .padding(.horizontal, 17)
                                .padding(.vertical, 20)
                                .allowsHitTesting(false)
                        }
                    }
                    .mask(
                        LinearGradient(
                            stops: [
                                .init(color: .black, location: 0),
                                .init(color: .black, location: 0.55),
                                .init(color: .black.opacity(0.85), location: 0.72),
                                .init(color: .black.opacity(0.35), location: 0.88),
                                .init(color: .clear, location: 1.0),
                            ],
                            startPoint: .top,
                            endPoint: .bottom
                        )
                    )

                    // Smart corner icon: paste when empty, clear when full.
                    // Plain SF Symbol (no circle bg) so we don't stack a
                    // gray dot on the gray text field. Sits outside the fade
                    // mask so it stays fully opaque over dissolving text.
                    Button(action: tokenInput.isEmpty ? pasteFromClipboard : clearInput) {
                        Image(systemName: tokenInput.isEmpty ? "doc.on.clipboard" : "xmark.circle.fill")
                            .font(.title3.weight(.medium))
                            .foregroundStyle(.secondary)
                            .padding(14)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(tokenInput.isEmpty ? "Paste from clipboard" : "Clear")
                }
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
                .frame(maxHeight: .infinity)
                .padding(.horizontal)
                .padding(.top, 12)

                if let error = errorMessage {
                    InlineNotice(message: error, severity: .error)
                        .padding(.horizontal)
                        .transition(reduceMotion ? .opacity : .opacity.combined(with: .scale))
                }

                VStack(spacing: 10) {
                    Button(action: validateAndContinue) {
                        Text("Continue")
                    }
                    .glassButton()
                    .disabled(tokenInput.isEmpty)
                    .accessibilityHint("Validates the token and proceeds to details")

                    Button(action: createNewRequest) {
                        Text("New Request")
                    }
                    .glassButton()
                    .accessibilityHint("Generate a Cashu Request to receive ecash")
                }
                .padding(.horizontal)
                .padding(.bottom, 16)
            }
            .animation(.easeInOut(duration: 0.2), value: errorMessage)
            .navigationTitle("Receive Ecash")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    SheetCloseButton()
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        HapticFeedback.selection()
                        showingScanner = true
                    } label: {
                        Image(systemName: "viewfinder")
                            .font(.body.weight(.semibold))
                            .toolbarIconTapTarget()
                    }
                    .accessibilityLabel("Scan QR Code")
                    .accessibilityHint("Opens the camera to scan an ecash token")
                }
            }
            .sheet(isPresented: $showingScanner) {
                ScannerWrapperView(onScanned: handleScannedToken)
                    .environmentObject(walletManager)
                    .canvasSheetBackground()
            }
            .navigationDestination(isPresented: $navigateToDetail) {
                if let token = validatedToken {
                    ReceiveTokenDetailView(tokenString: token, onComplete: {
                        dismiss()
                    })
                    .environmentObject(walletManager)
                    .navigationBarBackButtonHidden(true)
                }
            }
            .onAppear {
                guard settings.autoPasteEcashReceive else { return }
                guard tokenInput.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
                guard let clipboardContent = UIPasteboard.general.string?.trimmingCharacters(in: .whitespacesAndNewlines),
                      TokenParser.isCashuToken(clipboardContent) else { return }
                tokenInput = clipboardContent
            }
    }

    private func pasteFromClipboard() {
        if let clipboardContent = UIPasteboard.general.string {
            HapticFeedback.selection()
            tokenInput = clipboardContent
        }
    }

    private func clearInput() {
        HapticFeedback.selection()
        tokenInput = ""
        errorMessage = nil
    }

    private func createNewRequest() {
        HapticFeedback.selection()
        let nostr = NostrService.shared
        guard nostr.isInitialized, !nostr.publicKeyHex.isEmpty else {
            errorMessage = "Your Nostr identity isn't ready yet. Check Settings → Nostr, then try again."
            return
        }
        let id = CashuRequest.newId()
        do {
            let encoded = try PaymentRequestBuilder.build(
                id: id,
                amount: nil,
                unit: "sat",
                mints: [],
                description: nil,
                nostrPubkeyHex: nostr.publicKeyHex,
                relays: SettingsManager.shared.nostrRelays
            )
            let request = CashuRequestStore.shared.createNew(
                id: id,
                amount: nil,
                unit: "sat",
                mints: [],
                memo: nil,
                encoded: encoded
            )
            sheetDetent?.wrappedValue = .large
            currentRequest = request
        } catch {
            AppLogger.ui.error("createNewRequest failed: \(String(describing: error), privacy: .public)")
            errorMessage = "Couldn't create the request. Please try again."
        }
    }

    private func handleScannedToken(_ scanned: String) {
        tokenInput = scanned
        validateAndContinue()
    }

    private func validateAndContinue() {
        guard !tokenInput.isEmpty else { return }

        errorMessage = nil

        let trimmedToken = tokenInput.trimmingCharacters(in: .whitespacesAndNewlines)

        if let token = TokenParser.normalizedToken(from: trimmedToken) {
            validatedToken = token
            navigateToDetail = true
        } else {
            errorMessage = "Invalid token format. Token should start with 'cashu'"
        }
    }
}

/// Monospaced token paste field. `TextEditor` word-wraps at ASCII `-`
/// (common in cashuA base64url), which fragments the token into short lines.
/// Android fills each line edge-to-edge; we match that by displaying `-` as
/// non-breaking hyphens while keeping ASCII `-` in the bound string.
private struct TokenTextEditor: UIViewRepresentable {
    @Binding var text: String

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> UITextView {
        let textView = UITextView()
        textView.delegate = context.coordinator
        textView.backgroundColor = .clear
        textView.textContainerInset = .zero
        textView.textContainer.lineFragmentPadding = 0
        textView.textContainer.lineBreakMode = .byCharWrapping
        textView.adjustsFontForContentSizeCategory = true
        textView.autocorrectionType = .no
        textView.autocapitalizationType = .none
        textView.smartDashesType = .no
        textView.smartQuotesType = .no
        textView.smartInsertDeleteType = .no
        textView.spellCheckingType = .no
        textView.keyboardType = .asciiCapable
        textView.tintColor = .label
        textView.typingAttributes = Self.typingAttributes()
        textView.attributedText = Self.displayString(for: text)
        return textView
    }

    func updateUIView(_ textView: UITextView, context: Context) {
        textView.typingAttributes = Self.typingAttributes()
        textView.textContainer.lineBreakMode = .byCharWrapping
        let display = Self.displayString(for: text)
        if textView.attributedText?.string != display.string {
            let selected = textView.selectedRange
            textView.attributedText = display
            let maxLength = (textView.text as NSString).length
            if NSMaxRange(selected) <= maxLength {
                textView.selectedRange = selected
            }
        }
    }

    /// U+2011 NON-BREAKING HYPHEN — same glyph as `-`, not a line-break opportunity.
    private static let nonBreakingHyphen = "\u{2011}"

    private static func monoBodyFont() -> UIFont {
        let body = UIFont.preferredFont(forTextStyle: .body)
        let mono = UIFont.monospacedSystemFont(ofSize: body.pointSize, weight: .regular)
        return UIFontMetrics(forTextStyle: .body).scaledFont(for: mono)
    }

    private static func typingAttributes() -> [NSAttributedString.Key: Any] {
        let style = NSMutableParagraphStyle()
        style.lineBreakMode = .byCharWrapping
        style.hyphenationFactor = 0
        return [
            .font: monoBodyFont(),
            .foregroundColor: UIColor.label,
            .paragraphStyle: style,
        ]
    }

    private static func displayString(for raw: String) -> NSAttributedString {
        let display = raw.replacingOccurrences(of: "-", with: nonBreakingHyphen)
        return NSAttributedString(string: display, attributes: typingAttributes())
    }

    private static func storageString(from display: String) -> String {
        display.replacingOccurrences(of: nonBreakingHyphen, with: "-")
    }

    final class Coordinator: NSObject, UITextViewDelegate {
        var parent: TokenTextEditor

        init(_ parent: TokenTextEditor) {
            self.parent = parent
        }

        func textViewDidChange(_ textView: UITextView) {
            let display = textView.text ?? ""
            let storage = TokenTextEditor.storageString(from: display)
            if parent.text != storage {
                parent.text = storage
            }
            // Keep typed ASCII hyphens non-breaking without fighting the cursor.
            let normalized = display.replacingOccurrences(of: "-", with: TokenTextEditor.nonBreakingHyphen)
            if display != normalized {
                let selected = textView.selectedRange
                textView.attributedText = TokenTextEditor.displayString(
                    for: TokenTextEditor.storageString(from: normalized)
                )
                textView.typingAttributes = TokenTextEditor.typingAttributes()
                let maxLength = (textView.text as NSString).length
                if NSMaxRange(selected) <= maxLength {
                    textView.selectedRange = selected
                }
            }
        }
    }
}

#Preview {
    ReceiveView()
        .environmentObject(WalletManager())
}
