package com.cashu.me.Core

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy

class MintEventScheduleTest {
    @Test fun `tries subscription before fallback and polls exponentially`() {
        val schedule = MintEventSchedule(0)
        assertFalse(schedule.due(9_999, false, false, null, false))
        assertTrue(schedule.due(10_000, false, false, null, false))
        schedule.checked(10_000, false)
        assertFalse(schedule.due(14_999, false, false, null, false))
        assertTrue(schedule.due(15_000, false, false, null, false))
        schedule.checked(15_000, false)
        assertFalse(schedule.due(24_999, false, false, null, false))
        assertTrue(schedule.due(25_000, false, false, null, false))
        schedule.checked(25_000, false)
        assertFalse(schedule.due(44_999, false, false, null, false))
    }

    @Test fun `open subscription suppresses routine polls but keeps safety check`() {
        val schedule = MintEventSchedule(0)
        assertTrue(schedule.due(100, true, true, null, false))
        schedule.checked(100, true)
        assertFalse(schedule.due(59_999, true, false, null, false))
        assertTrue(schedule.due(60_100, true, false, null, false))
    }

    @Test fun `push during persisted backoff is retained until the deadline`() {
        val schedule = MintEventSchedule(0)
        assertFalse(schedule.due(100, true, true, 300_000, true))
        assertFalse(schedule.due(299_999, true, false, 300_000, true))
        assertTrue(schedule.due(300_000, true, false, 300_000, true))
        schedule.checked(300_000, true)
        assertFalse(schedule.due(300_001, true, false, null, false))
    }

    @Test fun `issuance retries continue on idle subscription without another event`() {
        val schedule = MintEventSchedule(0)
        schedule.checked(0, true)
        assertFalse(schedule.due(1_000, true, false, 15_000, true))
        assertTrue(schedule.due(15_000, true, false, 15_000, true))
    }

    @Test fun `new subscription immediately reconciles and stops fallback polling`() {
        val schedule = MintEventSchedule(0)
        schedule.checked(10_000, false)
        assertTrue(schedule.due(11_000, true, false, null, false))
        schedule.checked(11_000, true)
        assertFalse(schedule.due(15_000, true, false, null, false))
        assertFalse(schedule.due(16_000, false, false, null, false))
        assertTrue(schedule.due(21_000, false, false, null, false))
    }
}


@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MintEventCoordinatorTest {
    private class Socket : MintEventSubscription {
        var event = false
        var stops = 0
        override suspend fun drain(): Boolean = event.also { event = false }
        override suspend fun stop() { stops++ }
    }

    @Test fun `collectors share a subscription and register before reconciliation`() = kotlinx.coroutines.test.runTest {
        var subscriptions = 0
        var emissions = 0
        val coordinator = MintEventCoordinator(backgroundScope, { true }, { subscriptions++; Socket() },
            { null to false }, { _, visible -> assertTrue(visible); MintQuoteSyncResult() },
            { testScheduler.currentTime })
        repeat(2) {
            backgroundScope.launch { coordinator.observe("quote").first(); emissions++ }
        }
        runCurrent()
        assertEquals(1, subscriptions)
        assertEquals(2, emissions)
        coordinator.stop(clear = true)
    }

    @Test fun `shared watch stops on background and resubscribes on resume`() = kotlinx.coroutines.test.runTest {
        val sockets = mutableListOf<Socket>()
        var checks = 0
        val coordinator = MintEventCoordinator(backgroundScope, { true }, {
            Socket().also(sockets::add)
        }, { null to false }, { _, _ -> checks++; MintQuoteSyncResult() }, { testScheduler.currentTime })
        backgroundScope.launch { coordinator.observe("quote").collect {} }
        coordinator.ensure("quote")
        runCurrent()
        assertEquals(1, sockets.size)
        assertEquals(1, checks)
        coordinator.stop()
        runCurrent()
        assertEquals(1, sockets[0].stops)
        advanceTimeBy(60_000)
        assertEquals(1, checks)
        coordinator.start()
        runCurrent()
        assertEquals(2, sockets.size)
        assertEquals(2, checks)
        coordinator.stop(clear = true)
    }

    @Test fun `socket event during failure backoff waits then retries without another event`() = kotlinx.coroutines.test.runTest {
        val socket = Socket()
        var failed = true
        var checks = 0
        val coordinator = MintEventCoordinator(backgroundScope, { true }, { socket },
            { if (failed) 15_000L to true else null to false },
            { _, _ -> checks++; failed = false; MintQuoteSyncResult() }, { testScheduler.currentTime })
        coordinator.ensure("quote")
        runCurrent()
        socket.event = true
        advanceTimeBy(14_999)
        assertEquals(0, checks)
        advanceTimeBy(1)
        runCurrent()
        assertEquals(1, checks)
        advanceTimeBy(59_999)
        assertEquals(1, checks)
        coordinator.stop(clear = true)
    }

    @Test fun `unsupported sockets retain exponential fallback and background fairness`() = kotlinx.coroutines.test.runTest {
        val checked = mutableListOf<Pair<String, Long>>()
        val coordinator = MintEventCoordinator(backgroundScope, { true }, { throw IllegalStateException() },
            { null to false }, { id, _ -> checked.add(id to testScheduler.currentTime); MintQuoteSyncResult() },
            { testScheduler.currentTime })
        coordinator.ensure("first")
        coordinator.ensure("second")
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(listOf("first" to 10_000L), checked)
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals("second" to 15_000L, checked.last())
        coordinator.stop(clear = true)
    }
}
