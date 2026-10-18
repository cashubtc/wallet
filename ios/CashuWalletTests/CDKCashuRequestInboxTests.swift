import XCTest
import Cdk
import Network
@testable import CashuWallet

@MainActor
final class CDKCashuRequestInboxTests: XCTestCase {
    private final class Inbox: NostrInboxProtocol, @unchecked Sendable {
        private let lock = NSLock()
        private var callback: (any NostrInboxListener)?
        private var stops = 0
        let ready: XCTestExpectation
        let stopping: XCTestExpectation
        let startup: AsyncStream<Void>
        let release: AsyncStream<Void>.Continuation

        init(ready: XCTestExpectation, stopping: XCTestExpectation? = nil) {
            self.ready = ready
            self.stopping = stopping ?? XCTestExpectation(description: "stop entered")
            (startup, release) = AsyncStream<Void>.makeStream()
        }
        func pubkey() -> String { "identity" }
        func start(listener: any NostrInboxListener) async throws {
            lock.withLock { callback = listener }
            ready.fulfill()
            for await _ in startup { break }
        }
        func stop() {
            let first = lock.withLock { stops += 1; return stops == 1 }
            if first { stopping.fulfill() }
        }
        var stopCount: Int { lock.withLock { stops } }
        func emit(_ event: NostrInboxEvent) {
            let listener = lock.withLock { callback }
            listener?.onEvent(event: event)
        }
    }

    private func event(_ id: String = "wrap") -> NostrInboxEvent {
        NostrInboxEvent(wrapId: id, wrapCreatedAt: 1, senderPubkey: "sender",
                        rumorId: "rumor", rumorKind: 14, rumorContent: "payload",
                        rumorCreatedAt: 2, rumorTags: [])
    }

    func testStopDuringStartupClosesQueueAndStopsFreshCDKRun() async throws {
        let ready = expectation(description: "start entered")
        let inbox = Inbox(ready: ready)
        var deliveries = 0
        let adapter = CDKCashuRequestInbox(inbox: inbox) { _ in deliveries += 1 }
        let starting = Task { try await adapter.start() }
        await fulfillment(of: [ready], timeout: 2)
        let stopping = Task { await adapter.stop() }
        // Give stop ownership before delivering the old callback.
        await fulfillment(of: [inbox.stopping], timeout: 2)
        inbox.emit(event("old-wallet"))
        inbox.release.yield(())
        try await starting.value
        await stopping.value
        XCTAssertGreaterThanOrEqual(inbox.stopCount, 2)
        XCTAssertEqual(deliveries, 0)
        try await adapter.start()
        inbox.emit(event("retired"))
        XCTAssertEqual(deliveries, 0)
    }

    func testCallbacksReturnWhileClaimSuspendsAndStopDrainsWork() async throws {
        let ready = expectation(description: "start entered")
        let entered = expectation(description: "claim entered")
        let (finish, release) = AsyncStream<Void>.makeStream()
        let inbox = Inbox(ready: ready)
        var completed = false
        let adapter = CDKCashuRequestInbox(inbox: inbox) { _ in
            entered.fulfill()
            for await _ in finish { break }
            completed = true
        }
        let starting = Task { try await adapter.start() }
        await fulfillment(of: [ready], timeout: 2)
        inbox.release.yield(())
        try await starting.value
        inbox.emit(event())
        await fulfillment(of: [entered], timeout: 2)
        let stopping = Task { await adapter.stop() }
        await fulfillment(of: [inbox.stopping], timeout: 2)
        XCTAssertFalse(completed)
        release.yield(())
        await stopping.value
        XCTAssertTrue(completed)
    }

    func testReconnectDuplicatesArePersistedByWrapIDAcrossAdapters() async throws {
        var stored: [String] = []
        var claims = 0
        var previous: Inbox?
        for _ in 0..<2 {
            let ready = expectation(description: "start entered")
            let inbox = Inbox(ready: ready)
            let tracker = ProcessedNIP17EventTracker(load: { stored }, save: { stored = $0 })
            tracker.reload()
            let adapter = CDKCashuRequestInbox(inbox: inbox) { event in
                guard tracker.begin(event.wrapId) else { return }
                claims += 1
                tracker.finish(event.wrapId, terminalOutcome: true)
            }
            let starting = Task { try await adapter.start() }
            await fulfillment(of: [ready], timeout: 2)
            inbox.release.yield(())
            try await starting.value
            previous?.emit(event("late-old-wallet"))
            for _ in 0..<3 { inbox.emit(event()) }
            await adapter.stop()
            previous = inbox
        }
        XCTAssertEqual(claims, 1)
        XCTAssertEqual(stored, ["wrap"])
    }
}

/// Real CDK boundary tests use independently generated, signed NIP-44/NIP-59
/// fixtures, not the deleted app decoder as an oracle.
@MainActor
final class CDKNostrInboxInteropTests: XCTestCase {
    private func fixture() throws -> [String: Any] {
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "nostr-inbox", withExtension: "json"))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
    }

    func testNIP44IndependentVectorAndInvalidPayloads() throws {
        let f = try fixture()
        let secret = try XCTUnwrap(f["recipient_secret"] as? String)
        let sender = try XCTUnwrap(f["sender_public"] as? String)
        let payload = try XCTUnwrap(f["ciphertext"] as? String)
        let plaintext = try XCTUnwrap(f["plaintext"] as? String)
        XCTAssertThrowsError(try NostrInbox(nostrSecretKey: "invalid", relays: ["wss://relay.example.com"], since: nil))
        XCTAssertThrowsError(try NostrInbox(nostrSecretKey: secret, relays: [], since: nil))
        XCTAssertEqual(try nip44Decrypt(nostrSecretKey: secret, senderPubkey: sender, payload: payload), plaintext)
        XCTAssertThrowsError(try nip44Decrypt(nostrSecretKey: f["wrong_secret"] as! String, senderPubkey: sender, payload: payload))
        XCTAssertThrowsError(try nip44Decrypt(nostrSecretKey: secret, senderPubkey: sender, payload: "not-base64"))
        var tampered = try XCTUnwrap(Data(base64Encoded: payload))
        tampered[tampered.count - 1] ^= 1
        XCTAssertThrowsError(try nip44Decrypt(nostrSecretKey: secret, senderPubkey: sender, payload: tampered.base64EncodedString()))
        let encrypted = try nip44Encrypt(nostrSecretKey: secret, recipientPubkey: sender, plaintext: plaintext)
        let senderSecret = String(repeating: "0", count: 63) + "1"
        XCTAssertEqual(try nip44Decrypt(nostrSecretKey: senderSecret,
            senderPubkey: nostrGetPubkey(nostrSecretKey: secret), payload: encrypted), plaintext)
    }

    func testRealCDKRejectsInvalidWrapsAndResubscribesWithFixedLookback() async throws {
        let f = try fixture()
        let ready = expectation(description: "relay ready")
        let delivered = expectation(description: "valid wraps from both connections")
        delivered.expectedFulfillmentCount = 2
        let relay = try InboxTestRelay(fixtures: f, ready: ready)
        defer { relay.stop() }
        await fulfillment(of: [ready], timeout: 3)
        let port = try XCTUnwrap(relay.port)
        var received: [NostrInboxEvent] = []
        let inbox = try CDKCashuRequestInbox(secretKey: f["recipient_secret"] as! String,
            relays: ["ws://127.0.0.1:\(port)"], since: 1_699_500_000) { event in
                received.append(event)
                if received.count <= 2 { delivered.fulfill() }
            }
        try await inbox.start()
        await fulfillment(of: [delivered], timeout: 30)
        await inbox.stop()
        let expected = ["valid", "valid_second"].map { (f[$0] as! [String: Any])["id"] as! String }
        XCTAssertEqual(Set(received.map(\.wrapId)), Set(expected))
        XCTAssertTrue(received.allSatisfy { $0.rumorKind == 14 && $0.rumorContent == f["payload"] as? String })
        XCTAssertGreaterThanOrEqual(relay.filters.count, 2)
        XCTAssertTrue(relay.filters.allSatisfy { ($0["since"] as? Int) == 1_699_500_000 })
        XCTAssertTrue(relay.filters.allSatisfy { ($0["kinds"] as? [Int]) == [1059] })
    }
}

/// A loopback WebSocket relay which drops its first subscription to exercise
/// CDK's actual reconnect path. All mutable state belongs to its serial queue.
private final class InboxTestRelay: @unchecked Sendable {
    private let queue = DispatchQueue(label: "inbox-test-relay")
    private let listener: NWListener
    private var connections: [NWConnection] = []
    private var subscriptions: [[String: Any]] = []
    private let fixtures: [String: Any]
    var port: UInt16? { listener.port?.rawValue }
    var filters: [[String: Any]] { queue.sync { subscriptions } }

    init(fixtures: [String: Any], ready: XCTestExpectation) throws {
        self.fixtures = fixtures
        let parameters = NWParameters.tcp
        let websocket = NWProtocolWebSocket.Options()
        websocket.autoReplyPing = true
        parameters.defaultProtocolStack.applicationProtocols.insert(websocket, at: 0)
        parameters.requiredLocalEndpoint = .hostPort(host: "127.0.0.1", port: .any)
        listener = try NWListener(using: parameters)
        listener.stateUpdateHandler = { state in
            if case .ready = state { ready.fulfill() }
        }
        listener.newConnectionHandler = { [weak self] connection in
            guard let self else { return }
            self.connections.append(connection)
            connection.start(queue: self.queue)
            self.receive(connection)
        }
        listener.start(queue: queue)
    }

    private func receive(_ connection: NWConnection) {
        connection.receiveMessage { [weak self] data, _, _, error in
            guard let self, error == nil else { return }
            if let data,
               let message = try? JSONSerialization.jsonObject(with: data) as? [Any],
               message.first as? String == "REQ", message.count >= 3,
               let id = message[1] as? String, let filter = message[2] as? [String: Any] {
                self.subscriptions.append(filter)
                let first = self.subscriptions.count == 1
                let names = first ? ["malformed", "invalid_mac", "invalid_seal", "valid"] : ["valid", "valid_second"]
                for name in names { self.send(["EVENT", id, self.fixtures[name]!], to: connection) }
                self.send(["EOSE", id], to: connection)
                if first {
                    self.queue.asyncAfter(deadline: .now() + 0.5) { connection.cancel() }
                }
            }
            self.receive(connection)
        }
    }

    private func send(_ message: [Any], to connection: NWConnection) {
        guard let data = try? JSONSerialization.data(withJSONObject: message) else { return }
        let metadata = NWProtocolWebSocket.Metadata(opcode: .text)
        let context = NWConnection.ContentContext(identifier: "nostr", metadata: [metadata])
        connection.send(content: data, contentContext: context, isComplete: true, completion: .idempotent)
    }

    func stop() {
        queue.sync {
            listener.cancel()
            connections.forEach { $0.cancel() }
        }
    }
}
