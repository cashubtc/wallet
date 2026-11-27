import SwiftUI
import CoreImage.CIFilterBuiltins
import Cdk

// MARK: - QR Speed/Size Settings

enum QRSpeed: String, CaseIterable {
    case fast = "F"
    case medium = "M"
    case slow = "S"
    
    var interval: Double {
        switch self {
        case .fast: return 0.1
        case .medium: return 0.3
        case .slow: return 0.5
        }
    }
    
    var next: QRSpeed {
        switch self {
        case .fast: return .medium
        case .medium: return .slow
        case .slow: return .fast
        }
    }
}

enum QRSize: String, CaseIterable {
    case small = "S"
    case medium = "M"
    case large = "L"
    
    var chunkSize: Int {
        switch self {
        case .small: return 50
        case .medium: return 100
        case .large: return 200
        }
    }
    
    var next: QRSize {
        switch self {
        case .small: return .medium
        case .medium: return .large
        case .large: return .small
        }
    }
}

// MARK: - QR Code View with Controls

/// VoiceOver action names exposed on actionable QR codes. They mirror the
/// Copy / Share entries of the long-press context menu the call site attaches —
/// the same options Android's shared QrCard announces to TalkBack.
enum QRContextAccessibility {
    static let copyActionName = "Copy"
    static let shareActionName = "Share"
}

/// Shared payment-code presentation for Receive and History. Both the context
/// menu and VoiceOver invoke the same actions; the caller owns the payload.
struct PaymentCodeCard: View {
    let content: String
    let size: CGFloat
    var staticOnly = true
    let onCopy: () -> Void
    let onShare: () -> Void

    var body: some View {
        QRCodeView(content: content, showControls: false, staticOnly: staticOnly,
                   onCopy: onCopy, onShare: onShare)
            .frame(width: size, height: size)
            .padding(16)
            .background(Color.white, in: RoundedRectangle(cornerRadius: 20))
            .contextMenu {
                Button(action: onCopy) { Label("Copy", systemImage: "doc.on.doc") }
                Button(action: onShare) { Label("Share", systemImage: "square.and.arrow.up") }
            }
    }
}

/// QR Code display view with animation support for large data
/// Includes per-QR speed and size controls like cashu.me
struct QRCodeView: View {
    private nonisolated(unsafe) static let ciContext = CIContext()
    private nonisolated(unsafe) static let imageCache: NSCache<NSString, UIImage> = {
        let cache = NSCache<NSString, UIImage>()
        cache.countLimit = 32
        return cache
    }()

    private struct PlaybackKey: Equatable {
        let content: String
        let staticOnly: Bool
        let size: QRSize
        let speed: QRSpeed
    }

    let content: String
    var showControls: Bool = true
    /// When true, never UR-encode the content. Use for standardized payloads
    /// (BOLT11 invoices, BOLT12 offers, Bitcoin addresses) that other wallets
    /// expect to scan as a single static frame. UR-animated QRs only make
    /// sense for our own long Cashu tokens.
    var staticOnly: Bool = false
    /// VoiceOver Copy action, mirroring the long-press context menu's Copy.
    /// Leave nil on non-actionable QRs so assistive tech never promises an
    /// unavailable action.
    var onCopy: (() -> Void)? = nil
    /// VoiceOver Share action, mirroring the long-press context menu's Share.
    var onShare: (() -> Void)? = nil

    // Local settings per QR instance
    @State private var speed: QRSpeed = .fast
    @State private var size: QRSize = .large

    @State private var totalParts: Int = 1

    // Generating the QR on every body pass handed SwiftUI a fresh UIImage each
    // time; inside an animated update (a quote refresh while the invoice is
    // showing) the old and new images cross-dissolved and the modules went
    // grey for a frame. Cache per source string so re-renders reuse the image.
    @State private var renderedQR: (source: String, image: UIImage)?

    private var isDirectlyStatic: Bool {
        staticOnly || content.count <= size.chunkSize
    }

    private var displayedQRImage: UIImage? {
        if isDirectlyStatic {
            if let renderedQR, renderedQR.source == content { return renderedQR.image }
            return Self.cachedQRImage(for: content)
        }
        return renderedQR?.image
    }

    private var playbackKey: PlaybackKey {
        PlaybackKey(content: content, staticOnly: staticOnly, size: size, speed: speed)
    }

    var body: some View {
        VStack(spacing: 8) {
            // QR Code
            Group {
                if let image = displayedQRImage {
                    Image(uiImage: image)
                        .interpolation(.none)
                        .resizable()
                        .scaledToFit()
                        .accessibilityLabel("QR code")
                        .accessibilityHint("Contains scannable payment data")
                        .accessibilityActions {
                            if let onCopy {
                                Button(QRContextAccessibility.copyActionName, action: onCopy)
                            }
                            if let onShare {
                                Button(QRContextAccessibility.shareActionName, action: onShare)
                            }
                        }
                } else {
                    Rectangle()
                        .fill(.tertiary)
                        .overlay(
                            Image(systemName: "qrcode")
                                .font(.title)
                                .foregroundStyle(.secondary)
                        )
                        .accessibilityLabel("QR code loading")
                }
            }
            
            // Controls row (Speed & Size toggles)
            if showControls && totalParts > 1 {
                controlsRow
            }
        }
        .task(id: playbackKey) {
            await runPlayback(
                content: content,
                staticOnly: staticOnly,
                chunkSize: size.chunkSize,
                interval: speed.interval
            )
        }
    }
    
    // MARK: - Controls Row
    
    private var controlsRow: some View {
        HStack(spacing: 24) {
            // Speed toggle
            Button(action: {
                speed = speed.next
            }) {
                HStack(spacing: 6) {
                    Image(systemName: "bolt.fill")
                        .font(.caption)
                        .accessibilityHidden(true)
                    Text("SPEED: \(speed.rawValue)")
                        .font(.caption)
                        .fontWeight(.medium)
                }
                .foregroundStyle(.secondary)
            }
            .accessibilityLabel("QR animation speed: \(speed.rawValue)")
            .accessibilityHint("Cycles through fast, medium, and slow animation speeds")

            // Size toggle
            Button(action: {
                size = size.next
            }) {
                HStack(spacing: 6) {
                    Image(systemName: "magnifyingglass")
                        .font(.caption)
                        .accessibilityHidden(true)
                    Text("SIZE: \(size.rawValue)")
                        .font(.caption)
                        .fontWeight(.medium)
                }
                .foregroundStyle(.secondary)
            }
            .accessibilityLabel("QR chunk size: \(size.rawValue)")
            .accessibilityHint("Cycles through small, medium, and large QR code chunk sizes")
        }
        .padding(.top, 4)
    }
    
    // MARK: - Encoder & Rendering

    @MainActor
    private func runPlayback(
        content: String,
        staticOnly: Bool,
        chunkSize: Int,
        interval: Double
    ) async {
        // Static-only and short payloads stay a single standard QR frame and
        // reuse the 1x module cache with no background encoding work.
        if staticOnly || content.count <= chunkSize {
            totalParts = 1
            if renderedQR?.source != content {
                renderedQR = Self.cachedQRImage(for: content).map { (content, $0) }
            }
            return
        }

        let disableAnimations = IntegrationTestConfig.shouldDisableAnimations
        let publish: @MainActor (String, UIImage?, Int) -> Void = { source, image, parts in
            self.totalParts = parts
            if let image {
                self.renderedQR = (source, image)
            } else {
                self.renderedQR = nil
            }
        }

        // Decode tokens, step the NUT-16 fountain encoder, and rasterize frames
        // off the main actor so animated QR playback never stalls UI interaction.
        let worker = Task.detached(priority: .userInitiated) {
            if let token = try? Token.decode(encodedToken: content),
               let urEncoder = try? token.urEncoder(maxFragmentLength: UInt32(chunkSize)),
               !urEncoder.isSingleFragment() {
                let parts = max(1, Int(urEncoder.fragmentCount()))
                let firstPart = (try? urEncoder.nextPart()) ?? content
                let firstImage = Self.generateQRImage(from: firstPart)
                guard !Task.isCancelled else { return }
                await publish(firstPart, firstImage, parts)

                // Keep the initial encoded frame in deterministic UI journeys. Updating
                // it every 100 ms prevents XCTest accessibility snapshots from settling.
                guard !disableAnimations else { return }

                let sleepNanos = UInt64(interval * 1_000_000_000)
                while !Task.isCancelled {
                    try? await Task.sleep(nanoseconds: sleepNanos)
                    guard !Task.isCancelled else { break }
                    guard let part = try? urEncoder.nextPart(),
                          let image = Self.generateQRImage(from: part) else {
                        continue
                    }
                    guard !Task.isCancelled else { break }
                    await publish(part, image, parts)
                }
            } else {
                let image = Self.cachedQRImage(for: content)
                guard !Task.isCancelled else { return }
                await publish(content, image, 1)
            }
        }

        await withTaskCancellationHandler {
            await worker.value
        } onCancel: {
            worker.cancel()
        }
    }

    private nonisolated static func cachedQRImage(for string: String) -> UIImage? {
        guard !string.isEmpty else { return nil }
        let key = string as NSString
        if let cached = imageCache.object(forKey: key) {
            return cached
        }
        guard let image = generateQRImage(from: string) else { return nil }
        imageCache.setObject(image, forKey: key)
        return image
    }

    /// Rasterizes the QR at native 1 px per module; `Image.interpolation(.none)`
    /// scales modules crisply on the GPU without allocating a multi-megapixel bitmap.
    nonisolated static func generateQRImage(from string: String) -> UIImage? {
        guard !string.isEmpty else { return nil }

        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(string.utf8)
        filter.correctionLevel = "M"

        guard let outputImage = filter.outputImage,
              let cgImage = ciContext.createCGImage(outputImage, from: outputImage.extent) else {
            return nil
        }

        return UIImage(cgImage: cgImage)
    }
}

// MARK: - Previews

#Preview("Static QR") {
    ZStack {
        Color.black
            .ignoresSafeArea()
        
        QRCodeView(content: "cashuAeyJwcm9vZnMiOlt7InByb29mIjoiIn1d")
            .frame(width: 250, height: 280)
            .padding()
            .background(Color.white)
            .clipShape(.rect(cornerRadius: 12))
    }
}

#Preview("Animated QR") {
    ZStack {
        Color.black
            .ignoresSafeArea()
        
        // Long content to trigger animation
        QRCodeView(content: String(repeating: "cashuAeyJwcm9vZnMiOlt7InByb29mIjoiIn1d", count: 10))
            .frame(width: 250, height: 300)
            .padding()
            .background(Color.white)
            .clipShape(.rect(cornerRadius: 12))
    }
}
