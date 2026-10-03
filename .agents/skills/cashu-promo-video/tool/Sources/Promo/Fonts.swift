import CoreText
import Foundation

/// Package-root paths, resolved from this file so the CLI runs from anywhere.
enum Paths {
    static let root = URL(fileURLWithPath: #filePath)
        .deletingLastPathComponent() // Promo
        .deletingLastPathComponent() // Sources
        .deletingLastPathComponent()
    static let fonts = root.appendingPathComponent("Fonts")
    static let takes = root.appendingPathComponent("takes")
    static let renders = root.appendingPathComponent("renders")
}

/// The film's two faces. Inter Display carries every card the film sets
/// itself; SF Pro only ever appears inside real footage (its license covers UI
/// mock-ups, not marketing). JetBrains Mono carries the field and has a
/// native U+20BF, so ₿ is never synthesized.
enum Fonts {
    static func register() throws {
        let files = try FileManager.default.contentsOfDirectory(at: Paths.fonts, includingPropertiesForKeys: nil)
            .filter { $0.pathExtension == "ttf" }
        for url in files {
            var error: Unmanaged<CFError>?
            if !CTFontManagerRegisterFontsForURL(url as CFURL, .process, &error) {
                let err = error?.takeRetainedValue()
                // Already registered in this process is fine.
                if let err, CFErrorGetCode(err) != 105 { throw PromoError("font \(url.lastPathComponent): \(err)") }
            }
        }
    }

    static func display(_ size: CGFloat, weight: String = "SemiBold") -> CTFont {
        CTFontCreateWithName("InterDisplay-\(weight)" as CFString, size, nil)
    }

    static func mono(_ size: CGFloat) -> CTFont {
        CTFontCreateWithName("JetBrainsMono-Regular" as CFString, size, nil)
    }
}
