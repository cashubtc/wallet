package com.cashu.me.Core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.cashudevkit.NostrInbox
import org.cashudevkit.NostrInboxEvent
import org.cashudevkit.NostrInboxInterface
import org.cashudevkit.NostrInboxListener

/** Single-use CDK adapter. FFI callbacks only enqueue; wallet work belongs to our processor. */
internal class CDKCashuRequestInbox(
    private val makeInbox: () -> NostrInboxInterface,
    private val onFailure: () -> Unit,
    private val onEvent: suspend (NostrInboxEvent) -> Unit,
) {
    constructor(
        secretKey: String,
        relays: List<String>,
        since: Long,
        onFailure: () -> Unit,
        onEvent: suspend (NostrInboxEvent) -> Unit,
    ) : this(
        makeInbox = { NostrInbox(secretKey, relays, since.coerceAtLeast(0).toULong()) },
        onFailure = onFailure,
        onEvent = onEvent,
    )

    private val lifecycle = SupervisorJob()
    private val scope = CoroutineScope(lifecycle + Dispatchers.IO)
    private val events = Channel<NostrInboxEvent>(Channel.UNLIMITED)
    private var inbox: NostrInboxInterface? = null
    private var startup: Job? = null
    private var stopped = false
    private val processor = scope.launch {
        for (event in events) {
            try {
                onEvent(event)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLogger.wallet.error("Nostr inbox processing failed; leaving event retryable", error)
                onFailure()
            }
        }
    }
    private val callback = object : NostrInboxListener {
        override fun onEvent(event: NostrInboxEvent) {
            events.trySend(event)
        }
    }

    @Synchronized
    fun start() {
        if (stopped || startup != null) return
        startup = scope.launch {
            try {
                val candidate = makeInbox()
                val accepted = synchronized(this@CDKCashuRequestInbox) {
                    inbox = candidate
                    !stopped
                }
                if (accepted) candidate.start(callback)
            } catch (error: Exception) {
                AppLogger.network.error("Nostr inbox start failed", error)
                onFailure()
            } finally {
                synchronized(this@CDKCashuRequestInbox) {
                    // CDK start resets its stop token. Repeat stop after startup
                    // settles, including when stop raced the first start call.
                    if (stopped) inbox?.stop()
                }
            }
        }
    }

    @Synchronized
    fun stop() {
        stopped = true
        events.close()
        inbox?.stop()
    }

    suspend fun stopAndJoin() {
        stop()
        val starting = synchronized(this) { startup }
        starting?.join()
        synchronized(this) { inbox?.stop() }
        // Drain, rather than cancel, an admitted mint redemption. Its complete
        // wallet workflow must settle before the database can be replaced.
        processor.join()
        lifecycle.complete()
    }
}
