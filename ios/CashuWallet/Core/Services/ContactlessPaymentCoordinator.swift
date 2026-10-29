import CoreNFC
import Foundation
import UIKit

/// Orchestrates a contactless NFC payment with no custom SwiftUI chrome.
/// The iOS native NFC scan sheet ("Hold Near Reader") is the entire user
/// surface; status text flows through `session.alertMessage` and errors
/// through `session.invalidate(errorMessage:)`.
///
/// Holds the delegate + session as instance state so they survive past the
/// triggering tap (NFCNDEFReaderSession requires a stable delegate pointer
/// for the duration of the scan).
@MainActor
final class ContactlessPaymentCoordinator {
    private var delegate: NFCReaderDelegate?
    private var session: NFCNDEFReaderSession?
    private var paymentTask: Task<Void, Never>?
    private var sessionID = UUID()

    func stop() {
        sessionID = UUID()
        paymentTask?.cancel()
        session?.invalidate()
        session = nil
        delegate = nil
    }

    func start(walletManager: WalletManager, navigationManager: NavigationManager) {
        guard NFCNDEFReaderSession.readingAvailable, UIApplication.shared.applicationState == .active,
              !AppLockManager.shared.isLocked, walletManager.isRuntimeReady,
              paymentTask == nil else { return }

        // Tear down any prior session so a back-to-back trigger doesn't
        // leak readers.
        stop()
        let id = sessionID

        let delegate = NFCReaderDelegate(
            onTagDetected: { [weak self] tag, session in
                guard let self, self.sessionID == id, self.paymentTask == nil else { return }
                self.paymentTask = Task {
                    await self.handleTagDetected(tag: tag, session: session,
                        walletManager: walletManager, navigationManager: navigationManager)
                    self.paymentTask = nil
                }
            },
            onError: { _ in
                // iOS surfaces the error in its own sheet; no extra UI.
            },
            onSessionEnd: { [weak self] in
                guard self?.sessionID == id else { return }
                self?.paymentTask?.cancel()
                self?.delegate = nil
                self?.session = nil
            }
        )
        self.delegate = delegate

        let session = NFCNDEFReaderSession(delegate: delegate, queue: nil, invalidateAfterFirstRead: false)
        session.alertMessage = "Hold your iPhone near the payment terminal"
        self.session = session
        session.begin()
    }

    private func handleTagDetected(
        tag: NFCNDEFTag,
        session: NFCNDEFReaderSession,
        walletManager: WalletManager,
        navigationManager: NavigationManager
    ) async {
        let service = NFCPaymentService(walletManager: walletManager, policy: .shared,
            isActive: { [weak self] in
                self?.session === session && UIApplication.shared.applicationState == .active
                    && !AppLockManager.shared.isLocked && walletManager.isRuntimeReady
            },
            authenticate: {
                await AppLockManager.shared.authenticateContactless(reason: "Approve contactless payment")
            },
            showPayment: { session.alertMessage = $0 }
        )

        do {
            session.alertMessage = "Reading payment request..."

            do {
                try await session.connect(to: tag)
            } catch {
                throw NFCPaymentError.tagConnectionFailed
            }

            let ndefMessage: NFCNDEFMessage
            do {
                ndefMessage = try await tag.readNDEF()
            } catch {
                throw NFCPaymentError.nfcReadFailed(error.localizedDescription)
            }

            guard let rawInput = NDEFTextRecord.extractText(from: ndefMessage) else {
                throw NFCPaymentError.nfcReadFailed("No readable data on tag")
            }

            let input = try service.decode(rawInput)

            switch input {
            case .creq(let request):
                let (status, capacity) = try await tag.queryNDEFStatus()
                guard status == .readWrite else {
                    throw NFCPaymentError.nfcWriteFailed("NFC tag is not writable.")
                }
                try await service.pay(request) { token in
                    let message = NDEFTextRecord.makeMessage(with: token)
                    guard message.length <= capacity else {
                        throw NFCPaymentError.nfcWriteFailed("NFC tag is too small for payment token.")
                    }
                    try await tag.writeNDEF(message)
                }

                session.alertMessage = "Payment sent!"
                session.invalidate()

            case .bolt11(let invoice):
                let decoded = PaymentRequestDecoder.decode(invoice)
                // An invoice no mint can pay as it stands still opens Send,
                // whose payment sheet says why (paste and scan parity).
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
