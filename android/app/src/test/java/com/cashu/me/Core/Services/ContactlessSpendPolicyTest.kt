package com.cashu.me.Core.Services

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContactlessSpendPolicyTest {
    private var stored: String? = null
    private var now = 86_400_000L
    private fun policy() = ContactlessSpendPolicy({ stored }, { stored = it }, { now })

    @Test fun defaultsAndBoundaryAvoidAuthentication() = runBlocking {
        val policy = policy()
        assertEquals(ContactlessLimits(1000, 10000), policy.state().limits)
        policy.authorizeAndRecord(1000, { true }) { fail("Unexpected authentication"); false }
        assertEquals(1000, policy.state().spent)
    }

    @Test fun largerPaymentRequiresSuccessfulAuthentication() = runBlocking {
        val policy = policy()
        assertTrue(runCatching { policy.authorizeAndRecord(1001, { true }) { false } }.isFailure)
        assertEquals(0, policy.state().spent)
        policy.authorizeAndRecord(1001, { true }) { true }
        assertEquals(1001, policy.state().spent)
    }

    @Test fun dailyCapIncludesTotalAndSurvivesRecreation() = runBlocking {
        policy().authorizeAndRecord(9999, { true }) { true }
        assertTrue(runCatching { policy().authorizeAndRecord(2, { true }) { true } }.isFailure)
        assertEquals(9999, policy().state().spent)
        policy().authorizeAndRecord(1, { true }) { true }
        assertEquals(10000, policy().state().spent)
    }

    @Test fun dayRolloverResetsButClockRollbackDoesNot() = runBlocking {
        policy().authorizeAndRecord(10000, { true }) { true }
        now = 0
        assertTrue(runCatching { policy().authorizeAndRecord(1, { true }) { true } }.isFailure)
        now = 2 * 86_400_000L
        policy().authorizeAndRecord(1, { true }) { true }
        assertEquals(1, policy().state().spent)
    }

    @Test fun settingsChangesDoNotResetDailyUse() = runBlocking {
        val policy = policy()
        policy.authorizeAndRecord(1000, { true }) { true }
        policy.saveLimits(ContactlessLimits(0, 2000))
        assertEquals(1000, policy.state().spent)
        assertTrue(runCatching { policy.authorizeAndRecord(1, { true }) { false } }.isFailure)
    }

    @Test fun foregroundIsRecheckedAfterAuthentication() = runBlocking {
        val policy = policy()
        var active = true
        assertTrue(runCatching {
            policy.authorizeAndRecord(1001, { active }) { active = false; true }
        }.isFailure)
        assertEquals(0, policy.state().spent)
    }

    @Test fun corruptStateAndWriteFailureDoNotAuthorize() = runBlocking {
        stored = "invalid"
        assertTrue(runCatching { policy().authorizeAndRecord(1, { true }) { true } }.isFailure)
        val broken = ContactlessSpendPolicy({ null }, { error("storage") })
        assertTrue(runCatching { broken.authorizeAndRecord(1, { true }) { true } }.isFailure)
    }

    @Test fun simultaneousRequestsCannotExceedDailyCap() = runBlocking {
        val policy = policy()
        val outcomes = (1..2).map { async { runCatching { policy.authorizeAndRecord(6000, { true }) { true } }.isSuccess } }
        assertEquals(1, outcomes.count { it.await() })
        assertEquals(6000, policy.state().spent)
    }

    @Test fun rejectsInvalidLimitsAndOverflowSizedRequests() = runBlocking {
        val policy = policy()
        for (limits in listOf(ContactlessLimits(-1, 100), ContactlessLimits(101, 100), ContactlessLimits(0, 0))) {
            assertTrue(runCatching { policy.saveLimits(limits) }.isFailure)
        }
        assertTrue(runCatching { policy.authorizeAndRecord(Long.MAX_VALUE, { true }) { true } }.isFailure)
    }

    @Test fun rejectedApprovalCancelsBeforeConfirmation() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(runCatching {
            completeContactlessSend(
                approve = { events += "approve"; error("denied") },
                confirm = { events += "confirm"; "token" },
                cancel = { events += "cancel" }, reclaim = { events += "reclaim" },
                deliver = { events += "deliver" },
            )
        }.isFailure)
        assertEquals(listOf("approve", "cancel"), events)
    }

    @Test fun writeFailureReclaimsWithoutRepeatingConfirmation() = runBlocking {
        val events = mutableListOf<String>()
        assertTrue(runCatching {
            completeContactlessSend(
                approve = { events += "approve" }, confirm = { events += "confirm"; "token" },
                cancel = { events += "cancel" }, reclaim = { events += "reclaim" },
                deliver = { events += "deliver"; error("write") },
            )
        }.isFailure)
        assertEquals(listOf("approve", "confirm", "deliver", "reclaim"), events)
    }

    @Test fun cancelledDeliveryStillRunsCleanup() = runBlocking {
        val writing = CompletableDeferred<Unit>()
        var reclaimed = false
        val job = launch {
            completeContactlessSend(approve = {}, confirm = { "token" }, cancel = {},
                reclaim = { reclaimed = true }, deliver = { writing.complete(Unit); CompletableDeferred<Unit>().await() })
        }
        writing.await()
        job.cancelAndJoin()
        assertTrue(reclaimed)
    }

    @Test fun successfulDeliveryDoesNotReclaim() = runBlocking {
        val token = completeContactlessSend(approve = {}, confirm = { "token" },
            cancel = { fail("cancel") }, reclaim = { fail("reclaim") }, deliver = { assertEquals("token", it) })
        assertEquals("token", token)
    }
}
