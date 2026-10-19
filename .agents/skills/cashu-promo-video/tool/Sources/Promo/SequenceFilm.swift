import CoreGraphics
import Field
import Foundation

/// A single-feature film: one whole phone, still, playing spans of one take
/// back to back with hard cuts on the half-beat grid; one caption at a time
/// above it; the faint landscape behind; then the end card.
///
/// A span is (take time in, length). Lengths are whole half-beats, so every
/// cut lands on the grid by construction.
class SequenceFilm: FilmBase {
    struct Span { let from: Double; let length: Double }
    struct Line { let text: String; let span: Int; var through: Int? = nil; var lead = 0.1 }

    let take: String
    let spans: [Span]
    /// Film time of each span's start, plus the end card's.
    let starts: [Double]
    let endCardAt: Double
    let duration: Double
    let t0 = 4.0
    let space = Stage.centered

    func clock(_ ft: Double) -> Double { t0 + AsciiFieldTerrain.speed * ft }

    /// `lines` caption spans: each enters `lead` after its first span starts
    /// and leaves 0.3 s before the span after `through` (default: its own).
    init(edl: EDL, take: String, spans: [Span], lines: [Line], endCardBeats: Double = 6) throws {
        for s in spans where !Grid.onGrid(s.length) { throw PromoError("span length \(s.length) is off the half-beat grid") }
        self.take = take
        self.spans = spans
        var t = 0.0, st: [Double] = []
        for s in spans { st.append(t); t += s.length }
        starts = st
        endCardAt = t
        duration = t + Grid.t(endCardBeats)
        super.init(baseEDL: edl)
        cuts = Array(st.dropFirst()) + [endCardAt]
        cards = lines.map { l in
            let end = (l.through ?? l.span) + 1 < st.count ? st[(l.through ?? l.span) + 1] : endCardAt
            return Card(lines: [l.text], filmIn: st[l.span] + l.lead, filmOut: end - 0.3)
        } + endCardCards(from: endCardAt + 0.05, end: duration)
    }

    func render(frame: Int, on c: Canvas) throws {
        c.clear(Ink.canvas)
        let ft = Double(frame) / Double(Grid.fps)
        let endCard = ft >= endCardAt
        backdrop(t: clock(ft), space: space, endCard: endCard, on: c)
        if !endCard, let i = starts.lastIndex(where: { ft >= $0 }) {
            let clip = Clip(take: take, takeIn: spans[i].from, filmIn: starts[i], filmOut: starts[i] + spans[i].length)
            PhoneLayer.draw(try plate(clip, at: ft), space: space, on: c)
        }
        drawCards(ft, on: c)
    }
}
