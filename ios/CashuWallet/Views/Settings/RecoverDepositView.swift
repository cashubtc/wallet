import SwiftUI

struct RecoverDepositView: View {
    @EnvironmentObject private var walletManager: WalletManager
    @Environment(\.dismiss) private var dismiss
    @State private var mintURL = ""
    @State private var quoteID = ""
    @State private var method: PaymentMethodKind = .bolt11
    @State private var unit = "sat"
    @State private var searchLimit: UInt32 = 1_000
    @State private var showOptions = false
    @State private var showMintPicker = false
    @State private var working = false
    @State private var error: String?
    @State private var result: DepositRecoveryResult?
    @State private var confirmation: RecoveryConfirmation?
    @FocusState private var focusedField: Field?
    private enum Field: Hashable { case mintURL, quoteID }

    private struct RecoveryConfirmation: Identifiable {
        let id = UUID()
        let request: DepositRecoveryRequest
    }

    private var recovered: Bool { result?.status == .recovered && result?.remaining == 0 }
    private var unitLabel: String { unit == "sat" ? "Sats" : unit.uppercased() }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 20) {
                    field("Mint URL", verticalPadding: walletManager.mints.isEmpty ? 16 : 5) {
                        HStack(spacing: 0) {
                            TextField("https://…", text: $mintURL)
                                .accessibilityLabel("Mint URL")
                                .focused($focusedField, equals: .mintURL)
                                .submitLabel(.next)
                                .onSubmit { focusedField = .quoteID }
                                .keyboardType(.URL)
                                .textContentType(.URL)
                                .accessibilityIdentifier("depositRecoveryMintURL")
                            if !walletManager.mints.isEmpty {
                                Button {
                                    focusedField = nil
                                    HapticFeedback.selection()
                                    showMintPicker = true
                                } label: {
                                    Image(systemName: "chevron.down")
                                        .font(.caption.weight(.semibold))
                                        .foregroundStyle(.secondary)
                                        .frame(width: 44, height: 44)
                                        .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel("Choose mint")
                                .accessibilityIdentifier("depositRecoveryMintPicker")
                            }
                        }
                    }
                    field("Quote ID") {
                        TextField("Paste quote ID", text: $quoteID)
                            .accessibilityLabel("Quote ID")
                            .focused($focusedField, equals: .quoteID)
                            .submitLabel(.done)
                            .onSubmit { focusedField = nil }
                            .accessibilityIdentifier("depositRecoveryQuoteID")
                    }
                }
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .disabled(working)

                Button {
                    focusedField = nil
                    HapticFeedback.selection()
                    withAnimation(.easeInOut(duration: 0.2)) { showOptions.toggle() }
                } label: {
                    HStack {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Options").foregroundStyle(.primary)
                            Text("\(method.friendlyTitle) · \(unitLabel)")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                        Spacer(minLength: 8)
                        Image(systemName: showOptions ? "chevron.up" : "chevron.down")
                            .font(.caption.weight(.semibold)).foregroundStyle(.tertiary)
                    }
                    .padding(.vertical, 14)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .disabled(working)
                .accessibilityValue(showOptions ? "Expanded" : "Collapsed")
                .accessibilityIdentifier("depositRecoveryOptions")
                .padding(.top, 10)

                if showOptions {
                    VStack(spacing: 0) {
                        Menu {
                            Picker("Payment method", selection: $method) {
                                ForEach(PaymentMethodKind.allCases, id: \.self) { Text($0.friendlyTitle).tag($0) }
                            }
                        } label: { optionRow("Payment method", value: method.friendlyTitle) }
                        Menu {
                            Picker("Currency", selection: $unit) {
                                ForEach(DepositRecoveryRequest.units, id: \.self) {
                                    Text($0 == "sat" ? "Sats" : $0.uppercased()).tag($0)
                                }
                            }
                        } label: { optionRow("Currency", value: unitLabel) }
                        Menu {
                            Picker("Search depth", selection: $searchLimit) {
                                Text("1,000 deposits").tag(UInt32(1_000))
                                Text("10,000 deposits").tag(UInt32(10_000))
                            }
                        } label: { optionRow("Search depth", value: searchLimit == 1_000 ? "1,000 deposits" : "10,000 deposits") }
                    }
                    .buttonStyle(.plain)
                    .disabled(working)
                }

                if let error {
                    InlineNotice(message: error, severity: .error).padding(.top, 12)
                }
                if let result {
                    InlineNotice(message: result.message, severity: result.status == .recovered ? .success : .info)
                        .accessibilityIdentifier("depositRecoveryResult")
                        .padding(.top, 12)
                }

                Button(action: recovered ? { dismiss() } : requestRecovery) {
                    LoadingButtonLabel(title: recovered ? "Done" : "Recover deposit", isLoading: working)
                }
                .glassButton()
                .disabled(working || !walletManager.isRuntimeReady || mintURL.isEmpty || quoteID.isEmpty)
                .accessibilityLabel(working ? "Recovering deposit" : recovered ? "Done" : "Recover deposit")
                .accessibilityIdentifier("recoverDepositButton")
                .padding(.top, 20)

                if !recovered {
                    Text("Uses this wallet's seed phrase.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 10)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 20)
            .padding(.bottom, 24)
            .frame(maxWidth: 720)
            .frame(maxWidth: .infinity)
        }
        .navigationTitle("Recover deposit")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(working)
        .scrollDismissesKeyboard(.interactively)
        .onChange(of: [mintURL, quoteID, method.rawValue, unit, String(searchLimit)]) {
            error = nil
            result = nil
        }
        .backdropSheet(isPresented: $showMintPicker) {
            MintPickerSheet(
                title: "Choose mint",
                mints: walletManager.mints,
                selectedMintUrl: Binding(
                    get: { walletManager.mints.first { MintURLIdentity.normalized($0.url) == MintURLIdentity.normalized(mintURL) }?.url },
                    set: { if let url = $0 { mintURL = url } }
                ),
                onSelect: { mintURL = $0 },
                mintSubtitle: { $0.url.replacingOccurrences(of: "https://", with: "", options: .anchored) }
            )
        }
        .backdropSheet(item: $confirmation) { confirmation in
            ActionConfirmationSheet(
                title: "Recover this deposit?",
                message: "Claim this deposit at \(URL(string: confirmation.request.mintURL)?.host ?? confirmation.request.mintURL) using this wallet's seed phrase.",
                actionLabel: "Recover",
                action: { recover(confirmation.request) }
            )
        }
        .bottomSheetBackdropHost()
    }

    private func field<Content: View>(_ label: String, verticalPadding: CGFloat = 16, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(label).font(.footnote).foregroundStyle(.secondary)
            content()
                .padding(.horizontal, 16)
                .padding(.vertical, verticalPadding)
                .background(.quaternary.opacity(0.5), in: RoundedRectangle(cornerRadius: 12))
        }
    }

    private func optionRow(_ label: String, value: String) -> some View {
        HStack {
            Text(label).foregroundStyle(.primary)
            Spacer(minLength: 8)
            Text(value).foregroundStyle(.secondary)
                .multilineTextAlignment(.trailing)
            Image(systemName: "chevron.up.chevron.down")
                .font(.caption).foregroundStyle(.tertiary)
        }
        .padding(.vertical, 14)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
    }

    private func requestRecovery() {
        focusedField = nil
        error = nil
        result = nil
        do {
            let request = try DepositRecoveryRequest(mintURL: mintURL, quoteID: quoteID,
                method: method, unit: unit, searchLimit: searchLimit)
            confirmation = RecoveryConfirmation(request: request)
        } catch { self.error = error.localizedDescription }
    }

    private func recover(_ request: DepositRecoveryRequest) {
        guard !working else { return }
        working = true
        Task {
            defer { working = false }
            do { result = try await walletManager.recoverDeposit(request) }
            catch let error as DepositRecoveryError { self.error = error.localizedDescription }
            catch { self.error = "Recovery failed. Check the deposit details and try again." }
        }
    }
}
