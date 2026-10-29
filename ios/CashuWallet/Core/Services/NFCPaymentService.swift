import Cdk
import Foundation

enum NFCPaymentError: LocalizedError {
    case nfcUnavailable
    case invalidPaymentRequest(String)
    case noAmountSpecified
    case unsupportedUnit(String)
    case noMatchingMint(requestedMints: [String])
    case insufficientBalance(required: UInt64, available: UInt64)
    case tokenCreationFailed(String)
    case nfcReadFailed(String)
    case nfcWriteFailed(String)
    case tagConnectionFailed

    var errorDescription: String? {
        switch self {
        case .nfcUnavailable:
            return "NFC is not available on this device"
        case .invalidPaymentRequest(let detail):
            return "Invalid payment request: \(detail)"
        case .noAmountSpecified:
            return "Payment request does not specify an amount"
        case .unsupportedUnit(let unit):
            return "Unsupported unit: \(unit)"
        case .noMatchingMint(let requestedMints):
            if requestedMints.count == 1 {
                let mint = formatMintURL(requestedMints[0])
                return "This payment requires funds from \(mint), but you don't have any balance there. Add this mint to your wallet and receive some ecash first."
            } else {
                let mintList = requestedMints.map { formatMintURL($0) }.joined(separator: ", ")
                return "This payment requires funds from one of these mints: \(mintList). You don't have any balance with these mints. Add one of them to your wallet and receive some ecash first."
            }
        case .insufficientBalance(let required, let available):
            return "Insufficient balance: need \(required), have \(available)"
        case .tokenCreationFailed(let detail):
            return "Failed to create token: \(detail)"
        case .nfcReadFailed(let detail):
            return "Failed to read NFC tag: \(detail)"
        case .nfcWriteFailed(let detail):
            return "Failed to write to NFC tag: \(detail)"
        case .tagConnectionFailed:
            return "Failed to connect to NFC tag"
        }
    }

    private func formatMintURL(_ urlString: String) -> String {
        if let url = URL(string: urlString), let host = url.host {
            return host
        }
        return urlString
    }
}

@MainActor
final class NFCPaymentService {
    enum NFCInput {
        case creq(Cdk.PaymentRequest)
        case bolt11(String)
    }

    private let walletManager: WalletManager
    private let policy: ContactlessSpendPolicy
    private let isActive: () -> Bool
    private let authenticate: () async -> Bool
    private let showPayment: (String) -> Void

    init(walletManager: WalletManager, policy: ContactlessSpendPolicy,
         isActive: @escaping () -> Bool, authenticate: @escaping () async -> Bool,
         showPayment: @escaping (String) -> Void) {
        self.walletManager = walletManager
        self.policy = policy
        self.isActive = isActive
        self.authenticate = authenticate
        self.showPayment = showPayment
    }

    func decode(_ raw: String) throws -> NFCInput {
        var input = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !input.isEmpty else {
            throw NFCPaymentError.invalidPaymentRequest("Empty payment request")
        }

        if input.lowercased().hasPrefix("bitcoin:") {
            let lightningFallback = PaymentRequestDecoder.encodedLightningRequest(from: input)

            if let creq = PaymentRequestDecoder.encodedCashuPaymentRequest(from: input) {
                do {
                    let request = try decodePaymentRequest(encoded: creq)
                    // Same decision as scanner/Send: pay from ecash when a held
                    // mint can; else use the bundled bolt11. Tap-to-pay has no
                    // "add mint & fund" UI, so an unfundable creq falls back to
                    // the invoice, or surfaces the no-matching-mint error later.
                    if let summary = PaymentRequestDecoder.cashuPaymentRequestSummary(from: creq) {
                        switch walletManager.routeForCashuPaymentRequest(summary, rawContent: input) {
                        case .payWithEcash:
                            return .creq(request)
                        case .payBolt11Fallback(let bolt11):
                            return .bolt11(bolt11)
                        case .acquireThenPay:
                            return .creq(request)
                        }
                    }
                    if Self.canPrepareInBandToken(for: request) || lightningFallback == nil {
                        return .creq(request)
                    }
                } catch {
                    if lightningFallback == nil {
                        throw NFCPaymentError.invalidPaymentRequest(error.userFacingWalletMessage)
                    }
                }
            }

            if let bolt11 = lightningFallback {
                return .bolt11(bolt11)
            }

            throw NFCPaymentError.invalidPaymentRequest("No supported payment method in bitcoin URI")
        }

        input = Self.stripPrefix("cashu://", from: input)
        input = Self.stripPrefix("cashu:", from: input)
        input = Self.stripPrefix("lightning://", from: input)
        input = Self.stripPrefix("lightning:", from: input)

        if let lightningRequest = PaymentRequestDecoder.encodedLightningRequest(from: input) {
            return .bolt11(lightningRequest)
        }

        do {
            return .creq(try decodePaymentRequest(encoded: input))
        } catch {
            throw NFCPaymentError.invalidPaymentRequest(error.userFacingWalletMessage)
        }
    }

    func pay(_ request: Cdk.PaymentRequest, deliver: @escaping (String) async throws -> Void) async throws {
        guard isActive(), walletManager.isRuntimeReady else { throw ContactlessPaymentError.inactive }
        guard let requestedAmount = request.amount(), requestedAmount.value > 0 else {
            throw NFCPaymentError.noAmountSpecified
        }

        let amount = requestedAmount.value

        if let unit = request.unit() {
            switch unit {
            case .sat:
                break
            default:
                throw NFCPaymentError.unsupportedUnit(Self.description(for: unit))
            }
        }

        let selectedMint = try selectMint(
            for: request,
            amount: amount,
            preferredMintURL: walletManager.activeMint?.url
        )

        do {
            _ = try await walletManager.sendTokens(
                amount: amount,
                memo: nil,
                p2pkPubkey: nil,
                mintUrl: selectedMint.url,
                contactless: ContactlessSendCallbacks(
                    approve: { total in
                        let host = URL(string: selectedMint.url)?.host ?? selectedMint.url
                        let memo = request.description()?.components(separatedBy: .newlines).joined(separator: " ").prefix(120)
                        self.showPayment("Amount: \(amount) sat\nMint: \(host)\nMemo: \(memo.map(String.init) ?? "None")\nTotal: \(total) sat")
                        await Task.yield()
                        try await self.policy.authorizeAndRecord(total: total,
                            isActive: self.isActive, authenticate: self.authenticate)
                    },
                    deliver: { token in
                        guard self.isActive() else { throw ContactlessPaymentError.inactive }
                        try await deliver(token)
                    }
                )
            )
        } catch {
            await walletManager.refreshBalance()
            await walletManager.loadTransactions()
            throw error
        }
    }

    private func selectMint(
        for request: Cdk.PaymentRequest,
        amount: UInt64,
        preferredMintURL: String?
    ) throws -> MintInfo {
        try MintSelection.select(from: walletManager.mints, requestedURLs: request.mints(), amount: amount, preferredURL: preferredMintURL)
    }

    private static func stripPrefix(_ prefix: String, from input: String) -> String {
        if input.lowercased().hasPrefix(prefix) {
            return String(input.dropFirst(prefix.count))
        }
        return input
    }

    private static func canPrepareInBandToken(for request: Cdk.PaymentRequest) -> Bool {
        guard let unit = request.unit() else { return true }
        if case .sat = unit {
            return true
        }
        return false
    }

    private static func description(for unit: Cdk.CurrencyUnit) -> String {
        switch unit {
        case .sat:
            return "sat"
        case .msat:
            return "msat"
        case .usd:
            return "usd"
        case .eur:
            return "eur"
        case .auth:
            return "auth"
        case .custom(let unit):
            return unit
        }
    }
}
