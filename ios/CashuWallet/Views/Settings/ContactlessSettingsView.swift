import SwiftUI

struct ContactlessSettingsView: View {
    @State private var perTap = "1000"
    @State private var daily = "10000"
    @State private var error: String?
    @State private var ready = false
    @State private var saving = false
    @State private var saved = false

    private var limits: ContactlessLimits? {
        guard let tap = UInt64(perTap), let day = UInt64(daily) else { return nil }
        let value = ContactlessLimits(perTap: tap, daily: day)
        return value.isValid ? value : nil
    }

    var body: some View {
        Form {
            Section {
                LabeledContent("Per-tap limit (sats)") {
                    TextField("1000", text: $perTap)
                        .keyboardType(.numberPad).multilineTextAlignment(.trailing)
                        .accessibilityLabel("Per-tap limit in sats")
                        .disabled(saving)
                }
                LabeledContent("Daily limit (sats)") {
                    TextField("10000", text: $daily)
                        .keyboardType(.numberPad).multilineTextAlignment(.trailing)
                        .accessibilityLabel("Daily limit in sats")
                        .disabled(saving)
                }
            } footer: {
                Text("Payments above the per-tap limit require device authentication. Set it to 0 to authenticate every tap. The daily limit stops further contactless payments.")
            }
            Section {
                Button(saved ? "Saved" : "Save limits") {
                    guard let limits else { return }
                    saving = true
                    Task { @MainActor in
                        defer { saving = false }
                        guard await AppLockManager.shared.authenticateContactless(reason: "Change contactless limits") else {
                            error = ContactlessPaymentError.authentication.localizedDescription
                            return
                        }
                        do {
                            try Task.checkCancellation()
                            try ContactlessSpendPolicy.shared.saveLimits(limits)
                            saved = true
                            error = nil
                        } catch { self.error = error.localizedDescription }
                    }
                }
                .disabled(!ready || saving || limits == nil)
                if let error { Text(error).foregroundStyle(.red).accessibilityAddTraits(.isStaticText) }
            } footer: {
                Text("Limits include fees. Interrupted payments also count toward the daily limit. The daily limit applies across wallets on this device and resets at midnight UTC.")
            }
        }
        .navigationTitle("Contactless")
        .task {
            do {
                let state = try ContactlessSpendPolicy.shared.state()
                perTap = String(state.limits.perTap); daily = String(state.limits.daily)
                ready = true
            } catch { self.error = error.localizedDescription }
        }
        .onChange(of: perTap) { _, _ in saved = false }
        .onChange(of: daily) { _, _ in saved = false }
    }
}
