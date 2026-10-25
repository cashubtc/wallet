import SwiftUI

struct PendingSendRecoveryView: View {
    @EnvironmentObject private var walletManager: WalletManager
    @State private var recovery: PendingSendRecovery
    @State private var isWorking = false
    @State private var message: String?
    @State private var reclaimID: String?
    let onDone: () -> Void
    var onWorkingChanged: (Bool) -> Void = { _ in }

    init(recovery: PendingSendRecovery, onDone: @escaping () -> Void, onWorkingChanged: @escaping (Bool) -> Void = { _ in }) {
        _recovery = State(initialValue: recovery)
        self.onDone = onDone
        self.onWorkingChanged = onWorkingChanged
    }

    var body: some View {
        ScrollView {
            VStack(spacing: 24) {
                Text(title).font(.title2.bold()).accessibilityAddTraits(.isHeader)
                Text(recovery.localizedDescription).multilineTextAlignment(.center)
                Text(URL(string: recovery.mintURL)?.host ?? recovery.mintURL)
                    .font(.subheadline).foregroundStyle(.secondary)
                ForEach(recovery.sends) { send in
                    VStack(spacing: 12) {
                        Text(CurrencyAmount(value: send.amount, currency: CurrencyRegistry.currency(forMintUnit: recovery.unit)).formatted()).font(.headline)
                        if let token = send.token {
                            QRCodeView(content: token, showControls: true)
                                .frame(width: 240, height: 280)
                            ShareLink("Share ecash", item: token)
                        } else {
                            Text("The token preview is unavailable. You can still check its status or reclaim it.")
                        }
                        Button("Reclaim ecash") { reclaimID = send.id }
                            .buttonStyle(.bordered)
                            .disabled(isWorking)
                            .accessibilityIdentifier("cashu.send.reclaim")
                    }
                }
                if let message { Text(message).foregroundStyle(.secondary) }
                if isWorking { ProgressView().accessibilityLabel("Checking send") }
                if recovery.outcome == .pending || recovery.outcome == .unverified {
                    Button("Check status") { Task { await resolve() } }
                        .buttonStyle(.borderedProminent)
                        .disabled(isWorking)
                        .accessibilityIdentifier("cashu.send.check-recovery")
                }
                Button("Done", action: onDone).disabled(isWorking)
            }
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .task { if recovery.outcome == .unverified { await resolve() } }
        .interactiveDismissDisabled(isWorking)
        .alert("Reclaim this ecash?", isPresented: Binding(
            get: { reclaimID != nil }, set: { if !$0 { reclaimID = nil } }
        ), presenting: reclaimID) { id in
            Button("Cancel", role: .cancel) { reclaimID = nil }
            Button("Reclaim", role: .destructive) {
                reclaimID = nil
                Task { await resolve(reclaiming: id) }
            }
        } message: { _ in
            Text("If it hasn't been claimed, the ecash will return to your wallet and the recipient can no longer use this token. Mint fees may apply.")
        }
    }

    private var title: String {
        switch recovery.outcome {
        case .pending: return "Pending ecash"
        case .unverified: return "Check send status"
        case .completed: return "Payment complete"
        case .returned: return "Send resolved"
        }
    }

    @MainActor
    private func resolve(reclaiming id: String? = nil) async {
        isWorking = true
        onWorkingChanged(true)
        message = nil
        defer { isWorking = false; onWorkingChanged(false) }
        if let id {
            do {
                try await walletManager.reclaimPendingSend(
                    operationID: id, mintURL: recovery.mintURL, unit: recovery.unit
                )
                recovery.sends.removeAll { $0.id == id }
                recovery.outcome = .unverified
            } catch { message = error.walletMessage.text }
        }
        do {
            recovery = try await walletManager.checkSendRecovery(recovery)
            if recovery.outcome == .completed || recovery.outcome == .returned { message = nil }
        } catch {
            message = "Couldn't verify the send. Check your connection and try checking again."
        }
    }
}
