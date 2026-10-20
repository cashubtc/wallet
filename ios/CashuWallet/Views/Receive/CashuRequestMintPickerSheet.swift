import SwiftUI

struct CashuRequestMintPickerSheet: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject var walletManager: WalletManager

    /// The currently-selected mint URL for the request, or `nil` for "Any mint".
    let currentMintUrl: String?
    /// Called with the new mint URL on selection (`nil` = Any mint). Sheet dismisses afterwards.
    let onSelect: (String?) -> Void

    var body: some View {
        NavigationStack {
            List {
                Button(action: { select(nil) }) {
                    HStack(spacing: 12) {
                        anyMintIcon
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Any mint")
                                .font(.body.weight(.medium))
                            Text("Sender chooses the mint")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        Spacer()
                        if currentMintUrl == nil {
                            Image(systemName: "checkmark")
                                .foregroundStyle(Color.accentColor)
                        }
                    }
                }
                .listRowSeparator(.hidden)
                .listRowBackground(Color.clear)
                .buttonStyle(.plain)

                ForEach(walletManager.mints) { mint in
                    Button(action: { select(mint.url) }) {
                        MintPickerRow(mint: mint, subtitle: SettingsManager.shared.formatAmountBalance(mint.balance) + " sat", isSelected: currentMintUrl == mint.url)
                    }
                    .listRowSeparator(.hidden)
                    .listRowBackground(Color.clear)
                    .buttonStyle(.plain)
                }
            }
            .listStyle(.plain)
            .scrollContentBackground(.hidden)
            .background(Color.clear)
            .navigationTitle("Mint")
            .navigationBarTitleDisplayMode(.inline)
        }
        .compactBottomSheetSurface()
    }

    private func select(_ mintUrl: String?) {
        HapticFeedback.selection()
        onSelect(mintUrl)
        dismiss()
    }

    // MARK: - Icons

    private var anyMintIcon: some View {
        Circle()
            .fill(.quaternary)
            .frame(width: 40, height: 40)
            .overlay(
                Image(systemName: "infinity")
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(.secondary)
            )
    }


}

/// Unit chooser for a Cashu request the user created. Lists the units the
/// request's mint advertises; selecting one re-encodes the request in that unit.
struct CashuRequestUnitPickerSheet: View {
    let units: [String]
    let currentUnit: String
    let onSelect: (String) -> Void

    var body: some View {
        UnitSelectorSheet(units: units, selectedUnit: currentUnit, onSelect: onSelect, title: "Unit", usesList: true)
    }
}
