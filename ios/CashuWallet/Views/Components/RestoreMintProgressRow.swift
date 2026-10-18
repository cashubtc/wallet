import SwiftUI

struct RestoreMintProgressRow: View {
    let url: String
    let phase: MintRestorePhase
    let iconUrl: String?
    let name: String
    let onRetry: () -> Void

    var body: some View {
        let recovered: RestoreMintResult? = {
            if case .recovered(let result) = phase { return result }
            return nil
        }()

        HStack(spacing: 12) {
            MintAvatarView(
                iconUrl: recovered?.iconUrl ?? iconUrl,
                name: recovered?.mintName ?? name
            )

            VStack(alignment: .leading, spacing: 2) {
                Text(recovered?.mintName ?? name)
                    .font(.subheadline.weight(.medium))
                    .lineLimit(1)

                if case .failed(let message) = phase {
                    InlineNotice(message: message, severity: .error)
                } else {
                    Text(url)
                        .font(.caption2)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
            }

            Spacer()

            switch phase {
            case .pending, .restoring:
                ProgressView()
                    .controlSize(.small)
            case .recovered(let result):
                HStack(spacing: 6) {
                    Image(systemName: result.totalRecovered > 0 ? "checkmark.circle.fill" : "minus.circle")
                        .foregroundStyle(result.totalRecovered > 0 ? .green : .secondary)
                        .contentTransition(.symbolEffect(.replace))
                    Text("\(result.unspent) sats")
                        .font(.subheadline.weight(result.unspent > 0 ? .semibold : .regular))
                        .monospacedDigit()
                        .foregroundStyle(result.unspent > 0 ? .primary : .secondary)
                }
            case .failed:
                Button("Retry") { onRetry() }
                    .textLinkButton()
            }
        }
        .padding(.horizontal, 4)
        .padding(.vertical, 12)
        .contentShape(Rectangle())
    }
}
