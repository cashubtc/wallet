import SwiftUI

struct MintPickerRow: View {
    let mint: MintInfo
    let subtitle: String
    let isSelected: Bool
    var showsSelectionBadge = false
    var truncatesSubtitle = false
    var usesMonogram = false

    var body: some View {
        HStack(spacing: 12) {
            MintAvatarView(iconUrl: mint.iconUrl, name: mint.name, size: 40,
                           fallbackSystemImage: usesMonogram ? nil : "bitcoinsign.bank.building",
                           showsBorder: usesMonogram)
                .overlay(alignment: .bottomTrailing) {
                    if showsSelectionBadge && isSelected {
                        Circle().fill(.green).frame(width: 12, height: 12)
                            .overlay(Circle().stroke(Color(.systemBackground), lineWidth: 2))
                            .offset(x: 2, y: 2)
                    }
                }
            VStack(alignment: .leading, spacing: 2) {
                Text(mint.name).font(.body.weight(.medium))
                Text(subtitle).font(.subheadline).foregroundStyle(.secondary)
                    .lineLimit(truncatesSubtitle ? 1 : nil)
                    .truncationMode(truncatesSubtitle ? .middle : .tail)
            }
            Spacer()
            if isSelected {
                Image(systemName: "checkmark").foregroundStyle(Color.accentColor)
            }
        }
        .contentShape(Rectangle())
    }
}
