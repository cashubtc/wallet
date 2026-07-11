import SwiftUI

/// Compact payment-method glyphs for mint rows — SF Symbol when known,
/// otherwise a border-only text pill (no fill). Sits on the same line as the mint name.
struct MintMethodIcons: View {
    let methods: [PaymentMethodKind]

    var body: some View {
        if !methods.isEmpty {
            HStack(spacing: 4) {
                ForEach(methods, id: \.self) { method in
                    MethodGlyph(method: method)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(methods.map(\.displayName).joined(separator: ", "))
        }
    }
}

private struct MethodGlyph: View {
    let method: PaymentMethodKind

    var body: some View {
        if let symbol = method.rowSymbol {
            Image(systemName: symbol)
                .font(.caption.weight(.semibold))
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)
        } else {
            Text(method.displayName)
                .font(.caption2.weight(.semibold))
                .foregroundStyle(.secondary)
                .padding(.horizontal, 6)
                .padding(.vertical, 2)
                .overlay(
                    Capsule()
                        .strokeBorder(.tertiary, lineWidth: 1)
                )
                .accessibilityHidden(true)
        }
    }
}

private extension PaymentMethodKind {
    /// Row glyph for discovery / list chrome. Nil → bordered text pill fallback.
    var rowSymbol: String? {
        switch self {
        case .bolt11: return "bolt.fill"
        case .bolt12: return "arrow.2.squarepath"
        case .onchain: return "bitcoinsign"
        }
    }
}

#Preview {
    MintMethodIcons(methods: [.bolt11, .bolt12, .onchain])
        .padding()
}
