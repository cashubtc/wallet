package com.cashu.me.ui.send

import com.cashu.me.Core.PendingSend
import com.cashu.me.Core.PendingSendRecovery
import com.cashu.me.Core.SendRecoveryOutcome
import com.cashu.me.ui.components.NoticeSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the pending-ecash screen says after a check or a reclaim. */
class PendingSendRecoveryNoticeTest {
    private val send = PendingSend("a", 16, "token")

    private fun snapshot(outcome: SendRecoveryOutcome, sends: List<PendingSend> = emptyList()) =
        PendingSendRecovery("https://mint.example", "sat", sends.map { it.id }, sends, outcome)

    private fun notice(
        snapshot: PendingSendRecovery,
        checkedOnly: Boolean = true,
        reclaimFailure: String? = null,
        checkFailed: Boolean = false,
        returned: ReclaimedEcash? = null,
    ) = sendRecoveryNotice(snapshot, checkedOnly, reclaimFailure, checkFailed, returned)

    @Test fun settledOutcomesLeaveItToTheStatusScreen() {
        assertNull(notice(snapshot(SendRecoveryOutcome.Completed), reclaimFailure = "Already spent.", checkFailed = true))
        assertNull(notice(snapshot(SendRecoveryOutcome.Returned), checkedOnly = false, returned = ReclaimedEcash(16, 0)))
    }

    @Test fun aCheckThatChangesNothingSaysSoInAToast() {
        listOf(listOf(send), listOf(send, PendingSend("b", 21, null))).forEach {
            val checked = notice(snapshot(SendRecoveryOutcome.Pending, it))
            assertEquals("Not claimed yet", checked?.message)
            assertNull(checked?.title)
            assertTrue(checked?.isToast == true)
        }
    }

    @Test fun aFailedReclaimIsNamedBeforeAFailedCheck() {
        val failed = notice(
            snapshot(SendRecoveryOutcome.Pending, listOf(send)),
            checkedOnly = false, reclaimFailure = "Couldn't reach the mint.", checkFailed = true,
        )
        assertEquals("Couldn't reclaim", failed?.title)
        assertEquals("Couldn't reach the mint.", failed?.message)
        assertEquals(NoticeSeverity.Error, failed?.severity)
        assertFalse(failed?.isToast ?: true)
    }

    @Test fun aFailedCheckAsksForAnotherTry() {
        listOf(
            snapshot(SendRecoveryOutcome.Pending, listOf(send)),
            snapshot(SendRecoveryOutcome.Unverified),
        ).forEach {
            val failed = notice(it, checkFailed = true)
            assertEquals("Couldn't check status", failed?.title)
            assertEquals("Check your connection and try again.", failed?.message)
            assertFalse(failed?.isToast ?: true)
        }
    }

    @Test fun reclaimingOneOfSeveralNamesWhatCameBack() {
        val returned = notice(
            snapshot(SendRecoveryOutcome.Pending, listOf(send)),
            checkedOnly = false, returned = ReclaimedEcash(21, 0),
        )
        assertTrue(returned?.message.orEmpty().startsWith("Reclaimed "))
        assertTrue(returned?.message.orEmpty().endsWith("21"))
        assertTrue(returned?.isToast == true)
    }

    @Test fun anUnresolvedSendKeepsTheScreensOwnWords() {
        assertNull(notice(snapshot(SendRecoveryOutcome.Unverified)))
        assertNull(notice(snapshot(SendRecoveryOutcome.Unverified), checkedOnly = false, returned = ReclaimedEcash(16, 0)))
    }
}
