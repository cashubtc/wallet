import SwiftUI
import UIKit

struct AddMintSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var walletManager: WalletManager

    @State private var mintUrl = ""
    @State private var nickname = ""
    @State private var isAdding = false
    @State private var errorMessage: String?
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
                                if errorMessage != nil { errorMessage = nil }
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

                if let errorMessage {
                    Section {
                        InlineNotice(message: errorMessage, severity: .error)
                    }
                }

                Section {
                    Button(action: addMint) {
                        HStack {
                            Text("Add Mint")
                            if isAdding {
                                Spacer()
                                ProgressView()
                            }
                        }
                    }
                    .disabled(!canSubmit)
                    .accessibilityIdentifier("mints-add-submit-button")

                    Button("Paste URL from Clipboard", action: pasteFromClipboard)
                        .disabled(isAdding)
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .navigationTitle("Add Mint")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                        .fontWeight(.semibold)
                }
            }
            .onAppear {
                urlFieldFocused = true
            }
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
            errorMessage = nil
        } else {
            errorMessage = "No valid mint URL found in QR code."
        }
    }

    private func addMint() {
        let urlToAdd = mintUrl.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !urlToAdd.isEmpty, !isAdding else { return }

        isAdding = true
        errorMessage = nil
        Task { @MainActor in
            do {
                try await walletManager.addMint(url: urlToAdd)
                HapticFeedback.selection()
                mintUrl = ""
                nickname = ""
                dismiss()
            } catch {
                errorMessage = error.userFacingWalletMessage
            }
            isAdding = false
        }
    }

    private func pasteFromClipboard() {
        guard let clipboardContent = UIPasteboard.general.string,
              !clipboardContent.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            errorMessage = "Clipboard is empty."
            return
        }
        if let normalized = Self.normalizedMintUrl(from: clipboardContent) {
            mintUrl = normalized
            errorMessage = nil
        } else {
            errorMessage = "No valid mint URL found in clipboard."
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
