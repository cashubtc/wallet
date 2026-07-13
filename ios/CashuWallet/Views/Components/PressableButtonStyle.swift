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

/// One round glass icon button with a one-word caption on the canvas below it —
/// the shared "method" button used by both the Send and Receive sheets
/// (Scan · Ecash · Tap / Scan · Ecash · Bitcoin). On iOS 26, the configurable
/// native glass button style retains the system-owned inset and interaction,
/// while a subtle adaptive tint keeps it distinct from the sheet's own glass
/// without turning three peer actions into prominent filled controls. iOS 18–25
/// falls back to a `.quaternary` circle. Wrap a row in a
/// `GlassEffectContainer(spacing:)` on iOS 26 so the adjacent circular glass
/// surfaces sample light consistently (glass can't sample other glass).
struct CircularGlassIconButton: View {
    let icon: String
    let label: String
    let a11y: String
    let action: () -> Void

    var body: some View {
        VStack(spacing: 8) {
            if #available(iOS 26, *) {
                Button(action: action) {
                    Image(systemName: icon)
                        .font(.title2)
                        .foregroundStyle(.primary)
                        .frame(width: 64, height: 64)
                }
                .buttonStyle(.glass(.regular.tint(Color.primary.opacity(0.15))))
                .buttonBorderShape(.circle)
            } else {
                Button(action: action) {
                    Image(systemName: icon)
                        .font(.title2)
                        .foregroundStyle(.primary)
                        .frame(width: 64, height: 64)
                        .background(.quaternary, in: Circle())
                }
                .buttonStyle(PressableButtonStyle())
            }

            Text(label)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
        }
        .accessibilityElement(children: .combine)
        .accessibilityLabel(a11y)
        .accessibilityAddTraits(.isButton)
    }
}
