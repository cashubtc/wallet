import XCTest
import Cdk
@testable import CashuWallet

@MainActor
final class FocusedMintQuoteMonitorTests: XCTestCase {
    func testDetailMonitoringIncludesSettledOffersButExcludesPaidInvoicesAndOutgoingPayments() {
        func transaction(
            status: WalletTransaction.TransactionStatus,
            invoice: String,
            type: WalletTransaction.TransactionType = .incoming
        ) -> WalletTransaction {
            WalletTransaction(
                id: "payment", amount: 21, type: type, kind: .lightning, date: Date(),
                status: status, invoice: invoice, quoteId: "quote"
            )
        }
        XCTAssertEqual(transaction(status: .pending, invoice: "lnbc1").mintQuoteIdForStatusRefresh, "quote")
        XCTAssertEqual(transaction(status: .expired, invoice: "lnbc1").mintQuoteIdForStatusRefresh, "quote")
        XCTAssertEqual(transaction(status: .completed, invoice: "LNO1").mintQuoteIdForStatusRefresh, "quote")
        XCTAssertNil(transaction(status: .completed, invoice: "lnbc1").mintQuoteIdForStatusRefresh)
        XCTAssertNil(transaction(status: .pending, invoice: "lno1", type: .outgoing).mintQuoteIdForStatusRefresh)
    }

    func testChecksOnlyDisplayedInvoiceImmediatelyAndStopsAfterIssuance() async {
        let monitor = FocusedMintQuoteMonitor()
        var ids: [String] = []
        var intervals: [Duration] = []
        await monitor.monitor(quoteID: "displayed", refresh: { id in
            XCTAssertTrue(monitor.isActive)
            ids.append(id)
            return self.quote(method: .bolt11, paid: 21, issued: ids.count == 1 ? 0 : 21)
        }, sleep: { interval in
            intervals.append(interval)
        })

        XCTAssertEqual(ids, ["displayed", "displayed"])
        XCTAssertEqual(intervals, [.seconds(2)])
        XCTAssertFalse(monitor.isActive)
    }

    func testPreviouslyPaidReusableOfferKeepsCheckingForLaterPayments() async {
        let monitor = FocusedMintQuoteMonitor()
        var checks = 0
        var intervals: [Duration] = []
        await monitor.monitor(quoteID: "displayed", refresh: { _ in
            checks += 1
            // An issued BOLT12 counter is a baseline, never a terminal state.
            return self.quote(method: .bolt12, paid: checks == 1 ? 10 : 31,
                              issued: checks == 1 ? 10 : 31)
        }, sleep: { interval in
            intervals.append(interval)
            if intervals.count == 2 { throw CancellationError() }
        })

        XCTAssertEqual(checks, 2)
        XCTAssertEqual(intervals, [.seconds(2), .seconds(2)])
        XCTAssertFalse(monitor.isActive)
    }

    func testCancellationReleasesFocusAndReopeningChecksImmediately() async {
        let monitor = FocusedMintQuoteMonitor()
        let sleeping = expectation(description: "Waiting between checks")
        var ids: [String] = []
        let task = Task {
            await monitor.monitor(quoteID: "first", refresh: { id in
                ids.append(id)
                return nil // Missing/temporarily unavailable quote keeps retrying.
            }, sleep: { _ in
                sleeping.fulfill()
                try await Task.sleep(for: .seconds(60))
            })
        }
        await fulfillment(of: [sleeping], timeout: 2)
        XCTAssertTrue(monitor.isActive)
        task.cancel()
        await task.value
        XCTAssertFalse(monitor.isActive)

        await monitor.monitor(quoteID: "second", refresh: { id in
            ids.append(id)
            return self.quote(method: .bolt11, paid: 21, issued: 21)
        }, sleep: { _ in XCTFail("Settled invoice must stop") })
        XCTAssertEqual(ids, ["first", "second"])
        XCTAssertFalse(monitor.isActive)
    }

    func testExpiredUnpaidInvoiceStopsButPaidUnissuedInvoiceKeepsRetrying() async {
        let monitor = FocusedMintQuoteMonitor()
        await monitor.monitor(quoteID: "displayed", refresh: { _ in
            self.quote(method: .bolt11, expiry: 1)
        }, sleep: { _ in XCTFail("Expired unpaid invoice must stop") })

        var checks = 0
        await monitor.monitor(quoteID: "displayed", refresh: { _ in
            checks += 1
            return self.quote(method: .bolt11, paid: 21, issued: checks == 1 ? 0 : 21, expiry: 1)
        }, sleep: { _ in })
        XCTAssertEqual(checks, 2)
    }

    func testOnchainDepositKeepsCheckingAfterExpiry() async {
        let monitor = FocusedMintQuoteMonitor()
        var checks = 0
        await monitor.monitor(quoteID: "displayed", refresh: { _ in
            checks += 1
            return self.quote(method: .onchain, paid: checks == 1 ? 0 : 21,
                              issued: checks == 1 ? 0 : 21, expiry: 1)
        }, sleep: { interval in XCTAssertEqual(interval, .seconds(10)) })
        XCTAssertEqual(checks, 2)
        XCTAssertFalse(monitor.isActive)
    }

    private func quote(
        method: PaymentMethodKind, paid: UInt64 = 0, issued: UInt64 = 0, expiry: UInt64? = nil
    ) -> MintQuoteInfo {
        MintQuoteInfo(
            id: "displayed", request: "request", amount: nil, isAmountless: true,
            paymentMethod: method, state: paid > issued ? .paid : paid > 0 ? .issued : .pending,
            expiry: expiry, createdAt: nil, amountPaid: paid, amountIssued: issued
        )
    }
}

final class MintEventScheduleTests: XCTestCase {
    private func time(_ seconds: TimeInterval) -> Date { Date(timeIntervalSince1970: seconds) }

    func testSubscribeGraceThenExponentialFallback() {
        var schedule = MintEventSchedule(now: time(0))
        XCTAssertFalse(schedule.due(now: time(9), subscribed: false, event: false, retryAt: nil, failed: false))
        XCTAssertTrue(schedule.due(now: time(10), subscribed: false, event: false, retryAt: nil, failed: false))
        schedule.checked(now: time(10), subscribed: false)
        XCTAssertFalse(schedule.due(now: time(14), subscribed: false, event: false, retryAt: nil, failed: false))
        XCTAssertTrue(schedule.due(now: time(15), subscribed: false, event: false, retryAt: nil, failed: false))
        schedule.checked(now: time(15), subscribed: false)
        XCTAssertFalse(schedule.due(now: time(24), subscribed: false, event: false, retryAt: nil, failed: false))
        XCTAssertTrue(schedule.due(now: time(25), subscribed: false, event: false, retryAt: nil, failed: false))
        schedule.checked(now: time(25), subscribed: false)
        XCTAssertFalse(schedule.due(now: time(44), subscribed: false, event: false, retryAt: nil, failed: false))
    }

    func testSubscriptionUsesEventsAndSlowSafetyCheck() {
        var schedule = MintEventSchedule(now: time(0))
        XCTAssertTrue(schedule.due(now: time(1), subscribed: true, event: true, retryAt: nil, failed: false))
        schedule.checked(now: time(1), subscribed: true)
        XCTAssertFalse(schedule.due(now: time(60), subscribed: true, event: false, retryAt: nil, failed: false))
        XCTAssertTrue(schedule.due(now: time(61), subscribed: true, event: false, retryAt: nil, failed: false))
    }

    func testPushDuringBackoffRemainsDueWithoutAnotherEvent() {
        var schedule = MintEventSchedule(now: time(0))
        XCTAssertFalse(schedule.due(now: time(1), subscribed: true, event: true, retryAt: time(300), failed: true))
        XCTAssertFalse(schedule.due(now: time(299), subscribed: true, event: false, retryAt: time(300), failed: true))
        XCTAssertTrue(schedule.due(now: time(300), subscribed: true, event: false, retryAt: time(300), failed: true))
        schedule.checked(now: time(300), subscribed: true)
        XCTAssertFalse(schedule.due(now: time(301), subscribed: true, event: false, retryAt: nil, failed: false))
    }

    func testNewSubscriptionReconcilesImmediatelyAndStopsAppFallback() {
        var schedule = MintEventSchedule(now: time(0))
        schedule.checked(now: time(10), subscribed: false)
        XCTAssertTrue(schedule.due(now: time(11), subscribed: true, event: false, retryAt: nil, failed: false))
        schedule.checked(now: time(11), subscribed: true)
        XCTAssertFalse(schedule.due(now: time(15), subscribed: true, event: false, retryAt: nil, failed: false))
        XCTAssertFalse(schedule.due(now: time(16), subscribed: false, event: false, retryAt: nil, failed: false))
        XCTAssertTrue(schedule.due(now: time(21), subscribed: false, event: false, retryAt: nil, failed: false))
    }
}

@MainActor
final class MintEventCoordinatorTests: XCTestCase {
    private final class Socket: MintEventSubscription {
        var event = false
        var onDrain: (() -> Void)?
        var onStop: (() -> Void)?
        func drain() async throws -> Bool {
            defer { event = false; onDrain = nil }
            onDrain?()
            return event
        }
        func stop() async { onStop?() }
    }

    func testSharedWatchStopsOnBackgroundAndResubscribesOnResume() async {
        let firstCheck = expectation(description: "Initial reconciliation")
        let secondCheck = expectation(description: "Resume reconciliation")
        let stopped = expectation(description: "Socket stopped")
        var subscriptions = 0
        var checks = 0
        let coordinator = MintEventCoordinator(socketsEnabled: { true }, subscribe: { _ in
            subscriptions += 1
            let socket = Socket()
            if subscriptions == 1 { socket.onStop = { stopped.fulfill() } }
            return socket
        }, retry: { _ in (nil, false) }, reconcile: { _, _ in
            checks += 1
            (checks == 1 ? firstCheck : secondCheck).fulfill()
            return nil
        })
        let observer = Task { for await _ in coordinator.observe("quote") {} }
        defer { observer.cancel() }
        coordinator.ensure("quote")
        await fulfillment(of: [firstCheck], timeout: 2)
        XCTAssertEqual(subscriptions, 1)
        coordinator.stop()
        await fulfillment(of: [stopped], timeout: 2)
        XCTAssertEqual(checks, 1)
        coordinator.start()
        await fulfillment(of: [secondCheck], timeout: 2)
        XCTAssertEqual(subscriptions, 2)
        coordinator.stop(clear: true)
    }

    func testEventDuringBackoffIsReconciledAfterDeadlineWithoutAnotherEvent() async {
        let consumed = expectation(description: "Event consumed during backoff")
        let reconciled = expectation(description: "Retry after deadline")
        let socket = Socket()
        socket.event = true
        socket.onDrain = { consumed.fulfill() }
        var deadline = Date.distantFuture
        var failed = true
        var checks = 0
        let coordinator = MintEventCoordinator(socketsEnabled: { true }, subscribe: { _ in socket },
            retry: { _ in (failed ? deadline : nil, failed) }, reconcile: { _, _ in
                checks += 1
                failed = false
                reconciled.fulfill()
                return nil
            })
        coordinator.ensure("quote")
        await fulfillment(of: [consumed], timeout: 2)
        XCTAssertEqual(checks, 0)
        deadline = .distantPast
        await fulfillment(of: [reconciled], timeout: 2)
        XCTAssertEqual(checks, 1)
        coordinator.stop(clear: true)
    }
}

// Native end-to-end coverage: production WalletManager, released FFI, real SQLite
// and mint proofs. Only the Lightning payment backend and transport faults are fake.
@MainActor
final class MintEventLocalMintTests: XCTestCase {
    private var fixture = ""
    private var root = ""
    private var directory: URL!
    private var defaultsName = ""
    private var manager: WalletManager!
    private var oldSocketsSetting = true

    override func setUp() async throws {
        fixture = ProcessInfo.processInfo.environment["PAYMENT_FIXTURE_URL"] ?? ""
        try XCTSkipIf(fixture.isEmpty, "Set PAYMENT_FIXTURE_URL to run native mint event tests")
        root = "/sessions/" + (try await call("/sessions", method: "POST")["id"] as! String)
        directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let db = try LifecycleSafeWalletDatabase(filePath: directory.appendingPathComponent("wallet.db").path)
        let repo = try Cdk.WalletRepository(mnemonic: Cdk.generateMnemonic(), store: Cdk.customWalletStore(db: db))
        let url = fixture + root + "/mint/controlled"
        try await repo.createWallet(mintUrl: Cdk.MintUrl(url: url), unit: .sat, targetProofCount: nil)
        defaultsName = "mint-events-" + UUID().uuidString
        manager = WalletManager(walletStore: WalletStore(storage: UserDefaultsStorage(defaults: UserDefaults(suiteName: defaultsName)!)))
        manager.db = db
        manager.walletRepository = repo
        manager.isRuntimeReady = true
        let mint = MintInfo(url: url, name: "Test mint", isActive: true, balance: 0)
        manager.mints = [mint]
        manager.activeMint = mint
        oldSocketsSetting = SettingsManager.shared.useWebsockets
        SettingsManager.shared.useWebsockets = true
    }

    override func tearDown() async throws {
        manager?.stopPendingQuoteForegroundPolling()
        manager?.mintEvents.stop(clear: true)
        try await Task.sleep(for: .milliseconds(300))
        if !root.isEmpty { _ = try await call(root, method: "DELETE") }
        SettingsManager.shared.useWebsockets = oldSocketsSetting
        if !defaultsName.isEmpty { UserDefaults.standard.removePersistentDomain(forName: defaultsName) }
        manager = nil
        if let directory { try? FileManager.default.removeItem(at: directory) }
    }

    private func call(_ path: String, method: String = "GET", body: [String: Any]? = nil) async throws -> [String: Any] {
        var request = URLRequest(url: URL(string: fixture + path)!)
        request.httpMethod = method
        request.timeoutInterval = 10
        if let body {
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let (data, response) = try await URLSession.shared.data(for: request)
        XCTAssertEqual((response as? HTTPURLResponse)?.statusCode, 200)
        return try JSONSerialization.jsonObject(with: data) as! [String: Any]
    }
    private func transport(_ values: [String: Bool]) async throws { _ = try await call(root + "/transport", method: "POST", body: values) }
    private func records() async throws -> [[String: Any]] { try await call(root)["requests"] as! [[String: Any]] }
    private func checks(_ id: String) async throws -> Int {
        try await records().filter { $0["method"] as? String == "GET" && $0["path"] as? String == "/v1/mint/quote/bolt11/" + id }.count
    }
    private func issues() async throws -> [[String: Any]] {
        try await records().filter { $0["method"] as? String == "POST" && $0["path"] as? String == "/v1/mint/bolt11" && $0["signature_retry"] as? Bool != true }
    }
    private func sockets(_ kind: String) async throws -> Int {
        (try await call(root)["ws"] as! [[String: Any]]).filter { $0["kind"] as? String == kind }.count
    }
    private func until(seconds: Double = 15, _ condition: () async throws -> Bool) async throws {
        let deadline = Date().addingTimeInterval(seconds)
        while try await !condition() {
            guard Date() < deadline else { throw NSError(domain: "MintEventTestTimeout", code: 1) }
            try await Task.sleep(for: .milliseconds(100))
        }
    }
    private func pay(_ invoice: String) async throws { _ = try await call(root + "/pay/controlled", method: "POST", body: ["invoice": invoice]) }
    private func watch(_ id: String) -> Task<Void, Never> {
        Task { for await _ in manager.mintEvents.observe(id) {} }
    }
    private func verify(_ id: String, amount: UInt64) {
        XCTAssertEqual(manager.balancesByUnit["sat"], amount)
        XCTAssertEqual(manager.transactions.filter { $0.quoteId == id }.count, 1)
    }

    func testMeltOnlyRecoveryPreservesSettledAndRejectedPayments() async throws {
        // CDK's fake backend models external payment status; Nutshell's stock
        // FakeWallet can report an external invoice paid before executing it.
        let url = fixture + root + "/mint/cdk"
        try await manager.walletRepository!.createWallet(mintUrl: Cdk.MintUrl(url: url), unit: .sat, targetProofCount: nil)
        let mint = MintInfo(url: url, name: "Outgoing test mint", isActive: true, balance: 0)
        manager.mints.append(mint)
        manager.activeMint = mint
        let funded = try await manager.createMintQuote(amount: 100)
        try await until {
            _ = await self.manager.refreshPendingMintQuote(quoteId: funded.id, force: true)
            return self.manager.balancesByUnit["sat"] == 100
        }
        let wallet = try await manager.walletRepository!.getWallet(mintUrl: Cdk.MintUrl(url: manager.activeMint!.url), unit: .sat)
        for lostResponse in [false, true] {
            let invoice = try await call(root + "/invoice", method: "POST", body: ["amount": 21])["invoice"] as! String
            let quote = try await wallet.meltQuote(method: .bolt11, request: invoice, options: nil, extra: nil)
            _ = try await call(root + "/faults", method: "POST", body: ["method": "POST", "path": "/v1/melt/bolt11", "action": lostResponse ? "lose_response" : "reject"])
            let prepared = try await wallet.prepareMelt(quoteId: quote.id)
            do { _ = try await prepared.confirm() } catch { }
            await manager.syncPendingMeltQuotes()
            let after = try await wallet.totalBalance().value
            if lostResponse {
                let status = try await wallet.checkMeltQuoteStatus(quoteId: quote.id)
                XCTAssertEqual(status.state, .paid)
                let receipts = try await wallet.listTransactions(direction: .outgoing).filter { $0.quoteId == quote.id }
                XCTAssertEqual(receipts.count, 1)
                XCTAssertEqual(after + receipts[0].amount.value + receipts[0].fee.value, 100)
            } else {
                XCTAssertEqual(after, 100, "Rejected outgoing payment returns all spendable funds")
            }
            await manager.syncPendingMeltQuotes()
            let repeated = try await wallet.totalBalance().value
            XCTAssertEqual(repeated, after)
        }
    }

    func testPushReducesRequestsAndDuplicateEventsCreditOnce() async throws {
        try await transport(["enabled": true, "duplicate": true])
        let quote = try await manager.createMintQuote(amount: 42)
        let before = try await checks(quote.id)
        let legacy = Task {
            await FocusedMintQuoteMonitor().monitor(quoteID: quote.id) { id in
                await self.manager.refreshPendingMintQuote(quoteId: id, observingQuoteID: id)?.quote
            }
        }
        try await Task.sleep(for: .milliseconds(6500))
        legacy.cancel(); await legacy.value
        let baseline = try await checks(quote.id) - before
        let first = watch(quote.id), second = watch(quote.id)
        defer { first.cancel(); second.cancel() }
        try await until { try await self.sockets("ack") > 0 }
        try await Task.sleep(for: .seconds(1))
        let start = try await checks(quote.id)
        try await Task.sleep(for: .milliseconds(6500))
        let improved = try await checks(quote.id) - start
        XCTAssertGreaterThanOrEqual(baseline, 3)
        XCTAssertLessThanOrEqual(improved, 1)
        let subscriptions = try await sockets("subscribe")
        XCTAssertEqual(subscriptions, 1)
        XCTAssertEqual(manager.balancesByUnit["sat"] ?? 0, 0)
        let paidAt = Date()
        try await pay(quote.request)
        try await until(seconds: 5) { self.manager.balancesByUnit["sat"] == 42 }
        let latency = Date().timeIntervalSince(paidAt)
        try await Task.sleep(for: .milliseconds(500))
        verify(quote.id, amount: 42)
        let attempts = try await issues()
        XCTAssertEqual(attempts.count, 1)
        print("MINT_EVENT_EVIDENCE ios baseline_gets=\(baseline) watcher_gets=\(improved) payment_seconds=\(latency)")
    }

    func testFailedIssuanceRetainsBackoffAcrossBackgroundResume() async throws {
        try await transport(["enabled": true, "duplicate": true])
        let quote = try await manager.createMintQuote(amount: 23)
        _ = try await call(root + "/faults", method: "POST", body: ["method": "POST", "path": "/v1/mint/bolt11", "action": "reject", "remaining": -1])
        let observer = watch(quote.id)
        defer { observer.cancel() }
        try await until { try await self.sockets("ack") > 0 }
        try await pay(quote.request)
        try await until { self.manager.walletStore.loadMintQuoteSchedules()[quote.id]?.consecutiveFailures == 1 }
        let firstFailure = Date().timeIntervalSince1970
        let firstDeadline = manager.walletStore.loadMintQuoteSchedules()[quote.id]!.nextAttemptAt
        XCTAssertTrue((4.5...5.1).contains(firstDeadline - firstFailure))
        let attemptsAfterFailure = try await issues().count
        XCTAssertEqual(manager.balancesByUnit["sat"] ?? 0, 0)
        manager.stopPendingQuoteForegroundPolling()
        try await Task.sleep(for: .milliseconds(500))
        manager.startPendingQuoteForegroundPolling()
        try await Task.sleep(for: .seconds(1))
        let resumedAttempts = try await issues().count
        XCTAssertEqual(resumedAttempts, attemptsAfterFailure, "Resume/events cannot bypass persisted backoff")
        try await until { self.manager.walletStore.loadMintQuoteSchedules()[quote.id]?.consecutiveFailures == 2 }
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince1970, firstDeadline)
        let secondFailure = Date().timeIntervalSince1970
        let secondDeadline = manager.walletStore.loadMintQuoteSchedules()[quote.id]!.nextAttemptAt
        XCTAssertTrue((14.5...15.1).contains(secondDeadline - secondFailure))
        _ = try await call(root + "/faults", method: "DELETE")
        try await until(seconds: 25) { self.manager.balancesByUnit["sat"] == 23 }
        XCTAssertGreaterThanOrEqual(Date().timeIntervalSince1970, secondDeadline)
        let successes = try await issues().filter { $0["status"] as? Int == 200 }.count
        XCTAssertEqual(successes, 1)
        verify(quote.id, amount: 23)
        print("MINT_EVENT_EVIDENCE ios retry_delays_seconds=\(firstDeadline - firstFailure),\(secondDeadline - secondFailure)")
    }

    func testDisconnectAndMissedPushRecoverWithoutDuplicateCredit() async throws {
        try await transport(["enabled": true])
        let quote = try await manager.createMintQuote(amount: 31)
        let observer = watch(quote.id)
        defer { observer.cancel() }
        try await until { try await self.sockets("ack") > 0 }
        try await transport(["disconnect": true])
        try await until { try await self.sockets("connected") >= 2 }
        manager.stopPendingQuoteForegroundPolling()
        try await Task.sleep(for: .milliseconds(750))
        try await pay(quote.request)
        try await Task.sleep(for: .milliseconds(750))
        XCTAssertEqual(manager.balancesByUnit["sat"] ?? 0, 0)
        manager.startPendingQuoteForegroundPolling()
        try await until { self.manager.balancesByUnit["sat"] == 31 }
        verify(quote.id, amount: 31)
        let attempts = try await issues()
        XCTAssertEqual(attempts.count, 1)
    }

    func testUnsupportedWebsocketStillSettlesThroughReleasedSdkFallback() async throws {
        try await transport(["enabled": false])
        let quote = try await manager.createMintQuote(amount: 19)
        let observer = watch(quote.id)
        defer { observer.cancel() }
        try await until { try await self.sockets("attempt") > 0 }
        let connections = try await sockets("connected")
        XCTAssertEqual(connections, 0)
        try await pay(quote.request)
        try await until { self.manager.balancesByUnit["sat"] == 19 }
        verify(quote.id, amount: 19)
        let attempts = try await issues()
        XCTAssertEqual(attempts.count, 1)
    }
}
