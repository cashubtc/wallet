import CoreGraphics
import CoreText
import Field
import Foundation

/// Where the app's point space sits in the frame: film px = origin + scale·pt.
/// The field always lives in the *app's* coordinates, so the canvas grid
/// continues the in-app grid past the phone's edges cell for cell — that
/// continuity is the whole signature move.
struct AppSpace {
    var origin: CGPoint
    var scale: Double

    func px(_ x: Double, _ y: Double) -> CGPoint {
        CGPoint(x: origin.x + x * scale, y: origin.y + y * scale)
    }

    func pt(_ p: CGPoint) -> (x: Double, y: Double) {
        ((Double(p.x) - Double(origin.x)) / scale, (Double(p.y) - Double(origin.y)) / scale)
    }

    /// The phone's screen rect in film px (402 × 874 pt, iPhone 17 Pro).
    var screen: CGRect {
        CGRect(x: origin.x, y: origin.y, width: Phone.width * scale, height: Phone.height * scale)
    }
}

enum Phone {
    static let width = 402.0
    static let height = 874.0
    /// Capture scale: 1206 × 2622 px.
    static let captureScale = 3.0
    /// iPhone 17 Pro display corner radius, in points.
    static let cornerRadius = 62.0
}

/// A lens pressed into the field (grid points, app space).
struct Lens {
    var x: Double
    var y: Double
    var k: Double
}

struct FieldParams {
    var t: Double
    var space: AppSpace
    /// Pixel rect to populate; cells outside are skipped.
    var region: CGRect
    var vaultMix: Double = 0
    var vaultCenter: (x: Double, y: Double) = (201, 437)
    var erosion: Double = 0
    var lens: Lens? = nil
    /// Opacity multiplier as a function of app-space y (points) — the app's
    /// vertical mask, continued past the screen so the landscape reads as one.
    var mask: ((Double) -> Double)? = nil
    /// Per-cell multiplier on top of `mask`, in app points (x, y). Used to
    /// quiet the field around a subject.
    var spot: ((Double, Double) -> Double)? = nil
    var alpha: Double = 1
    var ramp: [Double] = Ink.ramp
    /// Replaces a cell's brightness: (col, row, terrain brightness) → value.
    /// (Unused by the current films.)
    var cellBrightness: ((Int, Int, Double) -> Double)? = nil
}

/// Draws the ASCII field with the app's math and the app's cell geometry:
/// glyph centers at (col·12 + 6, row·14 + 7) pt, sampled at (col + ½, row + ½)
/// × terrainScale — AsciiField.swift:891-925.
final class FieldPainter {
    private var fontCache: [Int: (CTFont, [CGGlyph], CGFloat, CGFloat)] = [:]

    /// Glyph table order: levels 0–2, then $ ¥ €, then ₿.
    private static let glyphs = AsciiFieldTerrain.levelGlyph + AsciiFieldTerrain.currencyGlyphs + ["₿"]

    private func font(for scale: Double) -> (CTFont, [CGGlyph], CGFloat, CGFloat) {
        let key = Int((scale * 1000).rounded())
        if let hit = fontCache[key] { return hit }
        let font = Fonts.mono(CGFloat(12 * scale))
        var glyphs: [CGGlyph] = []
        for g in Self.glyphs {
            var chars = Array(g.utf16)
            var out = [CGGlyph](repeating: 0, count: chars.count)
            CTFontGetGlyphsForCharacters(font, &chars, &out, chars.count)
            glyphs.append(out[0])
        }
        // SwiftUI centers a resolved Text's typographic box on the point:
        // advance wide, line-height tall. Every glyph here is monospaced.
        var g0 = glyphs[0]
        var adv = CGSize.zero
        CTFontGetAdvancesForGlyphs(font, .horizontal, &g0, &adv, 1)
        let ascent = CTFontGetAscent(font), descent = CTFontGetDescent(font), leading = CTFontGetLeading(font)
        let lineHeight = ascent + descent + leading
        let baselineFromCenter = -lineHeight / 2 + ascent
        let entry = (font, glyphs, adv.width / 2, baselineFromCenter)
        fontCache[key] = entry
        return entry
    }

    func draw(_ p: FieldParams, into ctx: CGContext) {
        let (font, glyphTable, halfAdvance, baselineFromCenter) = font(for: p.space.scale)
        let cellW = AsciiFieldTerrain.cellW, cellH = AsciiFieldTerrain.cellH
        let scale = AsciiFieldTerrain.terrainScale

        // Cell range covering the region, in app grid indices (may be negative).
        let topLeft = p.space.pt(CGPoint(x: p.region.minX, y: p.region.minY))
        let bottomRight = p.space.pt(CGPoint(x: p.region.maxX, y: p.region.maxY))
        let col0 = Int((topLeft.x / cellW).rounded(.down)) - 1
        let col1 = Int((bottomRight.x / cellW).rounded(.up)) + 1
        let row0 = Int((topLeft.y / cellH).rounded(.down)) - 1
        let row1 = Int((bottomRight.y / cellH).rounded(.up)) + 1

        let vaultReach2 = AsciiFieldVault.extentRadius * AsciiFieldVault.extentRadius
        ctx.saveGState()
        ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)

        var positions: [[CGPoint]] = Array(repeating: [], count: glyphTable.count)
        for row in row0...row1 {
            let py = Double(row) * cellH + cellH / 2
            var rowAlpha = p.alpha * (p.mask?(py) ?? 1)
            if rowAlpha <= 0.002 { continue }
            rowAlpha = min(1, rowAlpha)
            for i in positions.indices { positions[i].removeAll(keepingCapacity: true) }
            var spotAlphas: [(Int, CGPoint, Double)] = []
            let sy = (Double(row) + 0.5) * scale
            for col in col0...col1 {
                let px = Double(col) * cellW + cellW / 2
                var sampleX = (Double(col) + 0.5) * scale
                var sampleY = sy
                var wpx = px, wpy = py
                if let lens = p.lens, lens.k > 0 {
                    let dx = px - lens.x, dy = py - lens.y
                    let d = (dx * dx + dy * dy).squareRoot()
                    let f = AsciiFieldWarp.displacement(d, lens.k)
                    if f > 0 {
                        let theta = AsciiFieldWarp.swirlAngle(f)
                        let c = cos(theta), s = sin(theta)
                        let inv = f / d
                        wpx = px - (dx * c - dy * s) * inv
                        wpy = py - (dx * s + dy * c) * inv
                        sampleX = wpx / cellW * scale
                        sampleY = wpy / cellH * scale
                    }
                }
                let level: Int
                if let override = p.cellBrightness {
                    let terrain = Double(AsciiFieldTerrain.brightness(sampleX, sampleY, p.t))
                    level = AsciiFieldTerrain.displayLevel(override(col, row, terrain))
                } else if p.vaultMix <= 0 {
                    level = AsciiFieldTerrain.displayLevel(AsciiFieldTerrain.brightness(sampleX, sampleY, p.t))
                } else if p.vaultMix >= 1 {
                    let dx = wpx - p.vaultCenter.x, dy = wpy - p.vaultCenter.y
                    if dx * dx + dy * dy > vaultReach2 { continue }
                    level = AsciiFieldTerrain.displayLevel(AsciiFieldVault.brightness(
                        px: wpx, py: wpy, centerX: p.vaultCenter.x, centerY: p.vaultCenter.y, t: p.t))
                } else {
                    let terrain = Double(AsciiFieldTerrain.brightness(sampleX, sampleY, p.t))
                    let vault = AsciiFieldVault.brightness(
                        px: wpx, py: wpy, centerX: p.vaultCenter.x, centerY: p.vaultCenter.y, t: p.t)
                    level = AsciiFieldTerrain.displayLevel(terrain + (vault - terrain) * p.vaultMix)
                }
                if level < 0 { continue }
                let glyphIndex: Int
                if level == AsciiFieldTerrain.currencyLevel {
                    glyphIndex = 3 + AsciiFieldTerrain.currencyGlyphIndex(px: px, py: py)
                } else if level >= AsciiFieldTerrain.peakLevel {
                    glyphIndex = 6
                } else {
                    glyphIndex = level
                }
                let center = p.space.px(px, py)
                // Glyph positions are in *text* space, which the flipped text
                // matrix mirrors — so y goes in negated to land at +y.
                let origin = CGPoint(x: center.x - halfAdvance, y: -(center.y + baselineFromCenter))
                if let spot = p.spot {
                    let a = spot(px, py)
                    if a <= 0.002 { continue }
                    if a < 0.999 { spotAlphas.append((glyphIndex, origin, a)); continue }
                }
                positions[glyphIndex].append(origin)
            }
            for (gi, pts) in positions.enumerated() where !pts.isEmpty {
                let a = alpha(for: gi, p) * rowAlpha
                if a <= 0.002 { continue }
                ctx.setFillColor(Ink.ink(a))
                var glyphs = [CGGlyph](repeating: glyphTable[gi], count: pts.count)
                CTFontDrawGlyphs(font, &glyphs, pts, pts.count, ctx)
            }
            for (gi, origin, s) in spotAlphas {
                let a = alpha(for: gi, p) * rowAlpha * s
                if a <= 0.002 { continue }
                ctx.setFillColor(Ink.ink(a))
                var glyph = glyphTable[gi]
                var o = origin
                CTFontDrawGlyphs(font, &glyph, &o, 1, ctx)
            }
        }
        ctx.restoreGState()
    }

    private func alpha(for glyphIndex: Int, _ p: FieldParams) -> Double {
        let level = glyphIndex <= 2 ? glyphIndex : (glyphIndex <= 5 ? AsciiFieldTerrain.currencyLevel : AsciiFieldTerrain.peakLevel)
        var a = p.ramp[level]
        if p.erosion > 0 { a *= AsciiFieldTerrain.erosionAlpha(level: level, progress: p.erosion) }
        return a
    }
}
