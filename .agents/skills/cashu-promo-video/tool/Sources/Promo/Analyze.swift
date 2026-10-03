import CoreGraphics
import Field
import Foundation

/// Reads a take's events out of its pixels, so in-points are frame-exact
/// rather than trusting the driver's wall-clock marks.
enum Analyze {
    static func ensureCFR(_ dir: URL) throws -> URL {
        darkTake = dir.path.contains("-dark/")
        let out = dir.appendingPathComponent("take-60.mp4")
        if FileManager.default.fileExists(atPath: out.path) { return out }
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        p.arguments = ["ffmpeg", "-v", "error", "-y", "-i", dir.appendingPathComponent("take.mp4").path,
                       "-vf", "fps=60", "-an", "-c:v", "libx264", "-crf", "8", "-preset", "fast", out.path]
        try p.run()
        p.waitUntilExit()
        guard p.terminationStatus == 0 else { throw PromoError("ffmpeg CFR failed for \(dir.lastPathComponent)") }
        return out
    }

    /// Dark-mode takes: "ink" is light on black, so `darkness` measures
    /// brightness instead. Set from the take directory's name.
    nonisolated(unsafe) static var darkTake = false

    /// Mean ink of an app-point rect in a capture-scale frame: darkness on a
    /// light take, brightness on a dark one.
    static func darkness(_ image: CGImage, _ rect: CGRect) -> Double {
        let d = rawDarkness(image, rect)
        return darkTake ? 1 - d : d
    }

    static func rawDarkness(_ image: CGImage, _ rect: CGRect) -> Double {
        let g = gray(image)
        let s = Double(image.width) / Phone.width
        var sum = 0.0, n = 0.0
        for y in stride(from: Int(rect.minY * s), to: Int(rect.maxY * s), by: 2) {
            for x in stride(from: Int(rect.minX * s), to: Int(rect.maxX * s), by: 2) {
                sum += 1 - Double(g[y * image.width + x]) / 255
                n += 1
            }
        }
        return sum / n
    }

    /// 8-bit grey copy of a frame (device grey, row-major).
    static func gray(_ image: CGImage) -> [UInt8] {
        let w = image.width, h = image.height
        var out = [UInt8](repeating: 0, count: w * h)
        let ctx = CGContext(
            data: &out, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w,
            space: CGColorSpaceCreateDeviceGray(), bitmapInfo: CGImageAlphaInfo.none.rawValue
        )!
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        return out
    }
}

extension Analyze {
    /// Marks as an ordered list (labels may repeat, e.g. keypad digits), on
    /// the *video's* clock. The test runner's wall clock and the recording
    /// disagree by seconds, so each take is calibrated from its pixels: the
    /// first big change on the static springboard is the app launching,
    /// ~0.3 s after the runner's "launch" mark.
    static func markList(_ dir: URL) throws -> [(String, Double)] {
        let start = Double(try String(contentsOf: dir.appendingPathComponent("take-start.txt"), encoding: .utf8)
            .trimmingCharacters(in: .whitespacesAndNewlines))!
        let files = try FileManager.default.contentsOfDirectory(atPath: dir.path).filter { $0.hasSuffix("marks.json") }
        guard let file = files.first else { throw PromoError("no marks in \(dir.lastPathComponent)") }
        let list = try JSONSerialization.jsonObject(with: Data(contentsOf: dir.appendingPathComponent(file))) as! [[String: Any]]
        let raw = list.map { ($0["label"] as! String, ($0["time"] as! Double) - start) }
        let offset = try launchOffset(dir, launchMark: raw.first { $0.0 == "launch" }?.1, marks: raw)
        return raw.map { ($0.0, $0.1 + offset) }
    }

    /// Video time minus mark time, from the first tap whose response is
    /// unambiguous on screen: Create Wallet swaps the welcome title for the
    /// seed step's; the wallet's Send raises its sheet. (The runner's first
    /// visible event is its own launch, so "launch" can't calibrate.)
    static func launchOffset(_ dir: URL, launchMark: Double?, marks raw: [(String, Double)] = []) throws -> Double {
        let cache = dir.appendingPathComponent("offset.txt")
        if let s = try? String(contentsOf: cache, encoding: .utf8), let v = Double(s) { return v }
        _ = try ensureCFR(dir)
        let f = Footage(dir.appendingPathComponent("take-60.mp4"))
        let probes: [(String, CGRect)] = [
            ("tap:create", CGRect(x: 28, y: 118, width: 300, height: 60)),
            ("tap:send", CGRect(x: 20, y: 400, width: 362, height: 400)),
            // Settings pushes over the whole wallet.
            ("tap:settings", CGRect(x: 20, y: 150, width: 362, height: 500)),
        ]
        for (label, rect) in probes {
            guard let mark = raw.first(where: { $0.0 == label })?.1 else { continue }
            // Search a wide window around the mark against a frame well before it.
            let from = max(0.5, mark - 4.5)
            let ref = try f.frame(at: from)
            var t = from
            while t < mark + 5 {
                t += 1.0 / 60
                if diff(ref, try f.frame(at: t), rect) > 0.03 {
                    let offset = t - (mark + 0.3)
                    try String(offset).write(to: cache, atomically: true, encoding: .utf8)
                    return offset
                }
            }
        }
        return 0
    }

    /// Mean absolute luminance difference of an app-point rect between frames.
    static func diff(_ a: CGImage, _ b: CGImage, _ rect: CGRect) -> Double {
        let ga = gray(a), gb = gray(b)
        let s = Double(a.width) / Phone.width
        var sum = 0.0, n = 0.0
        for y in stride(from: Int(rect.minY * s), to: Int(rect.maxY * s), by: 2) {
            for x in stride(from: Int(rect.minX * s), to: Int(rect.maxX * s), by: 2) {
                sum += abs(Double(ga[y * a.width + x]) - Double(gb[y * a.width + x])) / 255
                n += 1
            }
        }
        return sum / n
    }

    /// First take time after `t` where `rect` visibly changes from its look at `t`.
    static func firstChange(_ f: Footage, after t: Double, in rect: CGRect, threshold: Double = 0.01, within: Double = 6) throws -> Double {
        let ref = try f.frame(at: t)
        var u = t
        while u < t + within {
            u += 1.0 / 60
            if diff(ref, try f.frame(at: u), rect) > threshold { return u }
        }
        throw PromoError("no change after \(t)s in \(rect)")
    }

    /// The claim take: (the copy in Messages, unused), the Receive sheet at
    /// rest, the tap on its Paste button, the claim screen.
    static func claim(_ dir: URL) throws -> [String: Double] {
        _ = try ensureCFR(dir)
        let f = Footage(dir.appendingPathComponent("take-60.mp4"))
        let marks = try markList(dir)
        func first(_ label: String) throws -> Double {
            guard let m = marks.first(where: { $0.0 == label }) else { throw PromoError("no mark \(label)") }
            return m.1
        }
        // Pixels, not marks. This take's launch offset is unreliable: the app
        // launches from Messages about 4 s before Create, inside the offset
        // probe's window, so it calibrates about 4 s early. Find the Receive
        // sheet rising after its tap with a wide window, and look for the paste
        // only once the sheet has settled. There's no keyboard, so the field
        // stays where the sheet rests. The touch lights its glass (~+7
        // levels); the token lands ~0.25 s later (~+22); the sheet leaves
        // ~0.17 s after that.
        let sheetUp = try firstChange(f, after: try first("tap:receive") - 1.0, in: CGRect(x: 0, y: 540, width: 402, height: 330), threshold: 0.08, within: 14) + 0.6
        let field = CGRect(x: 24, y: 553, width: 354, height: 52)
        let pasteTap = try firstChange(f, after: sheetUp, in: field, threshold: 0.02, within: 12)
        let pasted = try firstChange(f, after: pasteTap + 0.1, in: field, threshold: 0.05, within: 3)
        // The claim page is up when its big amount is.
        var screen = pasted
        while screen < pasted + 8 {
            screen += 1.0 / 60
            // White page (not the sheet's dimmed backdrop) carrying the amount.
            let fr = try f.frame(at: screen)
            if darkness(fr, CGRect(x: 0, y: 250, width: 402, height: 70)) < 0.05
                && darkness(fr, CGRect(x: 90, y: 355, width: 200, height: 50)) > 0.12 { break }
        }
        return ["claim.pasteTap": pasteTap, "claim.pasted": pasted, "claim.screen": screen]
    }


    /// Phone A of the chat film: Copy → Messages → paste → send.
    static func share(_ dir: URL) throws -> [String: Double] {
        _ = try ensureCFR(dir)
        let f = Footage(dir.appendingPathComponent("take-60.mp4"))
        let marks = try markList(dir)
        func first(_ label: String) throws -> Double {
            guard let m = marks.first(where: { $0.0 == label }) else { throw PromoError("no mark \(label)") }
            return m.1
        }
        // The title strip: "Pending Ecash" becomes the "Copied ecash token"
        // pill on the copy, and stays still until the app switch sweeps it
        // away. (The token QR below animates, so the body can't be watched.)
        let title = CGRect(x: 100, y: 84, width: 202, height: 28)
        let copy = try firstChange(f, after: try first("tap:copy-token") - 0.6, in: title, threshold: 0.05, within: 3)
        return [
            "share.copy": copy,
            "share.switch": try firstChange(f, after: copy + 0.5, in: title, threshold: 0.03, within: 6),
            // After the edit menu: the composer swells with the pasted token,
            // then the send collapses it into a bubble.
            // The edit menu pill, just above the composer (keyboard up).
            "share.menu": try firstChange(f, after: try first("press-composer") + 0.3, in: CGRect(x: 0, y: 440, width: 180, height: 36), threshold: 0.02, within: 12),
            "share.paste": try pasteAndSend(f, menu: try first("tap:paste") - 0.6).paste,
            "share.send": try pasteAndSend(f, menu: try first("tap:paste") - 0.6).send,
        ]
    }

    /// Every mark of a take on the video clock, plus — for each tap — when the
    /// screen actually answered ("<label>.r"): the first frame after the mark
    /// that differs from the mark's own frame. Marks are XCTest's intent and
    /// can trail or lead the real touch; the answer is what a cut should hit.
    static func sequence(_ dir: URL) throws -> [String: Double] {
        _ = try ensureCFR(dir)
        let f = Footage(dir.appendingPathComponent("take-60.mp4"))
        let body = CGRect(x: 0, y: 60, width: 402, height: 780)
        var points: [String: Double] = [:]
        var seen: [String: Int] = [:]
        for (label, t) in try markList(dir) where !label.hasPrefix("still:") {
            // Repeated labels (none today) get a counter so none is lost.
            let n = seen[label, default: 0]; seen[label] = n + 1
            let key = n == 0 ? label : "\(label)#\(n)"
            points[key] = t
            if label.hasPrefix("tap:") {
                points[key + ".r"] = (try? firstChange(f, after: t, in: body, threshold: 0.006, within: 6)) ?? t
            }
        }
        return points
    }

    static func pasteAndSend(_ f: Footage, menu: Double) throws -> (paste: Double, send: Double) {
        let body = CGRect(x: 0, y: 150, width: 402, height: 640)
        let paste = try firstChange(f, after: menu + 0.5, in: body, threshold: 0.05, within: 12)
        let send = try firstChange(f, after: paste + 0.6, in: body, threshold: 0.05, within: 12)
        return (paste, send)
    }

    /// The claim's Receive tap (chat film): the button's press, once the
    /// claim page is up.
    static func claimConfirm(_ dir: URL, screen: Double) throws -> [String: Double] {
        let f = Footage(dir.appendingPathComponent("take-60.mp4"))
        return ["claim.confirm": try firstChange(f, after: screen + 0.3, in: CGRect(x: 40, y: 680, width: 322, height: 90),
                                                 threshold: 0.01, within: 12)]
    }
}
