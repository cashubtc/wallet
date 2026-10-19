import AVFoundation
import CoreGraphics
import Foundation
import Vision

/// Automated checks for an export. Human checks (watching on a phone at 1×)
/// stay human; everything a machine can prove is proven here.
enum QA {
    struct Report {
        var lines: [String] = []
        var failures = 0
        mutating func pass(_ s: String) { lines.append("✓ " + s) }
        mutating func fail(_ s: String) { lines.append("✗ " + s); failures += 1 }
        mutating func note(_ s: String) { lines.append("· " + s) }
    }

    static func ffprobe(_ args: [String]) throws -> String {
        let p = Process()
        let pipe = Pipe()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        p.arguments = ["ffprobe", "-v", "error"] + args
        p.standardOutput = pipe
        try p.run()
        let d = pipe.fileHandleForReading.readDataToEndOfFile()
        p.waitUntilExit()
        return String(decoding: d, as: UTF8.self)
    }

    /// Words that must never be in frame: the demo mint's address. Its name,
    /// "Local test mint", is allowed since the films show whole phones
    /// (maintainer's call, 2026-09-30).
    static let forbidden = ["127.0.0.1", "localhost", "testnut"]

    static func run(_ url: URL, greenWindows: [(Double, Double)], expectSeconds: Double, allow: [String] = []) throws -> Report {
        var r = Report()
        // Streams and container.
        let audio = try ffprobe(["-select_streams", "a", "-show_entries", "stream=index", "-of", "csv=p=0", url.path])
            .trimmingCharacters(in: .whitespacesAndNewlines)
        audio.isEmpty ? r.pass("0 audio streams") : r.fail("audio stream present: \(audio)")
        let v = try ffprobe(["-select_streams", "v:0", "-show_entries", "stream=width,height,r_frame_rate,pix_fmt,codec_name,profile",
                             "-of", "default=nw=1", url.path])
        let fields = Dictionary(uniqueKeysWithValues: v.split(separator: "\n").compactMap { line -> (String, String)? in
            let kv = line.split(separator: "=", maxSplits: 1).map(String.init)
            return kv.count == 2 ? (kv[0], kv[1]) : nil
        })
        (fields["width"] == "1080" && fields["height"] == "1080") ? r.pass("1080×1080") : r.fail("size \(fields["width"] ?? "?")×\(fields["height"] ?? "?")")
        fields["r_frame_rate"] == "60/1" ? r.pass("60 fps") : r.fail("frame rate \(fields["r_frame_rate"] ?? "?")")
        r.note("codec \(fields["codec_name"] ?? "?") \(fields["profile"] ?? "") \(fields["pix_fmt"] ?? "")")
        let tags = try ffprobe(["-show_entries", "format_tags:stream_tags", "-of", "default=nw=1", url.path])
            .split(separator: "\n").filter { !$0.hasPrefix("[") && !$0.contains("handler_name") && !$0.contains("vendor_id")
                && !$0.contains("major_brand") && !$0.contains("minor_version") && !$0.contains("compatible_brands")
                && !$0.contains("language") && !$0.contains("encoder") }
        tags.isEmpty ? r.pass("no identifying metadata") : r.fail("metadata: \(tags.joined(separator: "; "))")
        let dur = Double(try ffprobe(["-show_entries", "format=duration", "-of", "csv=p=0", url.path])
            .trimmingCharacters(in: .whitespacesAndNewlines)) ?? 0
        abs(dur - expectSeconds) < 0.05 ? r.pass(String(format: "duration %.2fs", dur)) : r.fail(String(format: "duration %.2fs ≠ %.2fs", dur, expectSeconds))

        // Frame scans: confirmed green, and forbidden words (Vision OCR).
        let footage = Footage(url)
        var greenTimes: [Double] = []
        var hits: [(Double, String)] = []
        var frame = 0
        let total = Int(dur * 60)
        while frame < total {
            let t = Double(frame) / 60
            let img = try footage.frame(at: t)
            if frame % 3 == 0 && greenFraction(img) > 0.0004 { greenTimes.append(t) }
            if frame % 6 == 0 {
                for word in try recognize(img) {
                    let w = word.lowercased()
                    if forbidden.contains(where: { w.contains($0) && !allow.contains($0) }) { hits.append((t, word)) }
                }
            }
            frame += 1
        }
        let outside = greenTimes.filter { t in !greenWindows.contains { t >= $0.0 && t <= $0.1 } }
        if greenTimes.isEmpty { r.note("no confirmed green in frame") }
        outside.isEmpty ? r.pass("green only inside its allowed window(s)")
            : r.fail("green outside its window at \(outside.prefix(8).map { String(format: "%.2f", $0) }.joined(separator: ", "))s")
        if !allow.isEmpty { r.note("allowed in frame: \(allow.joined(separator: ", "))") }
        hits.isEmpty ? r.pass("no forbidden mint address readable in any sampled frame")
            : r.fail("mint address in frame: \(hits.prefix(6).map { String(format: "%.2fs %@", $0.0, $0.1) }.joined(separator: "; "))")
        return r
    }

    /// Fraction of pixels in the app's confirmed green (systemGreen, light
    /// or dark variant), on a downsampled frame.
    static func greenFraction(_ image: CGImage) -> Double {
        let w = 270, h = 270
        var px = [UInt8](repeating: 0, count: w * h * 4)
        let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        var hit = 0
        for i in stride(from: 0, to: px.count, by: 4) {
            let r = Int(px[i]), g = Int(px[i + 1]), b = Int(px[i + 2])
            if g > 140 && g - r > 70 && g - b > 40 { hit += 1 }
        }
        return Double(hit) / Double(w * h)
    }

    static func recognize(_ image: CGImage) throws -> [String] {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.usesLanguageCorrection = false
        try VNImageRequestHandler(cgImage: image).perform([request])
        return (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }
    }
}
