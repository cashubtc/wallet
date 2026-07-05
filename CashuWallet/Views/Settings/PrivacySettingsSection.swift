import SwiftUI

struct PrivacySettingsSection: View {
    @ObservedObject var settings = SettingsManager.shared
    @EnvironmentObject var walletManager: WalletManager

    var body: some View {
        LazyVStack(spacing: 0) {
            SettingsSectionGroup("Network") {
                Toggle(isOn: $settings.torEnabled) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Route traffic over Tor")
                        Text("Mint requests go through the Tor network, hiding your IP address from mints. Slower than a direct connection.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 4)
                .padding(.vertical, 14)

                transportStatusRow
            }
            .onChange(of: settings.torEnabled) { _, _ in
                walletManager.applyTransportPreference()
            }

            SettingsSectionGroup(nil) {
                Toggle("Check incoming invoice", isOn: $settings.checkIncomingInvoices)
                    .padding(.horizontal, 4)
                    .padding(.vertical, 14)

                Toggle("Check all invoices", isOn: $settings.periodicallyCheckIncomingInvoices)
                    .padding(.horizontal, 4)
                    .padding(.vertical, 14)
                    .disabled(!settings.checkIncomingInvoices)
                    .opacity(settings.checkIncomingInvoices ? 1.0 : 0.5)

                Toggle("Check sent ecash", isOn: $settings.checkSentTokens)
                    .padding(.horizontal, 4)
                    .padding(.vertical, 14)

                Toggle("Use WebSockets", isOn: $settings.useWebsockets)
                    .padding(.horizontal, 4)
                    .padding(.vertical, 14)
                    .disabled(!settings.checkIncomingInvoices && !settings.checkSentTokens)
                    .opacity((settings.checkIncomingInvoices || settings.checkSentTokens) ? 1 : 0.5)

                Toggle("Paste ecash automatically", isOn: $settings.autoPasteEcashReceive)
                    .padding(.horizontal, 4)
                    .padding(.vertical, 14)

                Toggle(isOn: $settings.sentryEnabled) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Send anonymous crash reports")
                        Text("Helps improve the app. No personal data, wallet addresses, or amounts are ever sent.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.horizontal, 4)
                .padding(.vertical, 14)
            }

            SettingsSectionFooter {
                Text("These settings affect your privacy and wallet responsiveness.")
            }
        }
    }

    /// Live transport indicator. `activeTransport` is nil while the wallet
    /// repository is starting up or being rebuilt after a toggle, so that
    /// state reads as "connecting".
    private var transportStatusRow: some View {
        HStack(spacing: 8) {
            if walletManager.activeTransport == nil {
                ProgressView()
                    .controlSize(.mini)
            } else {
                Circle()
                    .fill(statusColor)
                    .frame(width: 8, height: 8)
            }
            Text(statusText)
                .font(.caption)
                .foregroundStyle(.secondary)
            Spacer()
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 10)
    }

    private var statusColor: Color {
        switch walletManager.activeTransport {
        case .tor: return .green
        case .clearnet: return .orange
        case nil: return .secondary
        }
    }

    private var statusText: String {
        switch walletManager.activeTransport {
        case .tor:
            return "Tor active — mint traffic is routed through Tor"
        case .clearnet:
            return "Clearnet — mints can see your IP address"
        case nil:
            return settings.torEnabled ? "Starting Tor…" : "Connecting…"
        }
    }
}
