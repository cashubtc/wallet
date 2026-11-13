import Foundation

struct ContactlessLimits: Codable, Equatable {
    var perTap: UInt64 = 1_000
    var daily: UInt64 = 10_000

    var isValid: Bool { daily > 0 && daily <= 2_100_000_000_000_000 && perTap <= daily }
}

struct ContactlessSpendState: Codable, Equatable {
    var limits = ContactlessLimits()
    var day: Int64 = 0
    var spent: UInt64 = 0

    mutating func record(_ amount: UInt64, day today: Int64) throws {
        guard limits.isValid, amount > 0 else { throw ContactlessPaymentError.invalidLimits }
        if today > day { day = today; spent = 0 }
        guard spent <= limits.daily, amount <= limits.daily - spent else {
            throw ContactlessPaymentError.dailyLimit
        }
        spent += amount
    }
}

enum ContactlessPaymentError: LocalizedError {
    case invalidLimits, dailyLimit, authentication, inactive, busy, storage

    var errorDescription: String? {
        switch self {
        case .invalidLimits: return "Choose a daily limit at least as large as the per-tap limit."
        case .dailyLimit: return "Daily contactless limit reached. Use Send to review another payment."
        case .authentication: return "Payment not authorized. Authenticate with your device to continue."
        case .inactive: return "Contactless payment stopped. Open and unlock the wallet to try again."
        case .busy: return "A contactless payment is already in progress."
        case .storage: return "Couldn't save contactless limits. No new payment was authorized."
        }
    }
}

@MainActor
final class ContactlessSpendPolicy {
    static let shared = ContactlessSpendPolicy()
    private let loadData: () -> Data?
    private let saveData: (Data) throws -> Void
    private let now: () -> Date
    private var authorizing = false

    init(defaults: UserDefaults = .standard, now: @escaping () -> Date = Date.init) {
        let key = "cashu.contactless.spendPolicy.v1"
        loadData = { defaults.data(forKey: key) }
        saveData = {
            defaults.set($0, forKey: key)
            guard defaults.synchronize() else { throw ContactlessPaymentError.storage }
        }
        self.now = now
    }

    init(load: @escaping () -> Data?, save: @escaping (Data) throws -> Void,
         now: @escaping () -> Date) {
        loadData = load; saveData = save; self.now = now
    }

    func state() throws -> ContactlessSpendState {
        guard let data = loadData() else { return ContactlessSpendState() }
        guard let state = try? JSONDecoder().decode(ContactlessSpendState.self, from: data),
              state.limits.isValid else { throw ContactlessPaymentError.storage }
        return state
    }

    func saveLimits(_ limits: ContactlessLimits) throws {
        guard limits.isValid else { throw ContactlessPaymentError.invalidLimits }
        var current = try state()
        current.limits = limits
        try saveData(JSONEncoder().encode(current))
    }

    func authorizeAndRecord(
        total: UInt64,
        isActive: () -> Bool,
        authenticate: () async -> Bool
    ) async throws {
        guard !authorizing else { throw ContactlessPaymentError.busy }
        authorizing = true
        defer { authorizing = false }
        try Task.checkCancellation()
        guard isActive() else { throw ContactlessPaymentError.inactive }
        var initial = try state()
        try initial.record(total, day: day())
        let needsAuthentication = total > initial.limits.perTap
        if needsAuthentication, !(await authenticate()) { throw ContactlessPaymentError.authentication }
        try Task.checkCancellation()
        guard isActive() else { throw ContactlessPaymentError.inactive }
        var current = try state()
        guard needsAuthentication || total <= current.limits.perTap else {
            throw ContactlessPaymentError.authentication
        }
        try current.record(total, day: day())
        try saveData(JSONEncoder().encode(current))
    }

    private func day() -> Int64 { Int64(now().timeIntervalSince1970 / 86_400) }
}

@MainActor
struct ContactlessSendCallbacks {
    let approve: (UInt64) async throws -> Void
    let deliver: (String) async throws -> Void
}

@MainActor
func completeContactlessSend<T>(
    approve: () async throws -> Void,
    confirm: () async throws -> T,
    cancel: @escaping () async throws -> Void,
    reclaim: @escaping () async throws -> Void,
    deliver: (T) async throws -> Void
) async throws -> T {
    var confirmationStarted = false
    do {
        try await approve()
        try Task.checkCancellation()
        confirmationStarted = true
        let token = try await confirm()
        try Task.checkCancellation()
        try await deliver(token)
        return token
    } catch {
        let cleanup = Task {
            if confirmationStarted { try? await reclaim() }
            else { try? await cancel() }
        }
        await cleanup.value
        throw error
    }
}
