import Foundation
import XCTest
@testable import Field

// Parity for the film's copy of the field math. The web fixture and the
// inline vectors are the app's own (this repository, as of b49e88df):
//   docs/product/ascii-field-vectors.json      — terrain + currency, from the web TS
//   ios/CashuWalletTests/AsciiFieldTerrainTests.swift — warp (:110) + vault (:196)
//   ios/CashuWalletTests/AsciiFieldErosionTests.swift — erosion
// A failure means FieldMath.swift drifted from the app. Fix the copy, never a vector.

private let packageRoot = URL(fileURLWithPath: #filePath)
    .deletingLastPathComponent() // FieldTests
    .deletingLastPathComponent() // Tests
    .deletingLastPathComponent() // package root (tool/)

/// The app repository: tool/ lives at .agents/skills/cashu-promo-video/tool.
private let repoRoot = packageRoot
    .deletingLastPathComponent() // cashu-promo-video
    .deletingLastPathComponent() // skills
    .deletingLastPathComponent() // .agents
    .deletingLastPathComponent()

final class TerrainParityTests: XCTestCase {
    private struct TerrainRecord: Decodable { let x, y, t, f: Double; let b, level: Int }
    private struct CurrencyRecord: Decodable { let px, py: Double; let glyph: String }
    private struct Fixture: Decodable { let terrain: [TerrainRecord]; let currency: [CurrencyRecord] }

    /// Read straight from the app's docs, so the film can't validate against
    /// a stale copy.
    private var fixtureURL: URL { repoRoot.appendingPathComponent("docs/product/ascii-field-vectors.json") }

    private func loadFixture() throws -> Fixture {
        try JSONDecoder().decode(Fixture.self, from: Data(contentsOf: fixtureURL))
    }

    func testTerrainMatchesWebVectors() throws {
        let fixture = try loadFixture()
        XCTAssertGreaterThanOrEqual(fixture.terrain.count, 40)
        for r in fixture.terrain {
            XCTAssertEqual(AsciiFieldTerrain.fractal(r.x, r.y, r.t), r.f, accuracy: 1e-4, "fractal(\(r.x), \(r.y), \(r.t))")
            let b = AsciiFieldTerrain.brightness(r.x, r.y, r.t)
            XCTAssertEqual(Double(b), Double(r.b), accuracy: 1e-4, "brightness(\(r.x), \(r.y), \(r.t))")
            XCTAssertEqual(AsciiFieldTerrain.pickLevel(b), r.level, "level(\(r.x), \(r.y), \(r.t))")
        }
    }

    func testCurrencyGlyphsMatchWebVectors() throws {
        let fixture = try loadFixture()
        XCTAssertGreaterThanOrEqual(fixture.currency.count, 10)
        for r in fixture.currency {
            let i = AsciiFieldTerrain.currencyGlyphIndex(px: r.px, py: r.py)
            XCTAssertEqual(AsciiFieldTerrain.currencyGlyphs[i], r.glyph, "currency(\(r.px), \(r.py))")
        }
    }

    func testLevelTableShape() {
        XCTAssertEqual(AsciiFieldTerrain.levelMin, [40, 90, 140, 200, 216])
        XCTAssertEqual(AsciiFieldTerrain.levelGlyph, ["·", "/", ","])
        XCTAssertEqual(AsciiFieldTerrain.currencyGlyphs, ["$", "¥", "€"])
        XCTAssertEqual(AsciiFieldTerrain.pickLevel(39), -1)
        XCTAssertEqual(AsciiFieldTerrain.pickLevel(40), 0)
        XCTAssertEqual(AsciiFieldTerrain.pickLevel(255), 4)
    }

    func testDisplayLevelBoostsPeaks() {
        XCTAssertEqual(AsciiFieldTerrain.peakBoostMin, 208)
        XCTAssertEqual(AsciiFieldTerrain.displayLevel(207), 3)
        XCTAssertEqual(AsciiFieldTerrain.displayLevel(208), 4)
        XCTAssertEqual(AsciiFieldTerrain.displayLevel(216), 4)
        XCTAssertEqual(AsciiFieldTerrain.displayLevel(199), 2)
        XCTAssertEqual(AsciiFieldTerrain.displayLevel(39), -1)
    }
}

final class WarpParityTests: XCTestCase {
    func testDisplacement() {
        XCTAssertEqual(AsciiFieldWarp.displacement(60, 1), 36.0, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.displacement(52.5, 0.5), 18.0, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.displacement(50, 0.5), 17.91845990096719, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.displacement(0, 1), 0)
        XCTAssertEqual(AsciiFieldWarp.displacement(120, 1), 0)
        XCTAssertEqual(AsciiFieldWarp.displacement(105, 0.5), 0)
        XCTAssertEqual(AsciiFieldWarp.displacement(150, 1), 0)
        XCTAssertEqual(AsciiFieldWarp.displacement(60, 0), 0)
    }

    func testBloomedRadius() {
        XCTAssertEqual(AsciiFieldWarp.bloomedRadius(0), 90.0, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.bloomedRadius(0.5), 105.0, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.bloomedRadius(1), 120.0, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.bloomedRadius(2), 120.0, accuracy: 1e-9)
    }

    func testEnvelopes() {
        XCTAssertEqual(AsciiFieldWarp.pressEnvelope(elapsed: 0.28, from: 0), 1.0, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.pressEnvelope(elapsed: 0.14, from: 0), 1.025, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.pressEnvelope(elapsed: 0.07, from: 0.4), 0.848125, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.releaseEnvelope(elapsed: 0.3, from: 1), 0.125, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.releaseEnvelope(elapsed: 0.15, from: 0.8), 0.3375, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.releaseEnvelope(elapsed: 0.7, from: 1), 0.0, accuracy: 1e-9)
        for step in 0...200 {
            let k = AsciiFieldWarp.pressEnvelope(elapsed: Double(step) / 200 * 0.28, from: 0)
            XCTAssertLessThanOrEqual(k, 1.0529)
            XCTAssertGreaterThanOrEqual(k, 0)
        }
    }

    func testSwirlAndFollow() {
        XCTAssertEqual(AsciiFieldWarp.swirlAngle(36), 0.35, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.swirlAngle(18), 0.175, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.swirlAngle(0), 0.0, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.followFactor(1.0 / 30.0), 0.3788548423845485, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.followFactor(1.0 / 60.0), 0.21187237225468902, accuracy: 1e-9)
        XCTAssertEqual(AsciiFieldWarp.followFactor(0), 0.0, accuracy: 1e-9)
    }

    func testDisplacementNeverFoldsSampling() {
        var previous = -Double.infinity
        for step in 0...1200 {
            let d = Double(step) / 10
            let warped = d - AsciiFieldWarp.displacement(d, 1.053)
            XCTAssertGreaterThanOrEqual(warped, previous - 1e-12, "fold at d=\(d)")
            previous = warped
        }
    }
}

final class VaultParityTests: XCTestCase {
    private let vectors: [(Double, Double, Double, Double, Int)] = [
        (195, 154, 2.5, 178.07999999999998, 2),
        (195, 300, 2.5, 227.44, 4),
        (195, 258, 2.5, 35.48, -1),
        (243, 300, 2.5, 182.99999999999977, 2),
        (195, 208, 2.5, 173.88, 2),
        (287, 300, 2.5, 182.99999999999955, 2),
        (261, 300, 2.5, 181.87999999999968, 2),
        (247, 248, 2.5, 37.44, -1),
        (316, 300, 2.5, 194.64, 2),
        (309, 235, 2.5, 58.72, 0),
        (195, 450, 2.5, 126.40727272727273, 1),
        (30, 60, 2.5, -9.24, -1),
        (201, 307, 0.0, 38.56, -1),
        (189, 293, 0.0, 207.84, 3),
        (219, 244, 2.5, 205.32, 3),
        (159, 300, 2.5, 202.52, 3),
    ]

    func testVaultBrightness() {
        for (px, py, t, expected, level) in vectors {
            let b = AsciiFieldVault.brightness(px: px, py: py, centerX: 195, centerY: 300, t: t)
            XCTAssertEqual(b, expected, accuracy: 1e-6, "vault(\(px), \(py), t=\(t))")
            XCTAssertEqual(AsciiFieldTerrain.displayLevel(b), level, "level(\(px), \(py), t=\(t))")
        }
    }

    func testMorphLerp() {
        let terrain = Double(AsciiFieldTerrain.brightness(2.3725, 3.045714285714286, 2.5))
        XCTAssertEqual(terrain, 151, accuracy: 1e-9)
        let vault = AsciiFieldVault.brightness(px: 219, py: 328, centerX: 195, centerY: 300, t: 2.5)
        XCTAssertEqual(vault, 58.44, accuracy: 1e-6)
        let mixed = terrain + (vault - terrain) * 0.5
        XCTAssertEqual(mixed, 104.72, accuracy: 1e-6)
        XCTAssertEqual(AsciiFieldTerrain.displayLevel(mixed), 1)
    }

    func testOutsideInkNeverDraws() {
        for step in 0..<200 {
            let px = Double(step) * 7.3
            let py = Double(step) * 11.1 + 1400
            let b = AsciiFieldVault.brightness(px: px, py: py, centerX: 0, centerY: 0, t: Double(step) * 0.17)
            XCTAssertLessThan(abs(b), 40)
        }
    }

    func testExtent() {
        XCTAssertEqual(AsciiFieldVault.extentRadius, 157)
    }
}

final class ErosionParityTests: XCTestCase {
    private let eps = 1e-9

    func testEndpoints() {
        for level in 0...4 {
            XCTAssertEqual(AsciiFieldTerrain.erosionAlpha(level: level, progress: 0), 1, accuracy: eps)
            XCTAssertEqual(AsciiFieldTerrain.erosionAlpha(level: level, progress: 1), 0, accuracy: eps)
        }
    }

    func testPeaksOutlastThePlain() {
        for step in 0...100 {
            let p = Double(step) / 100
            for level in 1...4 {
                XCTAssertGreaterThanOrEqual(
                    AsciiFieldTerrain.erosionAlpha(level: level, progress: p),
                    AsciiFieldTerrain.erosionAlpha(level: level - 1, progress: p) - eps
                )
            }
        }
        XCTAssertEqual(AsciiFieldTerrain.erosionAlpha(level: 0, progress: 0.5), 0, accuracy: eps)
        XCTAssertGreaterThan(AsciiFieldTerrain.erosionAlpha(level: 4, progress: 0.5), 0.99)
    }

    func testPinnedVectors() {
        XCTAssertEqual(AsciiFieldTerrain.erosionAlpha(level: 0, progress: 0.24), 0.5, accuracy: eps)
        XCTAssertEqual(AsciiFieldTerrain.erosionAlpha(level: 2, progress: 0.50), 0.5, accuracy: eps)
        XCTAssertEqual(AsciiFieldTerrain.erosionAlpha(level: 4, progress: 0.76), 0.5, accuracy: eps)
    }
}
