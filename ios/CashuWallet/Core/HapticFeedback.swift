import UIKit

/// Centralized haptic feedback utility following Apple HIG.
/// Reuses prepared static feedback generators on the main actor to avoid
/// allocating a fresh generator on every tap or keystroke.
@MainActor
enum HapticFeedback {
    private static let lightImpact = preparedImpactGenerator(.light)
    private static let mediumImpact = preparedImpactGenerator(.medium)
    private static let heavyImpact = preparedImpactGenerator(.heavy)
    private static let softImpact = preparedImpactGenerator(.soft)
    private static let rigidImpact = preparedImpactGenerator(.rigid)

    private static let notificationGenerator: UINotificationFeedbackGenerator = {
        let generator = UINotificationFeedbackGenerator()
        generator.prepare()
        return generator
    }()

    private static let selectionGenerator: UISelectionFeedbackGenerator = {
        let generator = UISelectionFeedbackGenerator()
        generator.prepare()
        return generator
    }()

    private static func preparedImpactGenerator(
        _ style: UIImpactFeedbackGenerator.FeedbackStyle
    ) -> UIImpactFeedbackGenerator {
        let generator = UIImpactFeedbackGenerator(style: style)
        generator.prepare()
        return generator
    }

    /// Light impact — for UI selections, toggles
    static func impact(_ style: UIImpactFeedbackGenerator.FeedbackStyle = .medium) {
        let generator: UIImpactFeedbackGenerator
        switch style {
        case .light:
            generator = lightImpact
        case .medium:
            generator = mediumImpact
        case .heavy:
            generator = heavyImpact
        case .soft:
            generator = softImpact
        case .rigid:
            generator = rigidImpact
        @unknown default:
            generator = UIImpactFeedbackGenerator(style: style)
        }
        generator.impactOccurred()
        generator.prepare()
    }

    /// Notification feedback — for success, error, warning outcomes
    static func notification(_ type: UINotificationFeedbackGenerator.FeedbackType) {
        notificationGenerator.notificationOccurred(type)
        notificationGenerator.prepare()
    }

    /// Selection changed — for picker/selection changes
    static func selection() {
        selectionGenerator.selectionChanged()
        selectionGenerator.prepare()
    }
}
