@preconcurrency import CoreNFC
import Foundation

/// Orchestrates a contactless NFC payment with no custom SwiftUI chrome.
/// The iOS native NFC scan sheet ("Hold Near Reader") is the entire user
/// surface; status text flows through `session.alertMessage` and errors
/// through `session.invalidate(errorMessage:)`.
///
/// Holds the delegate + session as instance state so they survive past the
/// triggering tap (NFCTagReaderSession requires a stable delegate pointer
/// for the duration of the scan).
@MainActor
final class ContactlessPaymentCoordinator {
    private var delegate: NFCReaderDelegate?
    private var session: NFCTagReaderSession?
    private var isProcessing = false

    func start(walletManager: WalletManager, navigationManager: NavigationManager) {
        guard NFCTagReaderSession.readingAvailable else { return }

        // Tear down any prior session so a back-to-back trigger doesn't
        // leak readers.
        session?.invalidate()
        delegate = nil
        session = nil
        isProcessing = false

        let delegate = NFCReaderDelegate(
            onTagDetected: { [weak self] tag, session in
                await self?.handleTagDetected(
                    tag: tag,
                    session: session,
                    walletManager: walletManager,
                    navigationManager: navigationManager
                )
            },
            onSessionEnd: { [weak self] endedSession in
                guard self?.session === endedSession else { return }
                self?.delegate = nil
                self?.session = nil
                self?.isProcessing = false
            }
        )
        self.delegate = delegate

        guard let session = NFCTagReaderSession(
            pollingOption: [.iso14443, .iso15693, .iso18092], delegate: delegate, queue: nil
        ) else { self.delegate = nil; return }
        session.alertMessage = "Hold your iPhone near the payment terminal"
        self.session = session
        session.begin()
    }

    private func handleTagDetected(
        tag: NFCTag,
        session: NFCTagReaderSession,
        walletManager: WalletManager,
        navigationManager: NavigationManager
    ) async {
        guard self.session === session, !isProcessing else { return }
        isProcessing = true
        defer { if self.session === session { isProcessing = false } }
        let service = NFCPaymentService(walletManager: walletManager)

        do {
            session.alertMessage = "Reading payment request..."

            do {
                try await session.connect(to: tag)
            } catch {
                throw NFCPaymentError.tagConnectionFailed
            }

            let customReader: NFCType4Reader?
            let ndefTag: any NFCNDEFTag
            switch tag {
            case .iso7816(let iso):
                ndefTag = iso
                if iso.initialSelectedAID.uppercased() == NFCType4Tag.aidHex {
                    customReader = NFCType4Reader { bytes in
                        guard let apdu = NFCISO7816APDU(data: bytes) else {
                            throw NFCPaymentError.nfcReadFailed("Invalid command")
                        }
                        let (data, sw1, sw2) = try await iso.sendCommand(apdu: apdu)
                        return data + Data([sw1, sw2])
                    }
                } else { customReader = nil }
            case .miFare(let native): ndefTag = native; customReader = nil
            case .iso15693(let native): ndefTag = native; customReader = nil
            case .feliCa(let native): ndefTag = native; customReader = nil
            @unknown default: throw NFCPaymentError.nfcReadFailed("Unsupported payment tag")
            }

            let ndefMessage: NFCNDEFMessage
            do {
                if let customReader {
                    guard let message = NFCNDEFMessage(data: try await customReader.read()) else {
                        throw NFCPaymentError.nfcReadFailed("Invalid payment request")
                    }
                    ndefMessage = message
                } else {
                    // Preserve standard NDEF tags and existing Android/Numo receivers.
                    ndefMessage = try await ndefTag.readNDEF()
                }
            } catch {
                throw NFCPaymentError.nfcReadFailed(error.localizedDescription)
            }

            guard let rawInput = NDEFTextRecord.extractText(from: ndefMessage) else {
                throw NFCPaymentError.nfcReadFailed("No readable data on tag")
            }

            guard self.session === session else { return }
            let input = try service.decode(rawInput)

            switch input {
            case .creq(let request):
                if let customReader {
                    guard customReader.isWritable else {
                        throw NFCPaymentError.nfcWriteFailed("Payment tag is read-only")
                    }
                } else {
                    let (status, _) = try await ndefTag.queryNDEFStatus()
                    guard status == .readWrite else {
                        throw NFCPaymentError.nfcWriteFailed("Payment tag is read-only")
                    }
                }
                guard self.session === session else { return }
                session.alertMessage = "Preparing payment..."
                let token = try await service.prepareToken(for: request)

                guard self.session === session else { return }
                session.alertMessage = "Sending payment..."
                do {
                    if let customReader {
                        try await customReader.write(text: token)
                    } else {
                        try await ndefTag.writeNDEF(NDEFTextRecord.makeMessage(with: token))
                    }
                } catch {
                    throw NFCPaymentError.nfcWriteFailed(error.localizedDescription)
                }

                session.alertMessage = "Payment sent!"
                session.invalidate()

            case .bolt11(let invoice):
                let decoded = PaymentRequestDecoder.decode(invoice)
                // Refuse on the NFC sheet what Send's own input would refuse.
                if let caution = decoded.amountlessMeltCaution(payableBy: walletManager.mints) {
                    session.invalidate(errorMessage: caution)
                    return
                }
                session.alertMessage = "Lightning request found"
                session.invalidate()
                // The Send sheet already closed when the NFC session started,
                // so Send's payment steps present as soon as the system NFC
                // surface yields — the same UI a pasted invoice gets.
                navigationManager.present(.sheet(.sendAmount(
                    .melt(request: invoice, mode: .lightning, decoded: decoded)
                )))
            }
        } catch let error as NFCPaymentError {
            session.invalidate(errorMessage: error.localizedDescription)
        } catch {
            session.invalidate(errorMessage: error.localizedDescription)
        }
    }
}
