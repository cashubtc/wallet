import Foundation
import Cdk

/// App-owned queue between CDK's synchronous FFI callback and wallet work.
/// Each instance is single-use; the listener slot supplies session ownership.
@MainActor
final class CDKCashuRequestInbox: CashuRequestInboxClient {
    private let inbox: any NostrInboxProtocol
    private let callback: Callback
    private let continuation: AsyncStream<NostrInboxEvent>.Continuation
    private let processor: Task<Void, Never>
    private var startup: Task<Void, Error>?
    private var stopped = false

    convenience init(
        secretKey: String,
        relays: [String],
        since: UInt64,
        onEvent: @escaping @MainActor @Sendable (NostrInboxEvent) async -> Void
    ) throws {
        let inbox = try NostrInbox(nostrSecretKey: secretKey, relays: relays, since: since)
        self.init(inbox: inbox, onEvent: onEvent)
    }

    init(
        inbox: any NostrInboxProtocol,
        onEvent: @escaping @MainActor @Sendable (NostrInboxEvent) async -> Void
    ) {
        self.inbox = inbox
        let (stream, continuation) = AsyncStream<NostrInboxEvent>.makeStream()
        self.continuation = continuation
        callback = Callback(continuation: continuation)
        processor = Task {
            for await event in stream {
                await onEvent(event)
            }
        }
    }

    func start() async throws {
        guard !stopped, startup == nil else { return }
        let inbox = inbox
        let callback = callback
        let task = Task { try await inbox.start(listener: callback) }
        startup = task
        try await task.value
        // CDK start arms a fresh cancellation token. Stop again after startup
        // settles so a stop racing startup cannot leave a live subscription.
        if stopped { inbox.stop() }
    }

    func stop() async {
        stopped = true
        continuation.finish()
        inbox.stop()
        _ = await startup?.result
        inbox.stop()
        // Never cancel an admitted receive: its mint outcome may be ambiguous.
        await processor.value
    }

    private final class Callback: NostrInboxListener {
        let continuation: AsyncStream<NostrInboxEvent>.Continuation

        init(continuation: AsyncStream<NostrInboxEvent>.Continuation) {
            self.continuation = continuation
        }

        func onEvent(event: NostrInboxEvent) {
            continuation.yield(event)
        }
    }
}
