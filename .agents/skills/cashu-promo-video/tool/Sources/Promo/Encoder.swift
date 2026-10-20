import Foundation

/// Raw BGRA frames piped into ffmpeg. Two outputs from one pass: the H.264
/// master and the ProRes 422 HQ mezzanine. No audio stream exists in either
/// (`-an`), and all container/stream metadata is dropped (`-map_metadata -1`).
final class Encoder {
    private let process = Process()
    private let pipe = Pipe()

    init(width: Int, height: Int, fps: Int, h264: URL, prores: URL?) throws {
        try FileManager.default.createDirectory(at: h264.deletingLastPathComponent(), withIntermediateDirectories: true)
        var args = [
            "-v", "error", "-y",
            "-f", "rawvideo", "-pix_fmt", "bgra", "-s", "\(width)x\(height)", "-r", "\(fps)", "-i", "-",
            "-an", "-map_metadata", "-1", "-map_chapters", "-1",
            "-vf", "scale=out_color_matrix=bt709:out_range=tv",
            "-c:v", "libx264", "-profile:v", "high", "-pix_fmt", "yuv420p", "-crf", "14", "-preset", "slow",
            "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
            "-movflags", "+faststart", "-fflags", "+bitexact", "-flags:v", "+bitexact",
            h264.path,
        ]
        if let prores {
            args += [
                "-an", "-map_metadata", "-1", "-map_chapters", "-1",
                "-vf", "scale=out_color_matrix=bt709:out_range=tv",
                "-c:v", "prores_ks", "-profile:v", "3", "-pix_fmt", "yuv422p10le",
                "-color_primaries", "bt709", "-color_trc", "bt709", "-colorspace", "bt709",
                "-fflags", "+bitexact", "-flags:v", "+bitexact",
                prores.path,
            ]
        }
        // ffmpeg from PATH (Homebrew's, usually).
        process.executableURL = URL(fileURLWithPath: "/usr/bin/env")
        process.arguments = ["ffmpeg"] + args
        process.standardInput = pipe
        try process.run()
    }

    func write(_ canvas: Canvas) {
        let bytes = canvas.bytes
        pipe.fileHandleForWriting.write(Data(bytes: bytes.baseAddress!, count: bytes.count))
    }

    func finish() throws {
        try pipe.fileHandleForWriting.close()
        process.waitUntilExit()
        guard process.terminationStatus == 0 else { throw PromoError("ffmpeg exited \(process.terminationStatus)") }
    }
}
