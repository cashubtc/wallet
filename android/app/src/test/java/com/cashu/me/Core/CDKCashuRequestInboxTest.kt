package com.cashu.me.Core

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cashudevkit.NostrInboxEvent
import org.cashudevkit.NostrInboxInterface
import org.cashudevkit.NostrInboxListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CDKCashuRequestInboxTest {
    private class Inbox : NostrInboxInterface {
        val listening = CompletableDeferred<NostrInboxListener>()
        val releaseStart = CompletableDeferred<Unit>()
        val stops = AtomicInteger()
        override fun pubkey() = "identity"
        override suspend fun start(listener: NostrInboxListener) {
            listening.complete(listener)
            releaseStart.await()
        }
        override fun stop() { stops.incrementAndGet() }
    }

    private fun event(id: String = "wrap") = NostrInboxEvent(
        wrapId = id, wrapCreatedAt = 1uL, senderPubkey = "sender", rumorId = "rumor",
        rumorKind = 14u, rumorContent = "payload", rumorCreatedAt = 2uL, rumorTags = emptyList(),
    )

    @Test
    fun callbackDoesNotWaitForClaimAndStopDrainsAdmittedWork() = runBlocking {
        withTimeout(5_000) {
            val inbox = Inbox()
            val entered = CompletableDeferred<Unit>()
            val finish = CompletableDeferred<Unit>()
            val adapter = CDKCashuRequestInbox({ inbox }, { error("unexpected failure") }) {
                entered.complete(Unit)
                finish.await()
            }
            adapter.start()
            val callback = inbox.listening.await()
            inbox.releaseStart.complete(Unit)
            callback.onEvent(event()) // returns even while the consumer is blocked
            entered.await()
            adapter.stop()
            val draining = async { adapter.stopAndJoin() }
            assertFalse(draining.isCompleted)
            finish.complete(Unit)
            draining.await()
            assertTrue(inbox.stops.get() > 0)
        }
    }

    @Test
    fun stopDuringStartRejectsLateCallbacksAndStopsFreshCdkRun() = runBlocking {
        withTimeout(5_000) {
            val inbox = Inbox()
            val received = AtomicInteger()
            val adapter = CDKCashuRequestInbox({ inbox }, { error("unexpected failure") }) {
                received.incrementAndGet()
            }
            adapter.start()
            val callback = inbox.listening.await()
            adapter.stop()
            callback.onEvent(event("late-old-wallet"))
            val stopping = async { adapter.stopAndJoin() }
            inbox.releaseStart.complete(Unit)
            stopping.await()
            assertTrue(inbox.stops.get() >= 2)
            assertEquals(0, received.get())
            adapter.start() // retired instances cannot reopen
            callback.onEvent(event("after-retirement"))
            assertEquals(0, received.get())
        }
    }

    @Test
    fun reconnectDuplicatesUseWrapIdAndPersistAcrossNewAdapters() = runBlocking {
        withTimeout(5_000) {
            var stored = emptyList<String>()
            var claims = 0
            var previous: NostrInboxListener? = null
            suspend fun runDelivery() {
                val tracker = ProcessedNip17EventTracker({ stored }, { stored = it })
                tracker.reload()
                val inbox = Inbox()
                val adapter = CDKCashuRequestInbox({ inbox }, { error("unexpected failure") }) { event ->
                    if (tracker.begin(event.wrapId)) {
                        claims++
                        tracker.finish(event.wrapId, terminalOutcome = true)
                    }
                }
                adapter.start()
                val callback = inbox.listening.await()
                inbox.releaseStart.complete(Unit)
                previous?.onEvent(event("late-old-wallet"))
                repeat(3) { callback.onEvent(event()) }
                // Closing intake drains already queued events.
                adapter.stopAndJoin()
                previous = callback
            }
            runDelivery()
            runDelivery()
            assertEquals(1, claims)
            assertEquals(listOf("wrap"), stored)
        }
    }
}
