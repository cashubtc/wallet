import SwiftUI

struct ReceiveView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var walletManager: WalletManager

    @State private var selectedOption: ReceiveOption?

    enum ReceiveOption: String, Identifiable {
        case paste, scan, lightning
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
                            Image(systemName: "qrcode.viewfinder")
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
                }
            }
        }
    }

    private func handleScannedCode(_ code: String) {
        Task { @MainActor in
            if TokenParser.isCashuToken(code) {
                do {
                    let _ = try await walletManager.receiveTokens(tokenString: code)
                    dismiss()
                } catch {
                    print("Error receiving token: \(error)")
                }
            }
        }
    }
}

struct ReceiveEcashView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var walletManager: WalletManager
    @ObservedObject private var settings = SettingsManager.shared

    @State private var tokenInput = ""
    @State private var errorMessage: String?
    @State private var navigateToDetail = false
    @State private var validatedToken: String?

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                ZStack(alignment: .topLeading) {
                    TextEditor(text: $tokenInput)
                        .font(.system(.body, design: .monospaced))
                        .scrollContentBackground(.hidden)
                        .padding(12)
                        .frame(height: 160)
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
                .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 14))
                .padding(.horizontal)
                .padding(.top, 16)

                Button(action: pasteFromClipboard) {
                    Label("Paste from Clipboard", systemImage: "doc.on.clipboard")
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(.thinMaterial, in: Capsule())
                }
                .buttonStyle(.plain)
                .accessibilityHint("Pastes ecash token from clipboard")

                if let error = errorMessage {
                    Text(error)
                        .foregroundStyle(.red)
                        .font(.caption)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                        .transition(.opacity.combined(with: .scale))
                }

                Spacer()

                Button(action: validateAndContinue) {
                    Text("Continue")
                }
                .glassButton()
                .disabled(tokenInput.isEmpty)
                .padding(.horizontal)
                .padding(.bottom, 16)
                .accessibilityHint("Validates the token and proceeds to details")
            }
            .animation(.easeInOut(duration: 0.2), value: errorMessage)
            .navigationTitle("Receive Ecash")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(action: { dismiss() }) {
                        Image(systemName: "xmark")
                    }
                    .accessibilityLabel("Close")
                }
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
    }

    private func pasteFromClipboard() {
        if let clipboardContent = UIPasteboard.general.string {
            HapticFeedback.selection()
            tokenInput = clipboardContent
        }
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

#Preview {
    ReceiveView()
        .environmentObject(WalletManager())
}
