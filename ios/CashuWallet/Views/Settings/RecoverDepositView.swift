import SwiftUI

struct RecoverDepositView: View {
    @EnvironmentObject private var walletManager: WalletManager
    @State private var mintURL = ""
    @State private var quoteID = ""
    @State private var method: PaymentMethodKind = .bolt11
    @State private var unit = "sat"
    @State private var searchLimit: UInt32 = 1_000
    @State private var working = false
    @State private var error: String?
    @State private var result: DepositRecoveryResult?
    @State private var approvedRequest: DepositRecoveryRequest?
    @State private var showConfirmation = false
    @FocusState private var focusedField: Field?
    private enum Field: Hashable { case mintURL, quoteID }

    var body: some View {
        Form {
            Section {
                Text("Recover a paid deposit that was never added to your balance. You need the mint URL and quote ID saved by the original wallet. An invoice, payment hash, or Bitcoin address alone is not enough.")
                Text("Uses this wallet's recovery phrase. Restore the original phrase first if this deposit belongs to another wallet.")
                    .foregroundStyle(.secondary)
            }
            Section("Deposit details") {
                TextField("Mint URL", text: $mintURL)
                    .focused($focusedField, equals: .mintURL)
                    .submitLabel(.next)
                    .onSubmit { focusedField = .quoteID }
                    .keyboardType(.URL)
                    .textContentType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("depositRecoveryMintURL")
                TextField("Quote ID", text: $quoteID)
                    .focused($focusedField, equals: .quoteID)
                    .submitLabel(.done)
                    .onSubmit { focusedField = nil }
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .accessibilityIdentifier("depositRecoveryQuoteID")
                Picker("Payment method", selection: $method) {
                    ForEach(PaymentMethodKind.allCases, id: \.self) { Text($0.friendlyTitle).tag($0) }
                }
                Picker("Currency", selection: $unit) {
                    ForEach(DepositRecoveryRequest.units, id: \.self) { Text($0 == "sat" ? "Sats" : $0.uppercased()).tag($0) }
                }
            }
            .disabled(working)
            Section {
                Picker("Search depth", selection: $searchLimit) {
                    Text("Up to 1,000 deposits").tag(UInt32(1_000))
                    Text("Up to 10,000 deposits").tag(UInt32(10_000))
                }
                .disabled(working)
            } footer: {
                Text("Try the deeper search if this wallet created many deposits before you lost its data. Recovery does not find unknown quote IDs.")
            }
            Section {
                if let error { InlineNotice(message: error, severity: .error) }
                if let result {
                    InlineNotice(message: result.message, severity: result.status == .recovered ? .success : .info)
                        .accessibilityIdentifier("depositRecoveryResult")
                }
                Button {
                    focusedField = nil
                    error = nil
                    result = nil
                    do {
                        approvedRequest = try DepositRecoveryRequest(mintURL: mintURL, quoteID: quoteID,
                            method: method, unit: unit, searchLimit: searchLimit)
                        showConfirmation = true
                    } catch { self.error = error.localizedDescription }
                } label: {
                    HStack {
                        if working { ProgressView() }
                        Text(working ? "Recovering…" : "Recover deposit")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(working || !walletManager.isRuntimeReady || mintURL.isEmpty || quoteID.isEmpty)
                .accessibilityIdentifier("recoverDepositButton")
            }
        }
        .navigationTitle("Recover a deposit")
        .navigationBarTitleDisplayMode(.inline)
        .navigationBarBackButtonHidden(working)
        .scrollDismissesKeyboard(.interactively)
        .onChange(of: [mintURL, quoteID, method.rawValue, unit, String(searchLimit)]) {
            error = nil
            result = nil
        }
        .alert("Recover this deposit?", isPresented: $showConfirmation) {
            Button("Cancel", role: .cancel) { approvedRequest = nil }
            Button("Recover deposit") {
                guard let request = approvedRequest, !working else { return }
                approvedRequest = nil
                working = true
                Task {
                    defer { working = false }
                    do { result = try await walletManager.recoverDeposit(request) }
                    catch let error as DepositRecoveryError { self.error = error.localizedDescription }
                    catch { self.error = "Recovery could not finish. Check the mint and deposit details, then retry. Any saved deposit will remain available for recovery." }
                }
            }
        } message: {
            Text("Use this wallet's recovery phrase to restore ecash at this mint, find the deposit key, and claim available funds. Pending deposits will continue recovering in the background.")
        }
    }
}
