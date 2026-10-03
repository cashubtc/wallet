import CoreGraphics
import CoreText
import Foundation

/// The phone: a flat rounded-rect screen mask with a hairline ink outline.
/// No bezel, no shadow, no render — the screen *is* the device.
enum PhoneLayer {
    static func draw(_ plate: CGImage, space: AppSpace, on canvas: Canvas, alpha: Double = 1, outline: Double = 1) {
        let rect = space.screen
        let radius = CGFloat(Phone.cornerRadius * space.scale)
        let path = CGPath(roundedRect: rect, cornerWidth: radius, cornerHeight: radius, transform: nil)
        let ctx = canvas.context
        ctx.saveGState()
        ctx.addPath(path)
        ctx.clip()
        canvas.draw(plate, in: rect, alpha: CGFloat(alpha))
        ctx.restoreGState()
        if outline > 0 {
            ctx.saveGState()
            ctx.addPath(path)
            ctx.setStrokeColor(Ink.ink(0.9 * outline * alpha))
            ctx.setLineWidth(1.5)
            ctx.strokePath()
            ctx.restoreGState()
        }
    }
}

/// One line of the film's own type. Sentence case, ends with a period, set
/// in Inter Display — the app's own headlines stay in the footage.
struct TypeLine {
    var text: String
    var size: CGFloat = 76
    var weight: String = "SemiBold"
    var centerY: CGFloat
    var alpha: Double = 1
    var rise: CGFloat = 0
    var tracking: CGFloat = -0.01
    var ink: Double = 1
}

enum TypeLayer {
    static func draw(_ line: TypeLine, on canvas: Canvas) {
        guard line.alpha > 0.002 else { return }
        let font = Fonts.display(line.size, weight: line.weight)
        let attrs: [NSAttributedString.Key: Any] = [
            .init(kCTFontAttributeName as String): font,
            .init(kCTForegroundColorAttributeName as String): Ink.ink(line.alpha * line.ink),
            .init(kCTKernAttributeName as String): line.size * line.tracking,
        ]
        let str = NSAttributedString(string: line.text, attributes: attrs)
        let ctLine = CTLineCreateWithAttributedString(str)
        let bounds = CTLineGetBoundsWithOptions(ctLine, [])
        // Optical center on the cap height, not the line box: the eye centers
        // capitals, and descenders would otherwise pull the line up.
        let capHeight = CTFontGetCapHeight(font)
        let x = (CGFloat(canvas.width) - bounds.width) / 2 - bounds.minX
        let baseline = line.centerY + capHeight / 2 + line.rise
        let ctx = canvas.context
        ctx.saveGState()
        ctx.textMatrix = CGAffineTransform(scaleX: 1, y: -1)
        ctx.textPosition = CGPoint(x: x, y: baseline)
        CTLineDraw(ctLine, ctx)
        ctx.restoreGState()
    }

    /// Line width in px, for layout checks.
    static func width(_ line: TypeLine) -> CGFloat {
        let font = Fonts.display(line.size, weight: line.weight)
        let str = NSAttributedString(string: line.text, attributes: [
            .init(kCTFontAttributeName as String): font,
            .init(kCTKernAttributeName as String): line.size * line.tracking,
        ])
        return CTLineGetBoundsWithOptions(CTLineCreateWithAttributedString(str), []).width
    }

    static func capHeight(_ line: TypeLine) -> CGFloat {
        CTFontGetCapHeight(Fonts.display(line.size, weight: line.weight))
    }
}

/// The stores' own badges on the end card, as both guidelines ask: official
/// artwork unaltered (the black editions; Play has no other), App Store first,
/// both the same height, and at least a quarter of that height between them.
enum Badges {
    static let height: CGFloat = 88
    static let gap: CGFloat = 26
    static let images: [CGImage] = ["appstore-black.png", "googleplay.png"].map {
        trimmed(try! loadImage(Paths.root.appendingPathComponent("assets/badges/\($0)")))
    }

    static func draw(centerY: CGFloat, alpha: Double, rise: CGFloat, on canvas: Canvas) {
        guard alpha > 0.002 else { return }
        let widths = images.map { height * CGFloat($0.width) / CGFloat($0.height) }
        var x = 540 - (widths.reduce(0, +) + gap * CGFloat(images.count - 1)) / 2
        for (image, w) in zip(images, widths) {
            canvas.draw(image, in: CGRect(x: x, y: centerY - height / 2 + rise, width: w, height: height), alpha: CGFloat(alpha))
            x += w + gap
        }
    }

    /// The Play badge ships inside transparent padding: crop to its ink, so
    /// "same height" means the badges, not their files.
    static func trimmed(_ image: CGImage) -> CGImage {
        let w = image.width, h = image.height
        var px = [UInt8](repeating: 0, count: w * h * 4)
        let ctx = CGContext(data: &px, width: w, height: h, bitsPerComponent: 8, bytesPerRow: w * 4,
                            space: CGColorSpace(name: CGColorSpace.sRGB)!, bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
        ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: h))
        var minX = w, minY = h, maxX = -1, maxY = -1
        for y in 0..<h {
            for x in 0..<w where px[(y * w + x) * 4 + 3] > 8 {
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
            }
        }
        guard maxX >= minX else { return image }
        return image.cropping(to: CGRect(x: minX, y: minY, width: maxX - minX + 1, height: maxY - minY + 1)) ?? image
    }
}
