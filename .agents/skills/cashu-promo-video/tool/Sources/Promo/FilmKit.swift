import CoreGraphics
import Field
import Foundation

// Shared film machinery: the beat grid, the still stage, the edit decision
// list, phone clips, type cards, and the backdrop.

/// The 100 BPM grid: one beat is 0.6 s, 36 frames at 60 fps. Visible hard
/// cuts inside a beat land on half-beats (18 frames).
enum Grid {
    static let fps = 60
    static let beat = 0.6
    static func t(_ beats: Double) -> Double { beats * beat }
    static func snap(_ t: Double) -> Double { (t / (beat / 2)).rounded() * (beat / 2) }
    /// The last half-beat at or before `t` (a cut that must beat an event).
    static func snapDown(_ t: Double) -> Double { (t / (beat / 2) + 1e-9).rounded(.down) * (beat / 2) }
    static func onGrid(_ t: Double) -> Bool { abs(t / (beat / 2) - (t / (beat / 2)).rounded()) < 1e-6 }
}

/// Take directories and in-points (take seconds), authored per edit.
struct EDL: Codable {
    var takes: [String: String]
    var points: [String: Double]

    func p(_ key: String) throws -> Double {
        guard let v = points[key] else { throw PromoError("EDL is missing \(key)") }
        return v
    }
}

/// A span of a phone layer: film [filmIn, filmOut) plays `take` from `takeIn`.
struct Clip {
    let take: String
    let takeIn: Double
    let filmIn: Double
    let filmOut: Double
    /// Hold the frame at `takeIn` for the whole span.
    var still = false
}

struct Card {
    let lines: [String]
    let filmIn: Double
    let filmOut: Double
    var size: CGFloat = 76
    var top: CGFloat = 110
    var lineGap: CGFloat = 92
    var rise = 0.5
    var exit = 0.25
    var stagger = 0.45
    var weight = "SemiBold"
    var ink = 1.0
    /// Draw the store badges in place of the text; `lines` is what they say,
    /// so the hold rule still applies to them.
    var badges = false
}

/// The still stage every film stands on: whole phones at one size, their tops
/// under the caption line, nothing moving but the footage and the field.
enum Stage {
    static let scale = 0.945
    static let top = 1080 - 40 - Phone.height * scale
    static func phone(x: Double) -> AppSpace { AppSpace(origin: CGPoint(x: x, y: top), scale: scale) }
    static let centered = phone(x: 540 - Phone.width * scale / 2)
    /// Where the captions sit; the field goes quiet under it.
    static let captionBand = CGRect(x: 90, y: 50, width: 900, height: 130)
    static let endCardBlock = CGRect(x: 180, y: 380, width: 720, height: 600)
}

class FilmBase {
    let size = 1080
    let edl: EDL
    let painter = FieldPainter()
    var footage: [String: Footage] = [:]
    var cards: [Card] = []
    /// Visible hard cuts (film seconds).
    var cuts: [Double] = []

    init(baseEDL edl: EDL) {
        self.edl = edl
        for (name, dir) in edl.takes {
            footage[name] = Footage(Paths.root.appendingPathComponent(dir).appendingPathComponent("take-60.mp4"))
        }
    }

    var all: CGRect { CGRect(x: 0, y: 0, width: size, height: size) }

    func plate(_ clip: Clip, at ft: Double) throws -> CGImage {
        guard let f = footage[clip.take] else { throw PromoError("no take \(clip.take)") }
        return try f.frame(at: clip.still ? clip.takeIn : clip.takeIn + (ft - clip.filmIn))
    }

    /// The faint landscape behind the phones, in the phones' own cell grid so
    /// it runs on from any field the app shows. Quiet under the caption line,
    /// or under the end card's block once it's up.
    func backdrop(t: Double, space: AppSpace, endCard: Bool, on c: Canvas) {
        var f = FieldParams(t: t, space: space, region: all)
        f.alpha = 0.45
        f.spot = endCard
            ? Self.quiet(under: Stage.endCardBlock, space: space, floor: 0.1)
            : Self.quiet(under: Stage.captionBand, space: space)
        painter.draw(f, into: c.context)
    }

    /// A quiet pocket around the type: the field drops to the faint end of
    /// its ramp under the words (the background serves the subject).
    static func quiet(under rect: CGRect, space: AppSpace, floor: Double = 0.18) -> (Double, Double) -> Double {
        let r = rect.insetBy(dx: -60, dy: -40)
        return { x, y in
            let p = space.px(x, y)
            let dx = max(r.minX - p.x, 0, p.x - r.maxX), dy = max(r.minY - p.y, 0, p.y - r.maxY)
            let d = Double((dx * dx + dy * dy).squareRoot())
            let u = min(1, d / 90)
            return floor + (1 - floor) * u * u * (3 - 2 * u)
        }
    }

    // MARK: Type

    func drawCards(_ ft: Double, on c: Canvas) {
        for card in cards where ft >= card.filmIn - 0.01 && ft < card.filmOut + card.exit + 0.01 {
            if card.badges {
                let rise = progress(ft, from: card.filmIn, over: card.rise, Ease.out)
                let exit = progress(ft, from: card.filmOut, over: card.exit, Ease.inOut)
                Badges.draw(centerY: card.top, alpha: rise * (1 - exit),
                            rise: Badges.height * 0.22 * CGFloat(1 - rise), on: c)
                continue
            }
            for (i, line) in card.lines.enumerated() {
                let enter = card.filmIn + Double(i) * card.stagger
                let rise = progress(ft, from: enter, over: card.rise, Ease.out)
                let exit = progress(ft, from: card.filmOut, over: card.exit, Ease.inOut)
                let alpha = rise * (1 - exit)
                guard alpha > 0.002 else { continue }
                TypeLayer.draw(TypeLine(text: line, size: card.size, weight: card.weight,
                                        centerY: card.top + CGFloat(i) * card.lineGap,
                                        alpha: alpha, rise: CGFloat(Double(card.size) * 0.22 * (1 - rise)), ink: card.ink), on: c)
            }
        }
    }

    /// Pairs of lines whose motion windows overlap (must be empty).
    func motionOverlaps(until end: Double) -> [(String, String)] {
        var spans: [(String, Double, Double)] = []
        for card in cards {
            for (i, line) in card.lines.enumerated() {
                let enter = card.filmIn + Double(i) * card.stagger
                spans.append((line + " ↑", enter, enter + card.rise))
            }
            if card.filmOut < end {
                spans.append((card.lines.joined(separator: " / ") + " ↓", card.filmOut, card.filmOut + card.exit))
            }
        }
        var out: [(String, String)] = []
        for i in spans.indices {
            for j in spans.indices where j > i && spans[i].1 < spans[j].2 - 1e-6 && spans[j].1 < spans[i].2 - 1e-6 {
                out.append((spans[i].0, spans[j].0))
            }
        }
        return out
    }

    /// Cards shorter than words ÷ 3 + 0.5 s on screen, clamped at the film's end.
    func holdViolations(until end: Double) -> [(String, Double, Double)] {
        cards.compactMap { card in
            let words = card.lines.joined(separator: " ").split(separator: " ").count
            let need = Double(words) / 3 + 0.5
            let shown = min(end, card.filmOut + card.exit) - card.filmIn
            return shown + 1e-6 < need ? (card.lines.joined(separator: " "), shown, need) : nil
        }
    }

    // MARK: End card (shared by every film)

    func endCardCards(from t: Double, end: Double) -> [Card] {
        [
            Card(lines: ["Cashu.me"], filmIn: t, filmOut: end + 1, size: 150, top: 470, rise: 0.35),
            Card(lines: ["App Store.", "Google Play."], filmIn: t + 0.35, filmOut: end + 1,
                 top: 640, rise: 0.4, badges: true),
        ]
    }
}
