import CoreGraphics
import Field
import Foundation
import ImageIO
import UniformTypeIdentifiers

/// A y-down BGRA bitmap in sRGB — the one surface every layer draws into.
/// y-down matches the app's coordinate space (points from the window top), so
/// footage geometry and field geometry share one convention.
final class Canvas {
    let width: Int
    let height: Int
    let context: CGContext
    let bytesPerRow: Int
    private let buffer: UnsafeMutableRawPointer

    init(width: Int, height: Int) {
        self.width = width
        self.height = height
        bytesPerRow = width * 4
        buffer = UnsafeMutableRawPointer.allocate(byteCount: bytesPerRow * height, alignment: 64)
        let info = CGImageAlphaInfo.premultipliedFirst.rawValue | CGBitmapInfo.byteOrder32Little.rawValue
        context = CGContext(
            data: buffer, width: width, height: height, bitsPerComponent: 8,
            bytesPerRow: bytesPerRow, space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: info
        )!
        context.translateBy(x: 0, y: CGFloat(height))
        context.scaleBy(x: 1, y: -1)
        context.interpolationQuality = .high
        context.setShouldAntialias(true)
        context.setShouldSmoothFonts(false)
    }

    deinit { buffer.deallocate() }

    func clear(_ color: CGColor) {
        context.saveGState()
        context.setFillColor(color)
        context.fill(CGRect(x: 0, y: 0, width: width, height: height))
        context.restoreGState()
    }

    /// Raw BGRA bytes, top row first — what ffmpeg's `-pix_fmt bgra` expects.
    var bytes: UnsafeRawBufferPointer {
        UnsafeRawBufferPointer(start: buffer, count: bytesPerRow * height)
    }

    func makeImage() -> CGImage { context.makeImage()! }

    func writePNG(to url: URL) throws {
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        guard let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.png.identifier as CFString, 1, nil) else {
            throw PromoError("can't write \(url.path)")
        }
        CGImageDestinationAddImage(dest, makeImage(), nil)
        guard CGImageDestinationFinalize(dest) else { throw PromoError("can't finalize \(url.path)") }
    }

    /// Draws a CGImage into `rect` (y-down), un-flipping so it lands upright.
    func draw(_ image: CGImage, in rect: CGRect, alpha: CGFloat = 1) {
        context.saveGState()
        context.setAlpha(alpha)
        context.translateBy(x: rect.minX, y: rect.maxY)
        context.scaleBy(x: 1, y: -1)
        context.draw(image, in: CGRect(origin: .zero, size: rect.size))
        context.restoreGState()
    }
}

struct PromoError: Error, CustomStringConvertible {
    let description: String
    init(_ description: String) { self.description = description }
}

func loadImage(_ url: URL) throws -> CGImage {
    guard let src = CGImageSourceCreateWithURL(url as CFURL, nil),
          let image = CGImageSourceCreateImageAtIndex(src, 0, nil) else {
        throw PromoError("can't read \(url.path)")
    }
    return image
}

/// The film's three colors: canvas, ink, and (in footage only) the app's
/// confirmed green. Dark mode inverts canvas and ink under the same rule.
enum Ink {
    static var dark = false
    static var canvas: CGColor { dark ? CGColor(srgbRed: 0, green: 0, blue: 0, alpha: 1) : CGColor(srgbRed: 1, green: 1, blue: 1, alpha: 1) }
    static var ink: CGColor { ink(1) }
    static func ink(_ alpha: Double) -> CGColor {
        let v: CGFloat = dark ? 1 : 0
        return CGColor(srgbRed: v, green: v, blue: v, alpha: alpha)
    }
    /// The field's per-level opacity ramp for the current scheme.
    static var ramp: [Double] { dark ? AsciiFieldInk.dark : AsciiFieldInk.light }
}
