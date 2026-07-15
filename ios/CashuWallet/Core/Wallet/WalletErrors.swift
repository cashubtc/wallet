import Foundation
import Cdk

struct WalletMessage {
    enum Recoverability { case retryable, terminal }

    let text: String
    let severity: ErrorSeverity
    var recoverability: Recoverability = .retryable
}

enum AppErrorCauseType {
    case ffiCdk, ffiInternal, network, native
}

/// One application error contract for FFI and native iOS failures.
struct AppError: Identifiable {
    let info: FfiErrorInfo
    let reportId: String
    let timestamp: UInt64
    let operation: String
    let causeType: AppErrorCauseType
    let cause: Error?

    var id: String { reportId }
    var isReportable: Bool { info.severity == .error }

    var walletMessage: WalletMessage {
        WalletMessage(
            text: info.userMessage,
            severity: ErrorSeverity(info.severity),
            recoverability: info.terminal ? .terminal : .retryable
        )
    }

    static func from(_ error: Error, operation: String) -> AppError {
        let descriptor: (FfiErrorInfo, AppErrorCauseType)
        if let ffiError = error as? Cdk.FfiError {
            switch ffiError {
            case .Cdk(let code, let errorMessage):
                descriptor = (getErrorInfo(code: code, detail: errorMessage), .ffiCdk)
            case .Internal(let errorMessage):
                descriptor = (getErrorInfo(code: nil, detail: errorMessage), .ffiInternal)
            }
        } else if let urlError = error as? URLError {
            descriptor = (
                getErrorInfo(code: nil, detail: "Network error: \(safeDetail(urlError))"),
                .network
            )
        } else if let walletError = error as? WalletError {
            descriptor = (walletError.info, .native)
        } else {
            descriptor = (getErrorInfo(code: nil, detail: safeDetail(error)), .native)
        }

        return AppError(
            info: descriptor.0,
            reportId: UUID().uuidString.lowercased(),
            timestamp: UInt64(Date().timeIntervalSince1970),
            operation: operation,
            causeType: descriptor.1,
            cause: error
        )
    }

    static func fromMessage(_ message: String, operation: String) -> AppError {
        from(WalletError.networkError(message), operation: operation)
    }

    func preparedReport(userNote: String? = nil) -> NostrErrorReport {
        let bundle = Bundle.main
        return prepareNostrErrorReport(
            report: NostrErrorReport(
                schemaVersion: 0,
                reportId: reportId,
                createdAt: timestamp,
                appName: "cashu.me",
                appVersion: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown",
                appBuild: bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "unknown",
                platform: "ios",
                osVersion: ProcessInfo.processInfo.operatingSystemVersionString,
                operation: operation,
                errorCode: info.code,
                userMessage: info.userMessage,
                technicalMessage: info.technicalMessage,
                userNote: userNote
            )
        )
    }

    private static func safeDetail(_ error: Error) -> String {
        if let localized = error as? LocalizedError,
           let description = localized.errorDescription,
           !description.isEmpty {
            return description
        }
        let description = String(describing: error)
        return description.isEmpty ? "Unknown iOS error" : description
    }
}

enum NostrErrorReporter {
    static var recipientNprofile: String {
        Bundle.main.object(forInfoDictionaryKey: "NostrErrorReportNprofile") as? String ?? ""
    }

    static var isConfigured: Bool {
        !recipientNprofile.isEmpty &&
            isValidNostrErrorReportNprofile(recipientNprofile: recipientNprofile)
    }

    static func send(_ error: AppError, userNote: String?) async throws -> NostrReportReceipt {
        guard isConfigured else { throw WalletError.networkError("Error reporting is not configured for this build.") }
        return try await sendNostrErrorReport(
            recipientNprofile: recipientNprofile,
            report: error.preparedReport(userNote: userNote)
        )
    }
}

enum WalletErrorMessage {
    static func classified(for error: Error) -> WalletMessage {
        AppError.from(error, operation: "unknown").walletMessage
    }

    static func message(for error: Error) -> String {
        classified(for: error).text
    }
}

extension ErrorSeverity {
    init(_ severity: FfiErrorSeverity) {
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

    fileprivate var info: FfiErrorInfo {
        let userMessage: String
        switch self {
        case .notInitialized:
            userMessage = "The wallet is still starting up. Try again in a moment."
        case .mintAlreadyExists:
            userMessage = "This mint is already in your wallet."
        case .invalidMnemonic:
            userMessage = "That seed phrase doesn't look right. Check the spelling and try again."
        case .insufficientBalance:
            userMessage = "Not enough balance."
        case .networkError(let detail):
            return getErrorInfo(code: nil, detail: detail)
        }
        return FfiErrorInfo(
            code: nil,
            userMessage: userMessage,
            technicalMessage: userMessage,
            category: .internal,
            severity: .error,
            retryable: true,
            terminal: false
        )
    }

    var errorDescription: String? { info.userMessage }
}
