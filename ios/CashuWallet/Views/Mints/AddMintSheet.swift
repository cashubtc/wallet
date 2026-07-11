import SwiftUI
import UIKit

struct AddMintSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var walletManager: WalletManager

    @State private var mintUrl = ""
    @State private var nickname = ""
    @State private var isAdding = false
    @State private var errorMessage: String?
    @FocusState private var urlFieldFocused: Bool

    var body: some View {
        NavigationStack {
            List {
                Section {
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
        }
    }

    private var canSubmit: Bool {
        !mintUrl.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty && !isAdding
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
        let separators = CharacterSet.whitespacesAndNewlines.union(CharacterSet(charactersIn: ",;"))
        let candidates = clipboardContent.components(separatedBy: separators).filter { !$0.isEmpty }
        for rawCandidate in candidates {
            var candidate = rawCandidate.trimmingCharacters(in: CharacterSet(charactersIn: "\"'"))
            if !candidate.hasPrefix("http://") && !candidate.hasPrefix("https://") {
                candidate = "https://" + candidate
            }
            if candidate.hasSuffix("/") {
                candidate = String(candidate.dropLast())
            }
            if let url = URL(string: candidate), url.host != nil {
                mintUrl = candidate
                errorMessage = nil
                return
            }
        }
        errorMessage = "No valid mint URL found in clipboard."
    }
}

#Preview {
    AddMintSheet()
        .environmentObject(WalletManager())
}
