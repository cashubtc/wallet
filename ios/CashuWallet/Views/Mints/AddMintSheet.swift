import SwiftUI
import UIKit

struct AddMintSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var walletManager: WalletManager

    @State private var mintUrl = ""
    @State private var nickname = ""
    @State private var isAdding = false
    @State private var validationError: String?
    @State private var operationError: AppError?
    @State private var showingScanner = false
    @FocusState private var urlFieldFocused: Bool

    var body: some View {
        NavigationStack {
            List {
                Section {
                    HStack(spacing: 10) {
                        TextField("Mint URL (https://…)", text: $mintUrl)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                            .textContentType(.URL)
                            .focused($urlFieldFocused)
                            .submitLabel(.go)
                            .onSubmit(addMint)
                            .onChange(of: mintUrl) {
                                validationError = nil
                                operationError = nil
                            }
                            .accessibilityIdentifier("mints-add-url-field")

                        Button(action: openScanner) {
                            Image(systemName: "viewfinder")
                                .font(.body.weight(.semibold))
                                .foregroundStyle(.secondary)
                        }
                        .buttonStyle(.borderless)
                        .disabled(isAdding)
                        .accessibilityLabel("Scan QR Code")
                        .accessibilityHint("Opens the camera to scan a mint URL")
                        .accessibilityIdentifier("mints-add-scan-button")
                    }

                    TextField("Nickname (optional)", text: $nickname)
                        .textInputAutocapitalization(.words)
                        .autocorrectionDisabled()
                } footer: {
                    Text("Enter the URL of a Cashu mint to connect to it. This wallet is not affiliated with any mint.")
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom) {
                VStack(spacing: 12) {
                    if let validationError { InlineNotice(message: validationError, severity: .error) }
                    if let operationError { InlineNotice(error: operationError) }

                    Button(action: addMint) {
                        Group {
                            if isAdding {
                                ProgressView().tint(.primary)
                            } else {
                                Text("Add Mint")
                            }
                        }
                    }
                    .glassButton()
                    .disabled(!canSubmit)
                    .accessibilityIdentifier("mints-add-submit-button")

                    Button("Paste URL from Clipboard", action: pasteFromClipboard)
                        .textLinkButton()
                        .frame(maxWidth: .infinity)
                        .disabled(isAdding)
                }
                .padding(.horizontal)
                .padding(.top, 8)
                .padding(.bottom, 8)
            }
            .navigationTitle("Add Mint")
            .navigationBarTitleDisplayMode(.inline)
            .fullScreenCover(isPresented: $showingScanner) {
                ScannerWrapperView(
                    onScanned: handleScannedMintUrl,
                    promptText: "Scan a mint URL"
                )
                .environmentObject(walletManager)
                .canvasSheetBackground()
            }
        }
    }

    private var canSubmit: Bool {
        !mintUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isAdding
    }

    private func openScanner() {
        urlFieldFocused = false
        HapticFeedback.selection()
        showingScanner = true
    }

    private func handleScannedMintUrl(_ raw: String) {
        if let normalized = Self.normalizedMintUrl(from: raw) {
            mintUrl = normalized
            validationError = nil
            operationError = nil
        } else {
            validationError = "No valid mint URL found in QR code."
        }
    }

    private func addMint() {
        let urlToAdd = mintUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !urlToAdd.isEmpty, !isAdding else { return }

        isAdding = true
        validationError = nil
        operationError = nil
        Task { @MainActor in
            do {
                try await walletManager.addMint(url: urlToAdd)
                HapticFeedback.selection()
                mintUrl = ""
                nickname = ""
                dismiss()
            } catch {
                operationError = AppError.from(error, operation: "add mint")
            }
            isAdding = false
        }
    }

    private func pasteFromClipboard() {
        guard let clipboardContent = UIPasteboard.general.string,
              !clipboardContent.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            validationError = "Clipboard is empty."
            return
        }
        if let normalized = Self.normalizedMintUrl(from: clipboardContent) {
            mintUrl = normalized
            validationError = nil
            operationError = nil
        } else {
            validationError = "No valid mint URL found in clipboard."
        }
    }

    /// Pulls the first plausible mint URL from free-form paste/scan text.
    private static func normalizedMintUrl(from raw: String) -> String? {
        let separators = CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ",;"))
        let candidates = raw.components(separatedBy: separators).filter { !$0.isEmpty }
        for rawCandidate in candidates {
            var candidate = rawCandidate.trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
            if !candidate.hasPrefix("http://") && !candidate.hasPrefix("https://") {
                candidate = "https://" + candidate
            }
            if candidate.hasSuffix("/") {
                candidate = String(candidate.dropLast())
            }
            if let url = URL(string: candidate), url.host != nil {
                return candidate
            }
        }
        return nil
    }
}

#Preview {
    AddMintSheet()
        .environmentObject(WalletManager())
}
