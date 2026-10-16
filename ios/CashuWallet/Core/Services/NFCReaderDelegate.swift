@preconcurrency import CoreNFC
import Foundation

final class NFCReaderDelegate: NSObject, NFCTagReaderSessionDelegate, @unchecked Sendable {
    let onTagDetected: @MainActor @Sendable (NFCTag, NFCTagReaderSession) async -> Void
    let onSessionEnd: @MainActor @Sendable (NFCTagReaderSession) -> Void

    init(
        onTagDetected: @escaping @MainActor @Sendable (NFCTag, NFCTagReaderSession) async -> Void,
        onSessionEnd: @escaping @MainActor @Sendable (NFCTagReaderSession) -> Void
    ) {
        self.onTagDetected = onTagDetected
        self.onSessionEnd = onSessionEnd
    }

    func tagReaderSessionDidBecomeActive(_ session: NFCTagReaderSession) {}

    func tagReaderSession(_ session: NFCTagReaderSession, didInvalidateWithError error: Error) {
        Task { @MainActor in self.onSessionEnd(session) }
    }

    func tagReaderSession(_ session: NFCTagReaderSession, didDetect tags: [NFCTag]) {
        guard tags.count == 1, let tag = tags.first else {
            session.alertMessage = "Hold near one payment device at a time."
            session.restartPolling()
            return
        }
        Task { @MainActor in await self.onTagDetected(tag, session) }
    }
}
