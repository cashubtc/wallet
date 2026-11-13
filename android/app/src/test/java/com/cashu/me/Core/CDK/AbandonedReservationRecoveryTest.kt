package com.cashu.me.Core.CDK

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AbandonedReservationRecoveryTest {
    @Test fun returnsHeldFundsOnceAndLeavesOtherReservationsAlone() = runBlocking {
        val world = World()
        assertEquals(setOf("old"), world.recovery().recover(MINT, "sat"))
        assertTrue(world.recovery().recover(MINT, "sat").isEmpty())
        assertEquals(listOf("old"), world.released)
        assertEquals(listOf("new"), world.proofs.mapNotNull { it.owner })
    }

    @Test fun liveOperationOrPaymentIsNeverReleased() = runBlocking {
        for (kind in listOf("operation", "payment", "activity")) {
            val world = World()
            world.blocker = kind
            assertTrue(world.recovery().recover(MINT, "sat").isEmpty())
            assertTrue(world.released.isEmpty())
        }
    }

    @Test fun pendingProofOrIncompleteMintResponseKeepsFundsHeld() = runBlocking {
        val world = World()
        world.unspent = false
        assertTrue(world.recovery().recover(MINT, "sat").isEmpty())
        world.unspent = true
        world.proofs = world.proofs.map { if (it.owner == "old") it.copy(reserved = false) else it }
        assertTrue(world.recovery().recover(MINT, "sat").isEmpty())
        assertTrue(world.released.isEmpty())
    }

    @Test fun changedOwnershipWhileCheckingTheMintPreventsRelease() = runBlocking {
        val world = World()
        world.onCheck = { world.proofs = world.proofs.map { it.copy(owner = "changed") } }
        assertTrue(world.recovery().recover(MINT, "sat").isEmpty())
        assertTrue(world.released.isEmpty())
    }

    @Test fun networkFailureAndMixedAccountsKeepFundsHeld() = runBlocking {
        val world = World()
        world.onCheck = { throw java.io.IOException("offline") }
        assertTrue(runCatching { world.recovery().recover(MINT, "sat") }.isFailure)
        assertTrue(world.released.isEmpty())
        world.onCheck = {}
        world.proofs = world.proofs + world.proofs.first().copy(id = "other", unit = "usd")
        assertTrue(world.recovery().recover(MINT, "sat").isEmpty())
        assertTrue(world.released.isEmpty())
    }

    @Test fun requiresEveryRequestedProofToBeExplicitlyUnspent() {
        val ys = listOf("a", "b")
        fun response(rows: String) = """{"states":[$rows]}"""
        assertTrue(AbandonedReservationRecovery.allUnspent(ys, response("""{"Y":"b","state":"UNSPENT"},{"Y":"a","state":"UNSPENT"}""")))
        for (rows in listOf(
            """{"Y":"a","state":"UNSPENT"}""",
            """{"Y":"a","state":"UNSPENT"},{"Y":"b","state":"PENDING"}""",
            """{"Y":"a","state":"UNSPENT"},{"Y":"b","state":"SPENT"}""",
            """{"Y":"a","state":"UNSPENT"},{"Y":"a","state":"UNSPENT"}""",
        )) assertFalse(AbandonedReservationRecovery.allUnspent(ys, response(rows)))
        assertFalse(AbandonedReservationRecovery.allUnspent(ys, "{}"))
    }

    private class World {
        var proofs = listOf(
            ReservationProof("a", "old", MINT, "sat", true),
            ReservationProof("b", "new", MINT, "sat", true),
        )
        var blocker = ""
        var unspent = true
        var onCheck: () -> Unit = {}
        val released = mutableListOf<String>()
        fun recovery() = AbandonedReservationRecovery(
            eligibleOwners = setOf("old"), loadProofs = { proofs },
            hasOperation = { blocker == "operation" }, hasTransaction = { blocker == "payment" },
            hasActivity = { _, _ -> blocker == "activity" },
            checkUnspent = { _, _ -> onCheck(); unspent },
            release = { owner -> released += owner; proofs = proofs.filterNot { it.owner == owner } },
        )
    }

    companion object { const val MINT = "https://mint.example" }
}
