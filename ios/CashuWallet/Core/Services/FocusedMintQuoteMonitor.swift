import Foundation

/// The visible payment code owns this loop. Cancelling its view task releases
/// the focus, allowing ordinary wallet maintenance to resume.
@MainActor
final class FocusedMintQuoteMonitor {
    private var sessions: Set<UUID> = []
    var isActive: Bool { !sessions.isEmpty }

    func monitor(
        quoteID: String,
        refresh: (String) async -> MintQuoteInfo?,
        sleep: (Duration) async throws -> Void = { try await Task.sleep(for: $0) }
    ) async {
        guard !Task.isCancelled else { return }
        let session = UUID()
        sessions.insert(session)
        defer { sessions.remove(session) }

        while !Task.isCancelled {
            // Direct reconciliation bypasses the passive batch/age schedule,
            // but still respects persisted failure backoff in the reconciler.
            let quote = await refresh(quoteID)
            guard !Task.isCancelled else { return }
            if let quote, quote.paymentMethod != .bolt12,
               quote.hasSettledPayment ||
                (quote.paymentMethod == .bolt11 && quote.isExpired && quote.mintableAmount == 0) {
                return
            }
            do {
                try await sleep(quote?.paymentMethod == .onchain ? .seconds(10) : .seconds(2))
            } catch {
                return
            }
        }
    }
}

/// Subscription existence is not socket readiness. Keep a safety check with a handle.
/// Transport events never replace the persisted issuance retry deadline.
struct MintEventSchedule {
    private var nextCheck: Date
    private var fallbackDelay: TimeInterval = 5
    private var wasSubscribed = false
    private var dirty = false

    init(now: Date) { nextCheck = now.addingTimeInterval(10) }

    mutating func due(now: Date, subscribed: Bool, event: Bool, retryAt: Date?, failed: Bool) -> Bool {
        dirty = dirty || event || (subscribed && !wasSubscribed)
        if !subscribed && wasSubscribed { nextCheck = min(nextCheck, now.addingTimeInterval(5)) }
        wasSubscribed = subscribed
        if let retryAt, retryAt > now { return false }
        return failed || dirty || now >= nextCheck
    }

    mutating func checked(now: Date, subscribed: Bool) {
        dirty = false
        nextCheck = now.addingTimeInterval(subscribed ? 60 : fallbackDelay)
        fallbackDelay = subscribed ? 5 : min(fallbackDelay * 2, 60)
    }
}

@MainActor
protocol MintEventSubscription: AnyObject {
    func drain() async throws -> Bool
    func stop() async
}

/// Observers share a watch. Unobserved quotes retain bounded passive recovery.
@MainActor
final class MintEventCoordinator {
    private final class Watch {
        var task: Task<Void, Never>?
        var latest: MintQuoteReconciliationResult?
        var complete = false
        var observers: [UUID: AsyncStream<MintQuoteReconciliationResult>.Continuation] = [:]
    }
    private var watches: [String: Watch] = [:]
    private var active = true
    private var pendingChecks: [String] = []
    private var nextPassiveCheck = Date.distantPast

    private func claimCheck(_ id: String, visible: Bool, now: Date) -> Bool {
        if !pendingChecks.contains(id) { pendingChecks.append(id) }
        guard visible || (now >= nextPassiveCheck && pendingChecks.first == id) else { return false }
        pendingChecks.removeAll { $0 == id }
        if !visible { nextPassiveCheck = now.addingTimeInterval(5) }
        return true
    }
    var socketsEnabled: () -> Bool
    var subscribe: (String) async throws -> any MintEventSubscription
    var retry: (String) -> (Date?, Bool)
    var reconcile: (String, Bool) async -> MintQuoteReconciliationResult?

    init(
        socketsEnabled: @escaping () -> Bool,
        subscribe: @escaping (String) async throws -> any MintEventSubscription,
        retry: @escaping (String) -> (Date?, Bool),
        reconcile: @escaping (String, Bool) async -> MintQuoteReconciliationResult?
    ) {
        self.socketsEnabled = socketsEnabled
        self.subscribe = subscribe
        self.retry = retry
        self.reconcile = reconcile
    }

    func ensure(_ id: String) {
        guard active else { return }
        let watch = watches[id] ?? Watch()
        guard watch.task == nil, !watch.complete else { return }
        watches[id] = watch
        watch.task = Task { [weak self, weak watch] in
            guard let self, let watch else { return }
            var subscription: (any MintEventSubscription)?
            var nextSubscribe = Date.distantPast
            var subscribeDelay: TimeInterval = 2
            var schedule = MintEventSchedule(now: Date())
            while !Task.isCancelled {
                let now = Date()
                if !self.socketsEnabled() {
                    await subscription?.stop()
                    subscription = nil
                } else if subscription == nil && now >= nextSubscribe {
                    do {
                        subscription = try await self.subscribe(id)
                        subscribeDelay = 2
                    } catch {
                        nextSubscribe = now.addingTimeInterval(subscribeDelay)
                        subscribeDelay = min(subscribeDelay * 2, 30)
                    }
                }
                var event = false
                do { event = try await subscription?.drain() ?? false }
                catch {
                    await subscription?.stop()
                    subscription = nil
                    nextSubscribe = now.addingTimeInterval(subscribeDelay)
                    subscribeDelay = min(subscribeDelay * 2, 30)
                }
                guard !Task.isCancelled else { break }
                let subscribed = subscription != nil
                let (deadline, failed) = self.retry(id)
                let visible = !watch.observers.isEmpty
                if schedule.due(now: now, subscribed: subscribed, event: event, retryAt: deadline, failed: failed),
                   self.claimCheck(id, visible: visible, now: now) {
                    let result = await self.reconcile(id, visible)
                    guard !Task.isCancelled else { break }
                    schedule.checked(now: Date(), subscribed: subscribed)
                    if let result {
                        watch.latest = result
                        watch.observers.values.forEach { $0.yield(result) }
                        let quote = result.quote
                        if quote.paymentMethod != .bolt12 && (quote.hasSettledPayment ||
                            (quote.paymentMethod == .bolt11 && quote.isExpired && quote.mintableAmount == 0)) {
                            watch.complete = true
                            break
                        }
                    }
                }
                do { try await Task.sleep(for: .milliseconds(250)) } catch { break }
            }
            await subscription?.stop()
        }
    }

    func observe(_ id: String) -> AsyncStream<MintQuoteReconciliationResult> {
        let watch = watches[id] ?? Watch()
        watches[id] = watch
        return AsyncStream(bufferingPolicy: .bufferingNewest(1)) { continuation in
            let observer = UUID()
            watch.observers[observer] = continuation
            ensure(id)
            if let latest = watch.latest {
                continuation.yield(MintQuoteReconciliationResult(observed: latest.quote, retryStatus: latest.retryStatus))
            }
            continuation.onTermination = { [weak self] _ in
                Task { @MainActor in
                    guard let self, let watch = self.watches[id] else { return }
                    watch.observers.removeValue(forKey: observer)
                    if watch.observers.isEmpty {
                        watch.task?.cancel()
                        watch.task = nil
                        self.pendingChecks.removeAll { $0 == id }
                    }
                }
            }
        }
    }

    func isObserved(_ id: String) -> Bool { active && !(watches[id]?.observers.isEmpty ?? true) }

    func start() {
        active = true
        watches.filter { !$0.value.observers.isEmpty }.keys.forEach { ensure($0) }
    }
    func stop(clear: Bool = false) {
        active = false
        for watch in watches.values {
            watch.task?.cancel()
            watch.task = nil
            if clear { watch.observers.values.forEach { $0.finish() } }
        }
        pendingChecks.removeAll()
        nextPassiveCheck = .distantPast
        if clear { watches.removeAll() }
    }
}
