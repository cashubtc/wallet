import XCTest
@testable import CashuWallet

/// The ratchet for motion, in the manner of `TypographyGuardTests`.
///
/// Curves and durations live in `CashuMotion` so a feel can be retuned in one
/// place. These tests keep hand-typed values from drifting back to call sites.
///
/// - **Bans** are absolute. Their allowlists name the one sanctioned exception.
/// - **Ratchets** carry a ceiling that reflects today's known debt. Lower them;
///   never raise them.
final class MotionGuardTests: XCTestCase {

    // MARK: - Source scanning

    /// The app sources minus `CashuMotion.swift` itself, with comment lines
    /// dropped: prose may name a curve, code may not.
    private func sources() throws -> [(path: String, code: String)] {
        let root = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()   // CashuWalletTests
            .deletingLastPathComponent()   // ios
            .appendingPathComponent("CashuWallet")
            .resolvingSymlinksInPath()

        try XCTSkipUnless(
            FileManager.default.fileExists(atPath: root.path),
            "Source tree not readable from the test host — source-scanning guards skipped"
        )

        guard let walker = FileManager.default.enumerator(
            at: root, includingPropertiesForKeys: nil
        ) else { return [] }

        return walker.compactMap { entry in
            guard let url = entry as? URL, url.pathExtension == "swift" else { return nil }
            let path = url.resolvingSymlinksInPath().path.replacingOccurrences(of: root.path + "/", with: "")
            guard path != "DesignSystem/CashuMotion.swift" else { return nil }
            guard let text = try? String(contentsOf: url, encoding: .utf8) else { return nil }
            let code = text
                .split(separator: "\n", omittingEmptySubsequences: false)
                .filter { !$0.drop(while: { $0 == " " || $0 == "\t" }).hasPrefix("//") }
                .joined(separator: "\n")
            return (path, code)
        }
    }

    private func occurrences(of pattern: String, in text: String) -> Int {
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return 0 }
        return regex.numberOfMatches(in: text, range: NSRange(text.startIndex..., in: text))
    }

    // MARK: - Bans

    /// **Ban.** Nothing eases in.
    ///
    /// An ease-in curve starts slowly, which delays the exact moment the user is
    /// watching. The one exception is the 90 ms outgoing half of the staged
    /// fade-through in `SharedAxis`, where the face is gone before the curve
    /// can be read.
    func testNothingEasesInOutsideSharedAxis() throws {
        let allowed: Set<String> = ["Views/Components/LiquidGlassModifiers.swift"]
        let offenders = Set(
            try sources()
                .filter { occurrences(of: #"\.easeIn\("#, in: $0.code) > 0 }
                .map(\.path)
        )

        XCTAssertTrue(
            offenders.subtracting(allowed).isEmpty,
            """
            `.easeIn` on interface motion. Entrances and exits ease out; things \
            moving on screen ease in-out. Offenders: \(offenders.subtracting(allowed).sorted())
            """
        )
    }

    /// **Ban.** Nothing grows out of nothing.
    ///
    /// A bare `.scale` transition starts at zero. Entrances start near full size
    /// (0.9 and up) and fade, so the element arrives rather than inflates.
    ///
    /// The allowlist is empty and must stay that way.
    func testNothingScalesInFromZero() throws {
        let pattern = #"\.transition\(\s*\.scale\s*\)|\.scale\.combined\(|scaleEffect\(\s*0(\.0+)?\s*[,)]"#
        let offenders = try sources()
            .filter { occurrences(of: pattern, in: $0.code) > 0 }
            .map(\.path)

        XCTAssertTrue(
            offenders.isEmpty,
            """
            A scale entrance from zero. Use `.scale(scale: 0.9…)` combined with \
            `.opacity`. Offenders: \(offenders.sorted())
            """
        )
    }

    // MARK: - Ratchets

    /// **Ratchet.** Curves typed at call sites.
    ///
    /// What remains is `SharedAxis` (which names its own timings), onboarding's
    /// exempt choreography, and a handful of values used exactly once. A value
    /// that is needed a second time gets a role in `CashuMotion` instead.
    ///
    /// Lower this number. Never raise it.
    func testHandTypedCurvesDoNotSpread() throws {
        let ceiling = 48
        let pattern = #"\.(snappy|smooth|easeInOut|easeOut|easeIn|spring|linear|bouncy|interactiveSpring)\("#
        let counts = try sources()
            .map { (path: $0.path, n: occurrences(of: pattern, in: $0.code)) }
            .filter { $0.n > 0 }
        let total = counts.reduce(0) { $0 + $1.n }

        XCTAssertLessThanOrEqual(
            total, ceiling,
            """
            Hand-typed animation curves grew from \(ceiling) to \(total). Use a \
            CashuMotion role, or add one if the value now has a second use. \
            Current: \(counts.sorted { $0.path < $1.path }.map { "\($0.path):\($0.n)" })
            """
        )
    }

    /// **Ratchet.** A bare `.snappy` is SwiftUI's half-second default.
    ///
    /// It read as "quick" at call sites while being the slowest curve in the
    /// app, and it sat on the number pad. A half-second spring is named
    /// (`CashuMotion.settle`, `CashuMotion.valueRoll`) so choosing it is
    /// deliberate.
    ///
    /// The allowlist is empty and must stay that way.
    func testNoBareSnappyAtCallSites() throws {
        let offenders = try sources()
            .filter { occurrences(of: #"(?<![\w\)])\.snappy(?![\w(])"#, in: $0.code) > 0 }
            .map(\.path)

        XCTAssertTrue(
            offenders.isEmpty,
            """
            Bare `.snappy` (0.5 s). Use CashuMotion.flip for anything tapped \
            often, or CashuMotion.settle when half a second is intended. \
            Offenders: \(offenders.sorted())
            """
        )
    }

    // MARK: - The vocabulary

    /// The press pair is the one role with two speeds: down must be the faster.
    func testPressIsFasterDownThanUp() {
        XCTAssertNotEqual(
            CashuMotion.press(isPressed: true),
            CashuMotion.press(isPressed: false)
        )
        XCTAssertEqual(CashuMotion.press(isPressed: true), .snappy(duration: 0.09))
        XCTAssertEqual(CashuMotion.press(isPressed: false), .snappy(duration: 0.18))
    }

    /// The flip is the documented quarter second, half of the default spring.
    func testFlipIsAQuarterSecond() {
        XCTAssertEqual(CashuMotion.flip, .snappy(duration: 0.25))
        XCTAssertNotEqual(CashuMotion.flip, CashuMotion.settle)
    }
}
