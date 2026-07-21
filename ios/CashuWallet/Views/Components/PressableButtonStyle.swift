import SwiftUI

/// Subtle press feedback — scales to 0.97 on touch-down, springs back on release.
/// Asymmetric timing (faster compress than release) keeps the touch feeling immediate
/// while the release stays organic. No color or shadow change — the surface just
/// acknowledges the press.
struct PressableButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .scaleEffect(configuration.isPressed ? 0.97 : 1.0)
            .animation(
                .snappy(duration: configuration.isPressed ? 0.09 : 0.18),
                value: configuration.isPressed
            )
    }
}

// MARK: - Circular glass method button

/// One round icon button with a one-word caption below it — the shared "method"
/// button used by both the Send and Receive sheets (Scan · Ecash · Tap /
/// Scan · Ecash · Bitcoin). Apple's sheet-action-circle pattern (Maps, Find My,
/// Wallet): a monochrome symbol on a `.quaternary` fill. These buttons scroll
/// with the sheet's content, and the content layer gets no Liquid Glass —
/// glass sitting on the sheet's own glass background can't sample it and turns
/// invisible under the system Clear appearance. The semantic fill adapts to
/// light/dark and Increase Contrast, and renders identically under the Clear
/// and Tinted system glass settings. Metrics mirror Android's
/// `CircularMethodButton` (72dp circle · 32dp icon · 40dp row gap) so the
/// Send/Receive sheets match across platforms.
struct CircularGlassIconButton: View {
    let icon: String
    let label: String
    let a11y: String
    let action: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            Button(action: action) {
                Image(systemName: icon)
                    .font(.title)
                    .foregroundStyle(.primary)
                    .frame(width: 72, height: 72)
                    .background(.quaternary, in: Circle())
            }
            .buttonStyle(PressableButtonStyle())

            Text(label)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(a11y)
        .accessibilityAddTraits(.isButton)
    }
}
