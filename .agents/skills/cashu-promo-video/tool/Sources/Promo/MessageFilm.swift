import CoreGraphics
import Field
import Foundation

/// "The message is the money." — 16.2 s, 27 beats. Two whole phones side by
/// side, still: A (left) copies a token and sends it as a Messages bubble;
/// B (right) pastes the same text into Cashu and claims it.
///   1 phone A 0 · 2 phone B 7 · 3 both + the line 16 · 4 end card 21 · end 27
final class MessageFilm: FilmBase {
    static let beatStarts: [Double] = [0, 7, 16, 21, 27]
    static func b(_ n: Int) -> Double { Grid.t(beatStarts[n - 1]) }
    static let duration = Grid.t(27)

    let t0 = 5.0
    func clock(_ ft: Double) -> Double { t0 + AsciiFieldTerrain.speed * ft }

    var clipsA: [Clip] = []
    var clipsB: [Clip] = []

    static let gap = 60.0
    static func space(_ phone: Int) -> AppSpace {
        let w = Phone.width * Stage.scale
        return Stage.phone(x: 540 + (phone == 0 ? -gap / 2 - w : gap / 2))
    }

    init(edl: EDL) throws {
        let b = Self.b
        // A: the copy's pill lands on a half-beat; cut away before the app
        // switch (it records as black), straight into Messages.
        let copy = try edl.p("share.copy"), sw = try edl.p("share.switch")
        let menu = try edl.p("share.menu"), paste = try edl.p("share.paste"), send = try edl.p("share.send")
        let copyAt = 0.6
        let a1 = Grid.snapDown(copyAt + (sw - copy) - 0.05)
        let a2 = a1 + 0.9, a3 = a2 + 0.9
        clipsA = [
            Clip(take: "share", takeIn: copy - copyAt, filmIn: 0, filmOut: a1),
            Clip(take: "share", takeIn: menu - 0.45, filmIn: a1, filmOut: a2),
            Clip(take: "share", takeIn: paste - 0.1, filmIn: a2, filmOut: a3),
            Clip(take: "share", takeIn: send - 0.1, filmIn: a3, filmOut: b(4)),
        ]

        // B: at rest on the Receive sheet until its beat, then a tap on the
        // sheet's own Paste button (maintainer's call, 2026-09-30). Paste is allowed and
        // the token was copied in Messages, so iOS says "Cashu pasted from
        // Messages". The button routes at once: the sheet starts to leave
        // ~0.4 s after the touch, so cut on its exit into the claim page,
        // past the empty home the page rises over. (Lead 0.8: p1 lands on
        // the exit's first frame in the 2026-09-30 take.)
        let tap = try edl.p("claim.pasteTap"), screen = try edl.p("claim.screen")
        let confirm = try edl.p("claim.confirm")
        let bStart = b(2)
        let lead = 0.8
        let p1 = bStart + 1.2, p2 = p1 + 1.5
        clipsB = [
            Clip(take: "claim", takeIn: tap - 1.5, filmIn: 0, filmOut: bStart, still: true),
            Clip(take: "claim", takeIn: tap - lead, filmIn: bStart, filmOut: p1),
            Clip(take: "claim", takeIn: screen - 0.05, filmIn: p1, filmOut: p2),
            Clip(take: "claim", takeIn: confirm - 0.1, filmIn: p2, filmOut: b(4)),
        ]

        super.init(baseEDL: edl)
        cuts = [a1, a2, a3, bStart, p1, p2, b(4)]
        cards = [
            Card(lines: ["Copy it."], filmIn: 0.1, filmOut: a1 - 0.3, rise: 0.4, exit: 0.2),
            Card(lines: ["Send it."], filmIn: a1 + 0.1, filmOut: b(2) - 0.3, rise: 0.4, exit: 0.2),
            Card(lines: ["Paste it. It's yours."], filmIn: b(2) + 0.1, filmOut: b(3) - 0.4),
            Card(lines: ["The message is the money."], filmIn: b(3) + 0.1, filmOut: b(4) - 0.4),
        ] + endCardCards(from: b(4) + 0.05, end: Self.duration)
    }

    func clip(_ clips: [Clip], at ft: Double) -> Clip? { clips.last { ft >= $0.filmIn && ft < $0.filmOut } }

    func render(frame: Int, on c: Canvas) throws {
        c.clear(Ink.canvas)
        let ft = Double(frame) / Double(Grid.fps)
        let endCard = ft >= Self.b(4)
        backdrop(t: clock(ft), space: Self.space(0), endCard: endCard, on: c)
        if !endCard {
            for (i, clips) in [clipsA, clipsB].enumerated() {
                guard let clip = clip(clips, at: ft) else { continue }
                PhoneLayer.draw(try plate(clip, at: ft), space: Self.space(i), on: c)
            }
        }
        drawCards(ft, on: c)
    }
}
