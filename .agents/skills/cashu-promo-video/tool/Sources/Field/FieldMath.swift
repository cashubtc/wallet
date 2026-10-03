import Foundation

// The ASCII field's pure math, copied verbatim from the Cashu iOS app:
//   cashu.me-native @ b49e88df — ios/CashuWallet/Views/Components/AsciiField.swift
//   AsciiFieldTerrain (:24-151), AsciiFieldVault (:175-286), AsciiFieldWarp (:297-379)
//
// Only two things changed: `public` access, and `fontSize` dropped (the film
// sizes glyphs itself). Every coefficient, operator order and rounding mode is
// the app's. The film's field has to *be* the app's field — beat 2 frames the
// phone as a window into this same landscape — so if a parity test in
// FieldTests fails, this copy has drifted: re-copy it, never edit the vectors.

// MARK: - Terrain

public enum AsciiFieldTerrain {
    public static let cellW: Double = 12
    public static let cellH: Double = 14
    public static let terrainScale: Double = 0.13
    public static let contourSpacing: Double = 0.08
    /// The app's pace. The film may run up to the web hero's 0.9 on
    /// full-frame shots, never faster.
    public static let speed: Double = 0.45

    public static let levelMin: [Int] = [40, 90, 140, 200, 216]
    public static let levelGlyph: [String] = ["·", "/", ","]
    public static let currencyGlyphs: [String] = ["$", "¥", "€"]
    public static let currencyLevel = 3
    public static let peakLevel = 4

    public static func noise(_ x: Double, _ y: Double, _ t: Double) -> Double {
        sin(0.8 * x + 0.3 * t) * cos(0.6 * y + 0.2 * t) * 0.5
            + 0.25 * sin(1.6 * x + 1.2 * y + 0.15 * t)
            + sin(0.3 * x - 0.4 * t) * cos(0.4 * y + 0.25 * t) * 0.6
            + 0.3 * sin(0.5 * (x + y) + 0.35 * t)
            + sin(2.5 * x + 0.1 * t) * cos(2.8 * y - 0.12 * t) * 0.15
    }

    public static func fractal(_ x: Double, _ y: Double, _ t: Double) -> Double {
        noise(x, y, t)
            + 0.4 * noise(2.2 * x, 2.2 * y, 0.7 * t)
            + 0.15 * noise(4.5 * x, 4.5 * y, 0.4 * t)
    }

    public static func brightness(_ x: Double, _ y: Double, _ t: Double) -> Int {
        let r = min(1, max(0, (fractal(x, y, t) + 1.8) / 3.6))
        let s = r.truncatingRemainder(dividingBy: contourSpacing) / contourSpacing
        let onContour = s < 0.12 || s > 0.88
        var b = onContour ? Int((200 * r + 55).rounded()) : Int((140 * r).rounded())
        if onContour {
            let gx = noise(x + 0.01, y, t) - noise(x - 0.01, y, t)
            let gy = noise(x, y + 0.01, t) - noise(x, y - 0.01, t)
            let d = 12 * (gx * gx + gy * gy).squareRoot()
            if d > 0.5 { b = min(255, b + Int((40 * d).rounded())) }
        }
        return b
    }

    public static func pickLevel(_ b: Int) -> Int {
        for i in stride(from: levelMin.count - 1, through: 0, by: -1) where b >= levelMin[i] {
            return i
        }
        return -1
    }

    public static let peakBoostMin = 208

    public static func displayLevel(_ b: Int) -> Int {
        b >= peakBoostMin ? peakLevel : pickLevel(b)
    }

    // MARK: Erosion

    public static let erosionStagger: Double = 0.13
    public static let erosionWindow: Double = 0.48

    public static func erosionAlpha(level: Int, progress e: Double) -> Double {
        let u = min(1, max(0, (e - Double(level) * erosionStagger) / erosionWindow))
        return 1 - u * u * (3 - 2 * u)
    }

    public static func currencyGlyphIndex(px: Double, py: Double) -> Int {
        let col = Int32(truncatingIfNeeded: Int((px / cellW).rounded(.down)))
        let row = Int32(truncatingIfNeeded: Int((py / cellH).rounded(.down)))
        let hash = (col &* 31) ^ (row &* 17)
        let shifted = Int32(bitPattern: UInt32(bitPattern: hash) >> 13)
        let mixed = (hash ^ shifted) &* 1274126177
        return Int(UInt32(bitPattern: mixed) % 3)
    }

    public static func pickLevel(_ b: Double) -> Int {
        for i in stride(from: levelMin.count - 1, through: 0, by: -1) where b >= Double(levelMin[i]) {
            return i
        }
        return -1
    }

    public static func displayLevel(_ b: Double) -> Int {
        b >= Double(peakBoostMin) ? peakLevel : pickLevel(b)
    }
}

// MARK: - Vault

public enum AsciiFieldVault {
    public static let outerRadius: Double = 146
    public static let outerWidth: Double = 11
    public static let outerBrightness: Double = 196
    public static let innerRadius: Double = 92
    public static let innerWidth: Double = 9
    public static let innerBrightness: Double = 168
    public static let faceRadius: Double = 152
    public static let faceBrightness: Double = 52
    public static let spokeMinDistance: Double = 24
    public static let spokeMaxDistance: Double = 96
    public static let spokeBrightness: Double = 176
    public static let spokeArcWidth: Double = 8
    public static let boltRadius: Double = 121
    public static let boltHalfWidth: Double = 8
    public static let boltBrightness: Double = 212
    public static let stencilPeakBrightness: Double = 221
    public static let stencilCurrencyBrightness: Double = 202
    public static let liveGain: Double = 0.28
    public static let livePivot: Double = 128
    public static let extentRadius: Double = outerRadius + outerWidth

    public static let stencilCols = 9
    public static let stencilRows = 11
    private static let stencilArt: [String] = [
        "....2....",
        ".222222..",
        ".2....22.",
        ".2.....2.",
        ".2....22.",
        ".222222..",
        ".2....22.",
        ".2.....2.",
        ".2....22.",
        ".222222..",
        "....2....",
    ]
    private static let stencilBoost: [[Double]] = stencilArt.map { row in
        row.map { c in
            c == "2" ? stencilPeakBrightness : (c == "1" ? stencilCurrencyBrightness : 0)
        }
    }

    private static func ringProfile(_ d: Double, _ radius: Double, _ width: Double) -> Double {
        max(0, 1 - abs(d - radius) / width)
    }

    public static func brightness(px: Double, py: Double, centerX: Double, centerY: Double, t: Double) -> Double {
        let dx = px - centerX
        let dy = py - centerY
        let d = (dx * dx + dy * dy).squareRoot()
        var b = 0.0
        if d < faceRadius { b = faceBrightness }
        b = max(b, outerBrightness * ringProfile(d, outerRadius, outerWidth))
        b = max(b, innerBrightness * ringProfile(d, innerRadius, innerWidth))
        let ang = atan2(dy, dx)
        if d > spokeMinDistance && d < spokeMaxDistance {
            let a = (ang + .pi).truncatingRemainder(dividingBy: .pi / 3)
            let arc = min(a, .pi / 3 - a) * d
            b = max(b, spokeBrightness * max(0, 1 - arc / spokeArcWidth))
        }
        let a12 = (ang + .pi).truncatingRemainder(dividingBy: .pi / 6)
        let boltD = ((d - boltRadius) * (d - boltRadius)
            + (min(a12, .pi / 6 - a12) * boltRadius) * (min(a12, .pi / 6 - a12) * boltRadius)).squareRoot()
        if boltD < boltHalfWidth { b = max(b, boltBrightness) }
        let col = Int((dx / AsciiFieldTerrain.cellW + 0.5).rounded(.down)) + stencilCols / 2
        let row = Int((dy / AsciiFieldTerrain.cellH + 0.5).rounded(.down)) + stencilRows / 2
        if row >= 0, row < stencilRows, col >= 0, col < stencilCols {
            b = max(b, stencilBoost[row][col])
        }
        let tb = Double(AsciiFieldTerrain.brightness(
            px / AsciiFieldTerrain.cellW * AsciiFieldTerrain.terrainScale,
            py / AsciiFieldTerrain.cellH * AsciiFieldTerrain.terrainScale,
            t
        ))
        return b + liveGain * (tb - livePivot)
    }
}

// MARK: - Touch warp

public enum AsciiFieldWarp {
    public static let radius: Double = 120
    public static let radiusBloomFloor: Double = 0.75
    public static let maxDisplacement: Double = 36
    public static let pressDuration: Double = 0.28
    public static let releaseDuration: Double = 0.6
    public static let backOvershoot: Double = 1.2
    public static let swirlMax: Double = 0.35
    public static let followTau: Double = 0.07

    public static func bloomedRadius(_ k: Double) -> Double {
        radius * (radiusBloomFloor + (1 - radiusBloomFloor) * min(1, k))
    }

    public static func displacement(_ d: Double, _ k: Double) -> Double {
        guard k > 0, d > 0 else { return 0 }
        let r = bloomedRadius(k)
        guard d < r else { return 0 }
        let s = d / r
        let e = s * (1 - s)
        return maxDisplacement * k * 16 * e * e
    }

    private static func backOut(_ u: Double) -> Double {
        let c = min(1, max(0, u))
        let q = c - 1
        return max(0, 1 + (backOvershoot + 1) * q * q * q + backOvershoot * q * q)
    }

    public static func pressEnvelope(elapsed: Double, from k0: Double) -> Double {
        k0 + (1 - k0) * backOut(elapsed / pressDuration)
    }

    public static func releaseEnvelope(elapsed: Double, from k0: Double) -> Double {
        let v = 1 - min(1, max(0, elapsed / releaseDuration))
        return k0 * v * v * v
    }

    public static func swirlAngle(_ f: Double) -> Double {
        swirlMax * f / maxDisplacement
    }

    public static func followFactor(_ dt: Double) -> Double {
        1 - exp(-dt / followTau)
    }
}

// MARK: - Glyph ink

/// The light-scheme ramp: per-level opacity of ink on white
/// (AsciiField.swift:1037-1040). Tones are ink at reduced opacity, never new
/// colors.
public enum AsciiFieldInk {
    public static let light: [Double] = [0.17, 0.37, 0.56, 0.68, 0.75]
    public static let dark: [Double] = [0.25, 0.32, 0.44, 0.63, 0.83]
}
