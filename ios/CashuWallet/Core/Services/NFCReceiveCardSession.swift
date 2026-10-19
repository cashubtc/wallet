@preconcurrency import CoreNFC
import Foundation

@MainActor
protocol NFCReceiveTransport: AnyObject {
    func receive(request: String, accept: (NFCNdefPayload) throws -> NFCReceivePayment) async throws -> NFCReceivePayment?
    func stop()
}

@MainActor
protocol NFCReceivePresentmentAssertion: AnyObject {
    var isValid: Bool { get }
}

extension NFCPresentmentIntentAssertion: NFCReceivePresentmentAssertion {}

/// Outlives the transport and its screen, so ending a successful receive doesn't
/// immediately let the payer's remaining RF field launch the default wallet.
@MainActor
final class NFCReceivePresentment {
    static let shared = NFCReceivePresentment()

    private var assertion: (any NFCReceivePresentmentAssertion)?
    private var expiryTask: Task<Void, Never>?
    private var generation = UUID()
    private let acquire: () async throws -> any NFCReceivePresentmentAssertion
    private let waitForExpiry: () async throws -> Void

    init(
        acquire: @escaping () async throws -> any NFCReceivePresentmentAssertion = {
            try await NFCPresentmentIntentAssertion.acquire()
        },
        waitForExpiry: @escaping () async throws -> Void = {
            try await Task.sleep(for: .seconds(15))
        }
    ) {
        self.acquire = acquire
        self.waitForExpiry = waitForExpiry
    }

    func protectCompletion() async {
        // A quick second receipt shares the remaining protection, never extends
        // Apple's 15-second limit, and doesn't start an unnecessary cooldown.
        guard assertion?.isValid != true else { return }
        release()
        guard let acquired = try? await acquire(), acquired.isValid else { return }
        assertion = acquired
        let current = generation
        expiryTask = Task { [weak self, waitForExpiry] in
            do { try await waitForExpiry() } catch { return }
            guard !Task.isCancelled, self?.generation == current else { return }
            self?.release()
        }
    }

    private func release() {
        generation = UUID()
        expiryTask?.cancel()
        expiryTask = nil
        assertion = nil
    }
}

/// Uses the same foreground CardSession / presentment-assertion approach as
/// Macadamia's NFCRequestEmulation. No APDU or bearer-token data is logged.
@MainActor
final class NFCReceiveCardSession: NFCReceiveTransport {
    private var session: CardSession?

    static var isAvailable: Bool {
        get async {
            #if targetEnvironment(simulator)
            return false
            #else
            guard Bundle.main.object(forInfoDictionaryKey: "CashuHCEEnabled") as? String == "YES" else { return false }
            guard NFCReaderSession.readingAvailable, CardSession.isSupported else { return false }
            return await CardSession.isEligible
            #endif
        }
    }

    func stop() {
        session?.invalidate()
        session = nil
    }

    func receive(
        request: String,
        accept: (NFCNdefPayload) throws -> NFCReceivePayment
    ) async throws -> NFCReceivePayment? {
        guard await Self.isAvailable else {
            throw NFCReceiveError.message("Contactless receive isn't available on this iPhone or in your region.")
        }
        try Task.checkCancellation()
        var tag = try NFCType4Tag(request: request)
        // The foreground CardSession owns presentment during the exchange.
        // Save the optional assertion's short lifetime for the handoff afterward,
        // instead of spending it while waiting for a tap or minting the payment.
        let card = try await CardSession()
        defer { card.invalidate(); session = nil }
        try Task.checkCancellation()
        session = card
        var accepted: NFCReceivePayment?
        do {
            for try await event in card.eventStream {
                try Task.checkCancellation()
                switch event {
                case .sessionStarted:
                    card.alertMessage = "Hold this iPhone near the payer's device."
                    try await card.startEmulation()
                case .readerDetected:
                    if await !card.isEmulationInProgress {
                        try Task.checkCancellation()
                        try await card.startEmulation()
                    }
                case .readerDeselected:
                    tag.reset()
                case .received(let apdu):
                    let result = tag.process(apdu.payload)
                    if let payload = result.payload {
                        do {
                            accepted = try accept(payload)
                        } catch {
                            // Don't acknowledge a token that we couldn't retain.
                            try? await apdu.respond(response: Data([0x6A, 0x80]))
                            throw error
                        }
                    }
                    try await apdu.respond(response: result.response)
                    if accepted != nil {
                        // Acquire before dismissing the system sheet, but after
                        // ACK so this asynchronous call cannot time out the payer.
                        await NFCReceivePresentment.shared.protectCompletion()
                        card.alertMessage = "Payment data received."
                        await card.stopEmulation(status: .success)
                        return accepted
                    }
                case .sessionInvalidated(let reason):
                    switch reason {
                    case .userInvalidated, .maxSessionDurationReached, .emulationStopped, .invalidated:
                        return accepted
                    default: throw reason
                    }
                @unknown default: break
                }
            }
        } catch {
            // Once persisted, finish receiving even if the last response or
            // native-sheet dismissal failed. Never lose an already accepted token.
            if let accepted {
                await NFCReceivePresentment.shared.protectCompletion()
                return accepted
            }
            throw error
        }
        return accepted
    }
}
