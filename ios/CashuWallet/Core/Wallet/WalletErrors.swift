import Foundation
import Cdk

struct WalletMessage {
    enum Recoverability { case retryable, terminal }
    let text: String
    let severity: ErrorSeverity
    var recoverability: Recoverability = .retryable
}

enum AppErrorCauseType { case ffiCdk, ffiInternal, network, database, native }
enum AppErrorCategory: String { case `protocol`, `internal` }
enum AppErrorSeverity { case error, caution, info }

struct AppErrorInfo {
    let code: UInt32?
    let userMessage: String
    let technicalMessage: String
    let category: AppErrorCategory
    let severity: AppErrorSeverity
    let retryable: Bool
    let terminal: Bool
    let reportable: Bool
}

struct NostrErrorReport: Codable {
    let schemaVersion: UInt32
    let reportId: String
    let createdAt: UInt64
    let appName: String
    let appVersion: String
    let appBuild: String
    let platform: String
    let osVersion: String
    let operation: String
    let errorCode: UInt32?
    let userMessage: String
    let technicalMessage: String
    let userNote: String?

    enum CodingKeys: String, CodingKey {
        case schemaVersion = "schema_version"
        case reportId = "report_id"
        case createdAt = "created_at"
        case appName = "app_name"
        case appVersion = "app_version"
        case appBuild = "app_build"
        case platform
        case osVersion = "os_version"
        case operation
        case errorCode = "error_code"
        case userMessage = "user_message"
        case technicalMessage = "technical_message"
        case userNote = "user_note"
    }
}

struct NostrReportReceipt {
    let eventId: String
    let acceptedRelays: UInt32
    let failedRelays: UInt32
}

/// One application error contract for FFI and native iOS failures.
struct AppError: Identifiable {
    let info: AppErrorInfo
    let reportId: String
    let timestamp: UInt64
    let operation: String
    let causeType: AppErrorCauseType
    let cause: Error?

    var id: String { reportId }
    var isReportable: Bool { info.reportable }
    var walletMessage: WalletMessage {
        WalletMessage(
            text: info.userMessage,
            severity: ErrorSeverity(info.severity),
            recoverability: info.terminal ? .terminal : .retryable
        )
    }

    static func from(_ error: Error, operation: String) -> AppError {
        let causeType: AppErrorCauseType
        if let ffi = error as? Cdk.FfiError {
            switch ffi {
            case .Cdk: causeType = .ffiCdk
            case .Internal: causeType = .ffiInternal
            }
        } else if error is URLError {
            causeType = .network
        } else if ErrorInfoResolver.looksLikeDatabase(error) {
            causeType = .database
        } else {
            causeType = .native
        }
        let info = (error as? WalletError)?.info ?? ErrorInfoResolver.resolve(error, causeType: causeType)
        return AppError(
            info: info,
            reportId: UUID().uuidString.lowercased(),
            timestamp: UInt64(Date().timeIntervalSince1970),
            operation: operation,
            causeType: causeType,
            cause: error
        )
    }

    static func fromMessage(_ message: String, operation: String) -> AppError {
        from(WalletError.networkError(message), operation: operation)
    }

    func preparedReport(userNote: String? = nil) -> NostrErrorReport {
        let bundle = Bundle.main
        return NostrErrorReport(
            schemaVersion: 1,
            reportId: reportId,
            createdAt: timestamp,
            appName: "cashu.me",
            appVersion: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown",
            appBuild: bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown",
            platform: "ios",
            osVersion: ProcessInfo.processInfo.operatingSystemVersionString,
            operation: ErrorSanitizer.text(operation, maxBytes: 128),
            errorCode: info.code,
            userMessage: ErrorSanitizer.text(info.userMessage, maxBytes: 512),
            technicalMessage: ErrorSanitizer.text(info.technicalMessage, maxBytes: 2_048),
            userNote: userNote.map { ErrorSanitizer.text($0, maxBytes: 1_024) }.flatMap { $0.isEmpty ? nil : $0 }
        )
    }
}

/// Compatibility boundary to be replaced by CDK's FFI resolver once it is released.
enum ErrorInfoResolver {
    static let fallback = "The wallet couldn't finish that action. Try again in a moment."

    static func resolve(_ error: Error, causeType: AppErrorCauseType) -> AppErrorInfo {
        var code: UInt32?
        let detail: String
        if let ffi = error as? Cdk.FfiError {
            switch ffi {
            case .Cdk(let value, let message): code = value; detail = message
            case .Internal(let message): detail = message
            }
        } else if let localized = error as? LocalizedError, let message = localized.errorDescription, !message.isEmpty {
            detail = message
        } else {
            detail = String(describing: error)
        }
        return resolve(code: code, detail: detail, causeType: causeType)
    }

    static func resolve(code: UInt32?, detail: String, causeType: AppErrorCauseType) -> AppErrorInfo {
        let normalized = detail.lowercased()
        let safeDetail = ErrorSanitizer.text(detail.isEmpty ? "Unknown error" : detail, maxBytes: 2_048)
        let terminal = contains(normalized, ["already issued", "already paid", "already spent", "already redeemed"])
        let caution = contains(normalized, ["unsupported", "fee exceeded", "amountless", "outside of allowed"])
        let userMessage: String
        switch normalized {
        case let value where contains(value, ["already being minted"]): userMessage = "This payment is already being claimed. Give it a moment and refresh."
        case let value where contains(value, ["already issued", "already minted"]): userMessage = "Ecash has already been issued for this quote."
        case let value where contains(value, ["already paid", "invoice already paid"]): userMessage = "This invoice has already been paid."
        case let value where contains(value, ["token already spent", "proof already used", "already redeemed"]): userMessage = "This token was already redeemed."
        case let value where contains(value, ["insufficient", "not enough", "no spendable", "balance too low"]): userMessage = "Not enough balance."
        case let value where contains(value, ["expired quote", "quote expired", "invoice expired"]): userMessage = "This quote has expired. Create a new request."
        case let value where contains(value, ["pending quote", "payment pending", "quote pending"]): userMessage = "The payment is still pending. Try again shortly."
        case let value where contains(value, ["duplicate outputs", "already signed"]): userMessage = "The wallet fell out of sync with this mint. Try again to resync."
        case let value where contains(value, ["invalid proof", "could not verify", "dleq"]): userMessage = "This token could not be verified. Ask the sender for a new token."
        case let value where contains(value, ["unsupported unit"]): userMessage = "This mint doesn't support that unit. Choose another mint."
        case let value where contains(value, ["unsupported payment method"]): userMessage = "This mint doesn't support that payment method. Choose another mint."
        case let value where contains(value, ["invalid payment request", "invalid invoice"]): userMessage = "That payment request isn't valid. Check it and try again."
        case let value where contains(value, ["timeout", "timed out"]): userMessage = "The mint took too long to respond. Check your connection and try again."
        case let value where contains(value, ["network", "connection", "connect", "dns", "offline", "tls", "certificate"]): userMessage = "Couldn't reach the mint. Check your connection and try again."
        case let value where contains(value, ["sqlite", "database", "corrupt", "malformed"]): userMessage = "The wallet database could not be opened. Restart the app and try again."
        default: userMessage = causeType == .ffiCdk && !safeDetail.isEmpty ? safeDetail : fallback
        }
        let expected = terminal || caution || contains(normalized, [
            "insufficient", "not enough", "expired", "pending", "invalid invoice",
            "unsupported", "timeout", "offline", "network", "connection", "cancel"
        ])
        return AppErrorInfo(
            code: code,
            userMessage: userMessage,
            technicalMessage: safeDetail,
            category: causeType == .ffiCdk ? .protocol : .internal,
            severity: caution ? .caution : .error,
            retryable: !terminal,
            terminal: terminal,
            reportable: !expected && [.ffiInternal, .database, .native].contains(causeType)
        )
    }

    static func looksLikeDatabase(_ error: Error) -> Bool {
        contains(String(describing: error).lowercased(), ["sqlite", "database", "corrupt"])
    }

    private static func contains(_ value: String, _ terms: [String]) -> Bool { terms.contains { value.contains($0) } }
}

enum ErrorSanitizer {
    private static let replacements: [(String, String)] = [
        (#"\bnsec1[023456789acdefghjklmnpqrstuvwxyz]+\b"#, "<redacted-nsec>"),
        (#"\bnostr\+walletconnect://[^\s,;)\"']+"#, "<redacted-nwc-uri>"),
        (#"\bcashu[ab][a-z0-9_\-=]{16,}\b"#, "<redacted-cashu-token>"),
        (#"https?://[^\s,;)\"']+"#, "<redacted-url>"),
        (#"(?<![A-Za-z0-9])/(?:Users|private|data|var|tmp|storage|sdcard)/[^\s,;)\"']+"#, "<redacted-path>")
    ]

    static func text(_ value: String, maxBytes: Int) -> String {
        var safe = value
        for (pattern, replacement) in replacements {
            safe = safe.replacingOccurrences(of: pattern, with: replacement, options: [.regularExpression, .caseInsensitive])
        }
        safe = safe.replacingOccurrences(
            of: #"\b(mnemonic|seed phrase|private key|secret)\s*[:=]\s*([^\s,;]+(?:\s+[^\s,;]+){0,23})"#,
            with: "$1=<redacted>",
            options: [.regularExpression, .caseInsensitive]
        ).trimmingCharacters(in: .whitespacesAndNewlines)
        while safe.utf8.count > maxBytes && !safe.isEmpty { safe.removeLast() }
        return safe
    }
}

enum NostrErrorReporter {
    static var recipientNprofile: String {
        Bundle.main.object(forInfoDictionaryKey: "NostrErrorReportNprofile") as? String ?? ""
    }
    static var isConfigured: Bool { NostrErrorTransport.parseNprofile(recipientNprofile) != nil }

    static func send(_ error: AppError, userNote: String?) async throws -> NostrReportReceipt {
        guard let recipient = NostrErrorTransport.parseNprofile(recipientNprofile) else {
            throw WalletError.networkError("Error reporting is not configured for this build.")
        }
        return try await NostrErrorTransport.send(recipient: recipient, report: error.preparedReport(userNote: userNote))
    }
}

enum WalletErrorMessage {
    static func classified(for error: Error) -> WalletMessage { AppError.from(error, operation: "unknown").walletMessage }
    static func message(for error: Error) -> String { classified(for: error).text }
}

extension ErrorSeverity {
    init(_ severity: AppErrorSeverity) {
        switch severity {
        case .error: self = .error
        case .caution: self = .caution
        case .info: self = .info
        }
    }
}

extension Error {
    var userFacingWalletMessage: String { WalletErrorMessage.message(for: self) }
    var walletMessage: WalletMessage { WalletErrorMessage.classified(for: self) }
    var appError: AppError { AppError.from(self, operation: "unknown") }
    var isInsufficientBalanceError: Bool {
        if let walletError = self as? WalletError, case .insufficientBalance = walletError { return true }
        return walletMessage.text == "Not enough balance."
    }
}

enum WalletError: LocalizedError {
    case notInitialized
    case mintAlreadyExists
    case invalidMnemonic
    case insufficientBalance
    case networkError(String)

    fileprivate var info: AppErrorInfo {
        let message: String
        switch self {
        case .notInitialized: message = "The wallet is still starting up. Try again in a moment."
        case .mintAlreadyExists: message = "This mint is already in your wallet."
        case .invalidMnemonic: message = "That seed phrase doesn't look right. Check the spelling and try again."
        case .insufficientBalance: message = "Not enough balance."
        case .networkError(let detail): return ErrorInfoResolver.resolve(code: nil, detail: detail, causeType: .network)
        }
        return AppErrorInfo(
            code: nil, userMessage: message, technicalMessage: message, category: .internal,
            severity: .error, retryable: true, terminal: false, reportable: false
        )
    }

    var errorDescription: String? { info.userMessage }
}
