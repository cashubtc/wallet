import AVFoundation
import CoreGraphics
import CoreVideo
import Foundation

/// A take, re-encoded to constant 60 fps (`ffmpeg -vf fps=60`, frames held —
/// the recorder's PTS are wall-accurate, so frame i is take time i/60).
/// Frames are read forward with AVAssetReader; seeking backwards restarts it.
final class Footage {
    let url: URL
    private let asset: AVURLAsset
    private var reader: AVAssetReader?
    private var output: AVAssetReaderTrackOutput?
    private var current: (index: Int, image: CGImage)?
    private var pending: (index: Int, image: CGImage)?
    static let fps = 60.0

    init(_ url: URL) {
        self.url = url
        asset = AVURLAsset(url: url)
    }

    private func start(at index: Int) throws {
        let track = asset.tracks(withMediaType: .video).first!
        let r = try AVAssetReader(asset: asset)
        let start = CMTime(value: CMTimeValue(max(0, index - 1)), timescale: 60)
        r.timeRange = CMTimeRange(start: start, duration: .positiveInfinity)
        let out = AVAssetReaderTrackOutput(track: track, outputSettings: [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA,
        ])
        out.alwaysCopiesSampleData = false
        r.add(out)
        guard r.startReading() else { throw PromoError("can't read \(url.lastPathComponent): \(String(describing: r.error))") }
        reader = r
        output = out
        current = nil
        pending = nil
    }

    private func next() -> (Int, CGImage)? {
        guard let sample = output?.copyNextSampleBuffer(), let buffer = CMSampleBufferGetImageBuffer(sample) else { return nil }
        let pts = CMSampleBufferGetPresentationTimeStamp(sample).seconds
        return (Int((pts * Self.fps).rounded()), Self.image(from: buffer))
    }

    /// Last frame index in the file. The recorder writes nothing while the
    /// screen is static, so a take can end (in frames) before its last hold.
    private lazy var lastIndex: Int = {
        let d = asset.duration.seconds
        return max(0, Int((d * Self.fps).rounded(.down)) - 1)
    }()

    /// The frame shown at take time `t` (seconds). Past the last frame the
    /// screen simply held still, so the last frame is returned.
    func frame(at t: Double) throws -> CGImage {
        let index = min(lastIndex, max(0, Int((t * Self.fps).rounded())))
        if let c = current, c.index == index { return c.image }
        if reader == nil || current.map({ index < $0.index }) ?? false || index - (current?.index ?? 0) > 240 {
            try start(at: index)
        }
        while true {
            if let p = pending {
                if p.index > index, let c = current { return c.image }
                current = p
                pending = nil
                if p.index >= index { return p.image }
            }
            guard let n = next() else {
                if let c = current { return c.image }
                throw PromoError("\(url.lastPathComponent) ended before \(t)s")
            }
            pending = n
        }
    }

    static func image(from buffer: CVPixelBuffer) -> CGImage {
        CVPixelBufferLockBaseAddress(buffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(buffer, .readOnly) }
        let w = CVPixelBufferGetWidth(buffer), h = CVPixelBufferGetHeight(buffer)
        let bpr = CVPixelBufferGetBytesPerRow(buffer)
        let data = Data(bytes: CVPixelBufferGetBaseAddress(buffer)!, count: bpr * h)
        let provider = CGDataProvider(data: data as CFData)!
        let info = CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue)
        return CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: bpr,
                       space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: info,
                       provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)!
    }
}
