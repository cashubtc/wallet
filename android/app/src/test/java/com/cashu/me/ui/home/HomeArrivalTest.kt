package com.cashu.me.ui.home

import com.cashu.me.Core.HistoryFilter
import com.cashu.me.Models.CashuRequest
import com.cashu.me.Models.CashuRequestPayment
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import com.cashu.me.ui.history.HistoryItem
import com.cashu.me.ui.history.unifiedFiltered
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeArrivalLedgerTest {
    private val base = snapshot(listOf("old" to true), sats = 1_000)
    private val landed = snapshot(listOf("new" to true, "old" to true), sats = 3_500)
    private val paid = HomeReceipt(2_500, "sat", homeOwned = true)

    @Test
    fun `home mirrors the wallet until the first load`() {
        val ledger = HomeArrivalLedger(HomePresence.OnScreen)
        ledger.update(base, loaded = false)
        assertNull(ledger.shown)

        ledger.update(landed, loaded = true)
        assertEquals("the first load is shown as is", landed, ledger.shown)
        assertFalse(ledger.hasPendingBeat)
        assertNull(ledger.takeBeat())
    }

    @Test
    fun `wallet reset returns home to mirroring`() {
        val ledger = loaded(HomePresence.OnScreen)
        ledger.update(snapshot(emptyList(), sats = 0), loaded = false)
        assertNull(ledger.shown)
        assertFalse(ledger.hasPendingBeat)
    }

    @Test
    fun `on-screen receipt waits for its row then plays one beat`() {
        val ledger = loaded(HomePresence.OnScreen)
        assertEquals("on screen, the haptic waits for the beat", emptyList<HomeReceipt>(), ledger.receive(paid))

        // The receipt can land before the balance and the row.
        assertNull(ledger.takeBeat())
        ledger.update(snapshot(listOf("old" to true), sats = 3_500), loaded = true)
        assertNull("the balance alone doesn't play", ledger.takeBeat())

        ledger.update(landed, loaded = true)
        assertEquals(HomeBeat(landed, mapOf("sat" to 2_500L), listOf(paid)), ledger.takeBeat())
        assertEquals(landed, ledger.shown)
        assertFalse(ledger.hasPendingBeat)
    }

    @Test
    fun `late row confirms first and carries plus N when it lands`() {
        val ledger = loaded(HomePresence.OnScreen)
        ledger.receive(paid)
        assertTrue(ledger.isAwaitingRow)
        assertEquals(listOf(paid), ledger.takeOverdueConfirmations())
        assertNull("+N never shows under a balance that hasn't moved", ledger.takeBeat())

        ledger.update(landed, loaded = true)
        assertEquals(HomeBeat(landed, mapOf("sat" to 2_500L), emptyList()), ledger.takeBeat())
    }

    @Test
    fun `row that never comes stops blocking later changes`() {
        val ledger = loaded(HomePresence.OnScreen)
        ledger.receive(paid)
        ledger.takeOverdueConfirmations()
        ledger.stopAwaitingRow()
        assertFalse(ledger.isAwaitingRow)
        assertNull("nothing moved, and the +N was dropped", ledger.takeBeat())

        val rolled = snapshot(listOf("old" to true), sats = 3_500)
        ledger.update(rolled, loaded = true)
        assertEquals(HomeBeat(rolled, emptyMap(), emptyList()), ledger.takeBeat())
    }

    @Test
    fun `back-to-back receipts sum into one beat`() {
        val ledger = loaded(HomePresence.OnScreen)
        ledger.receive(HomeReceipt(1_000, "sat", homeOwned = true))
        ledger.receive(HomeReceipt(1_500, "sat", homeOwned = true))
        ledger.update(landed, loaded = true)
        val beat = ledger.takeBeat()
        assertEquals(mapOf("sat" to 2_500L), beat?.deltas)
        assertEquals(2, beat?.confirmations?.size)
    }

    @Test
    fun `covered changes hold and replay when home returns`() {
        val ledger = loaded(HomePresence.Covered)
        ledger.update(landed, loaded = true)
        assertEquals("nothing moves under a cover", base, ledger.shown)
        assertFalse(ledger.hasPendingBeat)

        assertEquals("the haptic plays when the money lands", listOf(paid), ledger.receive(paid))
        assertEquals("the sheet confirmed it", emptyList<HomeReceipt>(), ledger.receive(HomeReceipt(700, "sat", homeOwned = false)))

        assertEquals(emptyList<HomeReceipt>(), ledger.setPresence(HomePresence.OnScreen))
        assertEquals(
            "the replay shows +N only for what no sheet confirmed, and doesn't buzz twice",
            HomeBeat(landed, mapOf("sat" to 2_500L), emptyList()),
            ledger.takeBeat(),
        )
    }

    @Test
    fun `send replays without plus N`() {
        val ledger = loaded(HomePresence.Covered)
        val sent = snapshot(listOf("send" to false, "old" to true), sats = 400)
        ledger.update(sent, loaded = true)
        ledger.setPresence(HomePresence.OnScreen)
        assertEquals(HomeBeat(sent, emptyMap(), emptyList()), ledger.takeBeat())
    }

    @Test
    fun `replay drops plus N where the balance fell`() {
        val ledger = loaded(HomePresence.Covered)
        ledger.receive(HomeReceipt(13, "sat", homeOwned = true))
        // The receipt landed during a send of 14, so the balance fell by 1.
        val sentAndPaid = snapshot(listOf("send" to false, "paid" to true, "old" to true), sats = 999)
        ledger.update(sentAndPaid, loaded = true)
        ledger.setPresence(HomePresence.OnScreen)
        assertEquals(HomeBeat(sentAndPaid, emptyMap(), emptyList()), ledger.takeBeat())
    }

    @Test
    fun `covering home before its beat confirms now and replays later`() {
        val ledger = loaded(HomePresence.OnScreen)
        ledger.receive(paid)
        assertEquals(listOf(paid), ledger.setPresence(HomePresence.Covered))

        ledger.update(landed, loaded = true)
        ledger.setPresence(HomePresence.OnScreen)
        assertEquals(HomeBeat(landed, mapOf("sat" to 2_500L), emptyList()), ledger.takeBeat())
    }

    @Test
    fun `history tab takes changes silently`() {
        val ledger = loaded(HomePresence.Covered)
        ledger.receive(paid)
        ledger.setPresence(HomePresence.SeenElsewhere)
        ledger.update(landed, loaded = true)
        assertEquals(landed, ledger.shown)
        assertEquals(listOf(paid), ledger.receive(paid))

        ledger.setPresence(HomePresence.Covered)
        ledger.setPresence(HomePresence.OnScreen)
        assertFalse("History already showed it arrive", ledger.hasPendingBeat)
        assertNull(ledger.takeBeat())
    }

    @Test
    fun `plus N is kept per unit`() {
        val ledger = loaded(HomePresence.OnScreen)
        val euros = HomeReceipt(500, "EUR", homeOwned = true)
        ledger.receive(euros)
        ledger.receive(paid)
        ledger.update(landed.copy(balancesByUnit = landed.balancesByUnit + ("eur" to 500L)), loaded = true)
        val beat = ledger.takeBeat()
        assertEquals(mapOf("eur" to 500L, "sat" to 2_500L), beat?.deltas)
        assertEquals(listOf(euros, paid), beat?.confirmations)
    }

    @Test
    fun `announcement sums each unit`() {
        assertEquals("Received 2,500 sat", homeReceiptAnnouncement(listOf(paid)))
        assertEquals(
            "Received 2,500 sat",
            homeReceiptAnnouncement(
                listOf(HomeReceipt(1_000, "sat", homeOwned = true), HomeReceipt(1_500, "SAT", homeOwned = true)),
            ),
        )
    }

    private fun loaded(presence: HomePresence) = HomeArrivalLedger(presence).apply {
        update(base, loaded = true)
    }
}

class HomeArrivalCoordinatorTest {
    @Test
    fun `pieces landing separately play as one beat`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val coordinator = HomeArrivalCoordinator(scope, quietMillis = 30, rowWaitMillis = 1_000, deltaMillis = 5_000)
        val confirmed = mutableListOf<List<HomeReceipt>>()
        coordinator.onConfirm = { confirmed += it }
        coordinator.setPresence(HomePresence.OnScreen)
        coordinator.update(snapshot(listOf("old" to true), sats = 1_000), emptyList(), loaded = true)

        // Receipt, then balance, then the row, as the mint-quote sync publishes them.
        val paid = HomeReceipt(2_500, "sat", homeOwned = true)
        coordinator.receive(paid)
        coordinator.update(snapshot(listOf("old" to true), sats = 3_500), emptyList(), loaded = true)
        delay(80)
        assertTrue("the beat waits for the row", confirmed.isEmpty())
        assertEquals(1_000L, coordinator.shown?.satBalance)

        val landed = snapshot(listOf("new" to true, "old" to true), sats = 3_500)
        coordinator.update(landed, emptyList(), loaded = true)
        delay(120)
        assertEquals(landed, coordinator.shown)
        assertEquals(mapOf("sat" to 2_500L), coordinator.receivedDeltas)
        assertEquals(listOf(listOf(paid)), confirmed)

        // A second receipt while "+N" shows adds to it.
        coordinator.receive(paid)
        coordinator.update(snapshot(listOf("new2" to true, "new" to true, "old" to true), sats = 6_000), emptyList(), loaded = true)
        delay(120)
        assertEquals(mapOf("sat" to 5_000L), coordinator.receivedDeltas)

        coordinator.setPresence(HomePresence.Covered)
        assertEquals("leaving Home clears the +N", emptyMap<String, Long>(), coordinator.receivedDeltas)
        scope.cancel()
    }

    @Test
    fun `late row confirms on time and beats when it lands`() = runBlocking {
        val scope = CoroutineScope(coroutineContext + Job())
        val coordinator = HomeArrivalCoordinator(scope, quietMillis = 20, rowWaitMillis = 60, deltaMillis = 5_000)
        val confirmed = mutableListOf<List<HomeReceipt>>()
        coordinator.onConfirm = { confirmed += it }
        coordinator.setPresence(HomePresence.OnScreen)
        coordinator.update(snapshot(listOf("old" to true), sats = 1_000), emptyList(), loaded = true)

        val paid = HomeReceipt(2_500, "sat", homeOwned = true)
        coordinator.receive(paid)
        delay(250)
        assertEquals("the haptic doesn't wait on a slow mint", listOf(listOf(paid)), confirmed)
        assertEquals(emptyMap<String, Long>(), coordinator.receivedDeltas)

        val landed = snapshot(listOf("new" to true, "old" to true), sats = 3_500)
        coordinator.update(landed, emptyList(), loaded = true)
        delay(150)
        assertEquals(landed, coordinator.shown)
        assertEquals(mapOf("sat" to 2_500L), coordinator.receivedDeltas)
        assertEquals(1, confirmed.size)
        scope.cancel()
    }
}

class HistoryRequestActivityTest {
    @Test
    fun `a request that just got paid sorts by its latest payment`() {
        val older = WalletTransaction(
            id = "older",
            amount = 10,
            type = TransactionType.Outgoing,
            kind = TransactionKind.Ecash,
            dateEpochMillis = 3_000,
            status = TransactionStatus.Completed,
        )
        val paidAt = WalletTransaction(
            id = "tx1",
            amount = 21,
            type = TransactionType.Incoming,
            kind = TransactionKind.Ecash,
            dateEpochMillis = 5_000,
            status = TransactionStatus.Completed,
        )
        val request = CashuRequest(
            id = "request",
            encoded = "creqA",
            createdAtEpochMillis = 1_000,
            receivedPayments = listOf(CashuRequestPayment("tx1", amount = 21, receivedAtEpochMillis = 9_000)),
        )

        val items = unifiedFiltered(listOf(older, paidAt), listOf(request), HistoryFilter.All, query = "")
        assertEquals(listOf("req:request", "tx:older"), items.map { it.key })
        assertEquals(
            "the wallet's own payment time wins over when it was linked",
            5_000L,
            (items.first() as HistoryItem.Req).activityEpochMillis,
        )

        val unpaid = request.copy(receivedPayments = emptyList())
        assertEquals(1_000L, unpaid.lastActivityEpochMillis(emptyMap()))
    }
}

private fun snapshot(rows: List<Pair<String, Boolean>>, sats: Long) = HomeSnapshot(
    rows = rows.map { (id, incoming) -> HomeSnapshot.Row(id, incoming) },
    balancesByUnit = mapOf("sat" to sats),
    satBalance = sats,
)
