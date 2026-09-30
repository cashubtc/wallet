import CoreGraphics
import Foundation

// promo — the Cashu.me promo films' renderer. Paths resolve against the
// package root (tool/), so run it from anywhere.
//
//   promo edl-seq <out.json> <name>=<take dir>
//       a single-take film's marks and tap answers on the video clock
//   promo render-seq <send|restore> <edl.json> <out.mp4> [light|dark] [prores.mov]
//       a single-take film from Films.swift
//   promo edl-message <out.json> share=<dir> claim=<dir>
//       the two-phone chat film's in-points, read from its takes' pixels
//   promo render-message <edl.json> <from s> <to s> <out.mp4> [light|dark] [prores.mov]
//       the two-phone chat film ("The message is the money.")
//   promo qa <export.mp4> <expect s> <send|restore|message> <edl.json>
//       automated checks on an export (see QA.swift)

let args = Array(CommandLine.arguments.dropFirst())

func run() throws {
    try Fonts.register()
    guard let command = args.first else {
        print("usage: promo <edl-seq|render-seq|edl-message|render-message|qa> …")
        return
    }
    switch command {
    case "edl-message":
        // promo edl-message <out.json> share=<dir> claim=<dir>
        var takes: [String: String] = [:]
        for a in args.dropFirst(2) {
            let kv = a.split(separator: "=", maxSplits: 1).map(String.init)
            takes[kv[0]] = kv[1]
        }
        func url(_ k: String) -> URL { URL(fileURLWithPath: takes[k]!, relativeTo: Paths.root) }
        var points: [String: Double] = [:]
        points.merge(try Analyze.share(url("share"))) { $1 }
        points.merge(try Analyze.claim(url("claim"))) { $1 }
        points.merge(try Analyze.claimConfirm(url("claim"), screen: points["claim.screen"]!)) { $1 }
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        try enc.encode(EDL(takes: takes, points: points)).write(to: URL(fileURLWithPath: args[1], relativeTo: Paths.root))
        for k in points.keys.sorted() { print(String(format: "%-20@ %8.3f", k as NSString, points[k]!)) }
    case "render-message":
        // promo render-message <edl.json> <from> <to> <out.mp4> [light|dark] [prores.mov]
        let edl = try JSONDecoder().decode(EDL.self, from: Data(contentsOf: URL(fileURLWithPath: args[1], relativeTo: Paths.root)))
        Ink.dark = args.count > 5 && args[5] == "dark"
        let film = try MessageFilm(edl: edl)
        for (a, b) in film.motionOverlaps(until: MessageFilm.duration) { print("⚠︎ motion overlap: \(a) × \(b)") }
        for (line, shown, need) in film.holdViolations(until: MessageFilm.duration) { print(String(format: "⚠︎ hold: %@ %.2fs < %.2fs", line, shown, need)) }
        let from = Int((Double(args[2])! * 60).rounded()), to = Int((Double(args[3])! * 60).rounded())
        let prores = args.count > 6 ? URL(fileURLWithPath: args[6], relativeTo: Paths.root) : nil
        let enc = try Encoder(width: 1080, height: 1080, fps: 60, h264: URL(fileURLWithPath: args[4], relativeTo: Paths.root), prores: prores)
        let canvas = Canvas(width: 1080, height: 1080)
        for f in from..<to {
            try film.render(frame: f, on: canvas)
            enc.write(canvas)
        }
        try enc.finish()
        print("rendered → \(args[4])")
    case "render-seq":
        // promo render-seq <send|restore> <edl.json> <out.mp4> [light|dark] [prores.mov]
        let edl = try JSONDecoder().decode(EDL.self, from: Data(contentsOf: URL(fileURLWithPath: args[2], relativeTo: Paths.root)))
        Ink.dark = args.count > 4 && args[4] == "dark"
        let film = try Films.named(args[1], edl)
        for (a, b) in film.motionOverlaps(until: film.duration) { print("⚠︎ motion overlap: \(a) × \(b)") }
        for (line, shown, need) in film.holdViolations(until: film.duration) { print(String(format: "⚠︎ hold: %@ %.2fs < %.2fs", line, shown, need)) }
        let prores = args.count > 5 ? URL(fileURLWithPath: args[5], relativeTo: Paths.root) : nil
        let enc = try Encoder(width: 1080, height: 1080, fps: 60, h264: URL(fileURLWithPath: args[3], relativeTo: Paths.root), prores: prores)
        let canvas = Canvas(width: 1080, height: 1080)
        for f in 0..<Int((film.duration * 60).rounded()) {
            try film.render(frame: f, on: canvas)
            enc.write(canvas)
        }
        try enc.finish()
        print(String(format: "rendered %@ %.1fs → %@", args[1], film.duration, args[3]))
    case "edl-seq":
        // promo edl-seq <out.json> <name>=<take dir> — a single-take film's marks
        // and tap answers on the video clock.
        let kv = args[2].split(separator: "=", maxSplits: 1).map(String.init)
        let points = try Analyze.sequence(URL(fileURLWithPath: kv[1], relativeTo: Paths.root))
        let enc = JSONEncoder()
        enc.outputFormatting = [.prettyPrinted, .sortedKeys]
        try enc.encode(EDL(takes: [kv[0]: kv[1]], points: points)).write(to: URL(fileURLWithPath: args[1], relativeTo: Paths.root))
        for (k, v) in points.sorted(by: { $0.value < $1.value }) { print(String(format: "%8.3f  %@", v, k)) }
    case "qa":
        // promo qa <export.mp4> <expect s> <send|restore|message> <edl.json>
        let url = URL(fileURLWithPath: args[1], relativeTo: Paths.root)
        let edl = try JSONDecoder().decode(EDL.self, from: Data(contentsOf: URL(fileURLWithPath: args[4], relativeTo: Paths.root)))
        var green: [(Double, Double)] = []
        // The restore film types the mint's address in (a deliberate exception).
        let allow = args[3] == "restore" ? ["127.0.0.1"] : []
        var cuts: [Double] = []
        var holds: [(String, Double, Double)] = []
        var overlaps: [(String, String)] = []
        switch args[3] {
        case "send", "restore":
            let f = try Films.named(args[3], edl)
            // Green is the app's own here: the funded home's "+5,000" row, the
            // backup toggle, "Recovered", the restore check. Allowed throughout.
            green = [(0, f.duration)]
            cuts = f.cuts; holds = f.holdViolations(until: f.duration); overlaps = f.motionOverlaps(until: f.duration)
        case "message":
            let f = try MessageFilm(edl: edl)
            green = [(MessageFilm.b(2), MessageFilm.b(4) + 0.1)]
            cuts = f.cuts; holds = f.holdViolations(until: MessageFilm.duration); overlaps = f.motionOverlaps(until: MessageFilm.duration)
        default:
            throw PromoError("qa: unknown film \(args[3]) (send|restore|message)")
        }
        var report = try QA.run(url, greenWindows: green, expectSeconds: Double(args[2])!, allow: allow)
        let off = cuts.filter { !Grid.onGrid($0) }
        off.isEmpty ? report.pass("cuts on the 100 BPM grid: " + cuts.map { "f\(Int(($0 * 60).rounded()))" }.joined(separator: " "))
            : report.fail("cuts off grid: \(off)")
        holds.isEmpty ? report.pass("every card holds ≥ words ÷ 3 + 0.5 s") : report.fail("hold: \(holds)")
        overlaps.isEmpty ? report.pass("never more than one line in motion") : report.fail("motion overlap: \(overlaps)")
        for l in report.lines { print(l) }
        if report.failures > 0 { throw PromoError("\(report.failures) QA failure(s)") }
    default:
        throw PromoError("unknown command \(command)")
    }
}

do { try run() } catch {
    FileHandle.standardError.write("error: \(error)\n".data(using: .utf8)!)
    exit(1)
}
