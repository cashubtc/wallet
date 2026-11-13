package com.cashu.me.ui.home

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.Core.Protocols.CurrencyAmount
import com.cashu.me.Core.Protocols.CurrencyRegistry
import com.cashu.me.Core.WalletState
import com.cashu.me.Core.recentPaymentTransactions
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What the home screen shows of the wallet: the Recent rows, by id, and the
 * balances. A row's content always renders live; only its arrival or
 * departure waits for the beat (iOS `HomeSnapshot` parity).
 */
internal data class HomeSnapshot(
    val rows: List<Row>,
    val balancesByUnit: Map<String, Long>,
    val satBalance: Long,
) {
    data class Row(val id: String, val isIncoming: Boolean)

    /** The balance held in a lowercased unit. */
    fun balanceOf(unit: String): Long =
        balancesByUnit.entries.firstOrNull { it.key.lowercase() == unit }?.value
            ?: if (unit == "sat") satBalance else 0L

    /** True when this holds an incoming row that [earlier] did not. */
    fun hasNewIncomingRow(earlier: HomeSnapshot): Boolean {
        val known = earlier.rows.mapTo(HashSet()) { it.id }
        return rows.any { it.isIncoming && it.id !in known }
    }
}

/** Where the home screen stands relative to the user. */
internal enum class HomePresence {
    /** Nothing covers it: changes play as they settle. */
    OnScreen,

    /**
     * A sheet, overlay, pushed page, another tab, the lock screen or the
     * background hides it: changes wait and replay on return.
     */
    Covered,

    /**
     * The History tab is showing and plays arrivals itself, so home takes
     * changes silently and replays nothing.
     */
    SeenElsewhere,
}

/** A confirmed incoming payment, as home hears about it. */
internal data class HomeReceipt(
    val amount: Long,
    val unit: String,
    /**
     * No other surface confirms this payment (npub.cash, a background
     * Lightning payment, an unwatched Cashu request), so home owns its haptic
     * and spoken announcement, and replays its "+N" after a cover.
     */
    val homeOwned: Boolean,
) {
    /** Lowercased mint unit ("sat", "eur", …). */
    val unitKey: String get() = unit.lowercase()
}

/**
 * Everything home shows changing at once: rows, balances, "+N", haptic and
 * announcement start on the same frame.
 */
internal data class HomeBeat(
    val snapshot: HomeSnapshot,
    /** "+N" per lowercased unit; empty for none. */
    val deltas: Map<String, Long>,
    /** Home-owned receipts whose haptic and announcement play with this beat. */
    val confirmations: List<HomeReceipt>,
)

/**
 * The home arrival rules, kept free of timers and composition so they test
 * directly.
 *
 * The wallet publishes a receipt's pieces separately (balance, then rows, and
 * the receipt event on either side), so home holds what it shows and moves it
 * in one beat once the pieces have settled.
 */
internal class HomeArrivalLedger(presence: HomePresence = HomePresence.Covered) {
    /**
     * What home shows. Null until the first transaction load: until then home
     * mirrors the wallet with no motion, so a launch never plays an arrival.
     */
    var shown: HomeSnapshot? = null
        private set
    var presence: HomePresence = presence
        private set
    private var live: HomeSnapshot? = null

    /** Receipts that landed on screen, per unit, waiting for their beat. */
    private val liveDeltas = mutableMapOf<String, Long>()
    private val liveConfirmations = mutableListOf<HomeReceipt>()

    /** Home-owned receipts that landed under a cover, replayed as "+N". */
    private val heldDeltas = mutableMapOf<String, Long>()

    /** A receipt landed on screen before its row did. */
    private var awaitingIncomingRow = false

    /** True while a beat is waiting to play. */
    val hasPendingBeat: Boolean
        get() {
            val shown = shown ?: return false
            val live = live ?: return false
            if (presence != HomePresence.OnScreen) return false
            return live != shown || liveDeltas.isNotEmpty() || heldDeltas.isNotEmpty() ||
                liveConfirmations.isNotEmpty()
        }

    /**
     * Feed the wallet's current state. [loaded] is false until the wallet's
     * first transaction load, and again after a wallet reset.
     */
    fun update(snapshot: HomeSnapshot, loaded: Boolean) {
        live = snapshot
        if (!loaded) {
            shown = null
            clearPending()
            return
        }
        if (shown == null || presence == HomePresence.SeenElsewhere) shown = snapshot
    }

    /**
     * Record a receipt. Returns the receipts whose haptic and announcement
     * play now: home-owned ones that won't get an on-screen beat.
     */
    fun receive(receipt: HomeReceipt): List<HomeReceipt> {
        val confirmNow = if (receipt.homeOwned) listOf(receipt) else emptyList()
        if (shown == null) return confirmNow
        return when (presence) {
            HomePresence.OnScreen -> {
                liveDeltas.add(receipt.unitKey, receipt.amount)
                if (receipt.homeOwned) liveConfirmations += receipt
                awaitingIncomingRow = true
                emptyList()
            }
            HomePresence.Covered -> {
                if (receipt.homeOwned) heldDeltas.add(receipt.unitKey, receipt.amount)
                confirmNow
            }
            HomePresence.SeenElsewhere -> confirmNow
        }
    }

    /**
     * Returns the receipts whose haptic and announcement must play now,
     * because home was covered before their beat could play.
     */
    fun setPresence(newPresence: HomePresence): List<HomeReceipt> {
        val old = presence
        if (newPresence == old) return emptyList()
        presence = newPresence
        var flushed = emptyList<HomeReceipt>()
        if (old == HomePresence.OnScreen) {
            // These landed while home was showing, so their "+N" replays.
            liveDeltas.forEach { (unit, amount) -> heldDeltas.add(unit, amount) }
            liveDeltas.clear()
            flushed = liveConfirmations.toList()
            liveConfirmations.clear()
            awaitingIncomingRow = false
        }
        if (newPresence == HomePresence.SeenElsewhere) {
            if (shown != null) shown = live
            heldDeltas.clear()
        }
        return flushed
    }

    /** A receipt landed on screen and its row hasn't loaded yet. */
    val isAwaitingRow: Boolean
        get() {
            val shown = shown ?: return false
            val live = live ?: return false
            return awaitingIncomingRow && !live.hasNewIncomingRow(shown)
        }

    /**
     * Plays the beat when it's ready. A receipt waits for its row, so its "+N"
     * never shows under a balance that hasn't moved.
     */
    fun takeBeat(): HomeBeat? {
        if (!hasPendingBeat || isAwaitingRow) return null
        val shown = shown ?: return null
        val live = live ?: return null
        // A "+N" beside a balance that didn't rise (a send settled in the same
        // replay) would contradict the roll, so it shows only where it rose.
        val deltas = liveDeltas.toMutableMap().apply {
            heldDeltas.forEach { (unit, amount) -> add(unit, amount) }
        }.filterKeys { live.balanceOf(it) > shown.balanceOf(it) }
        val beat = HomeBeat(live, deltas, liveConfirmations.toList())
        this.shown = live
        clearPending()
        return beat
    }

    /**
     * The row is late: hands over the receipts waiting on it so their haptic
     * and announcement play now. Their "+N" still waits to land with the row.
     */
    fun takeOverdueConfirmations(): List<HomeReceipt> {
        if (!isAwaitingRow) return emptyList()
        return liveConfirmations.toList().also { liveConfirmations.clear() }
    }

    /**
     * The row never came: stop waiting and drop the "+N" it would have
     * carried, so later changes play again.
     */
    fun stopAwaitingRow() {
        if (!isAwaitingRow) return
        awaitingIncomingRow = false
        liveDeltas.clear()
    }

    private fun clearPending() {
        liveDeltas.clear()
        liveConfirmations.clear()
        heldDeltas.clear()
        awaitingIncomingRow = false
    }

    private fun MutableMap<String, Long>.add(unit: String, amount: Long) {
        this[unit] = (this[unit] ?: 0L) + amount
    }
}

/**
 * Runs [HomeArrivalLedger] on the clock for the whole shell, so arrivals keep
 * being held while Home is out of composition: waits for the wallet's writes
 * to go quiet, gives a receipt's row a little longer to load, then plays the
 * beat. Also owns the "+N" line, which sums receipts while it shows.
 */
@Stable
class HomeArrivalCoordinator internal constructor(
    private val scope: CoroutineScope,
    /** How long the wallet's writes must pause before a beat. */
    private val quietMillis: Long = 250,
    /**
     * The shorter pause before a replay once Home is uncovered, when the
     * writes have long settled.
     */
    private val returnPauseMillis: Long = 100,
    /**
     * A mint-quote sync announces each receipt as it mints but publishes
     * balance and rows once, after the whole pass, so a slow mint can hold the
     * row back for seconds: after [rowWaitMillis] the receipt's haptic and
     * announcement play without it, and after [rowGiveUpMillis] Home stops
     * waiting for it.
     */
    private val rowWaitMillis: Long = 5_000,
    private val rowGiveUpMillis: Long = 30_000,
    /** How long the "+N" shows (iOS MainWalletView: 2.5s). */
    private val deltaMillis: Long = 2_500,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** What home renders; null means mirror the wallet directly. */
    internal var shown by mutableStateOf<HomeSnapshot?>(null)
        private set

    /** The "+N" per lowercased unit while it shows. */
    internal var receivedDeltas by mutableStateOf<Map<String, Long>>(emptyMap())
        private set

    /** Plays haptic + announcement for home-owned receipts. */
    internal var onConfirm: (List<HomeReceipt>) -> Unit = {}

    private val ledger = HomeArrivalLedger()
    private val rowCache = mutableMapOf<String, WalletTransaction>()
    private var liveRowIds: Set<String> = emptySet()
    private var settleJob: Job? = null
    private var deltaJob: Job? = null
    /** When a receipt waiting on its row confirms anyway, and when it stops waiting. */
    private var rowDeadline: Long? = null
    private var rowGiveUp: Long? = null

    internal fun update(snapshot: HomeSnapshot, rows: List<WalletTransaction>, loaded: Boolean) {
        rows.forEach { rowCache[it.id] = it }
        liveRowIds = rows.mapTo(HashSet()) { it.id }
        ledger.update(snapshot, loaded)
        syncShown()
        scheduleBeat()
    }

    internal fun receive(receipt: HomeReceipt) {
        confirm(ledger.receive(receipt))
        scheduleBeat()
    }

    internal fun setPresence(presence: HomePresence) {
        confirm(ledger.setPresence(presence))
        // Leaving Home clears the "+N" (iOS clears it on disappear/background).
        if (presence != HomePresence.OnScreen) clearDelta()
        syncShown()
        scheduleBeat(if (presence == HomePresence.OnScreen) returnPauseMillis else quietMillis)
    }

    /**
     * The last known copy of a row the wallet no longer lists, so a row that
     * is leaving can still render until its beat.
     */
    internal fun cachedRow(id: String): WalletTransaction? = rowCache[id]

    private fun syncShown() {
        if (shown == ledger.shown) return
        shown = ledger.shown
        pruneRowCache()
    }

    private fun pruneRowCache() {
        val kept = liveRowIds + shown?.rows.orEmpty().map { it.id }
        rowCache.keys.retainAll(kept)
    }

    private fun scheduleBeat(pauseMillis: Long = quietMillis) {
        settleJob?.cancel()
        settleJob = null
        if (!ledger.hasPendingBeat) {
            clearRowDeadlines()
            return
        }
        if (ledger.isAwaitingRow && rowDeadline == null) {
            rowDeadline = now() + rowWaitMillis
            rowGiveUp = now() + rowGiveUpMillis
        }
        settle(afterMillis = pauseMillis)
    }

    private fun settle(afterMillis: Long) {
        settleJob = scope.launch {
            delay(afterMillis)
            settle()
        }
    }

    private fun settle() {
        settleJob = null
        val now = now()
        val giveUp = rowGiveUp
        if (ledger.isAwaitingRow && giveUp != null && now >= giveUp) ledger.stopAwaitingRow()
        val beat = ledger.takeBeat()
        if (beat == null) {
            val deadline = rowDeadline ?: return
            if (!ledger.isAwaitingRow || giveUp == null) return
            if (now >= deadline) confirm(ledger.takeOverdueConfirmations())
            // The wallet's next write reschedules; otherwise wake at the next deadline.
            settle(afterMillis = (if (now < deadline) deadline else giveUp) - now)
            return
        }
        clearRowDeadlines()
        shown = beat.snapshot
        pruneRowCache()
        if (beat.deltas.isNotEmpty()) showDelta(beat.deltas)
        confirm(beat.confirmations)
    }

    private fun clearRowDeadlines() {
        rowDeadline = null
        rowGiveUp = null
    }

    /** Adds to the "+N" already showing and restarts its dismiss timer. */
    private fun showDelta(deltas: Map<String, Long>) {
        deltaJob?.cancel()
        receivedDeltas = (receivedDeltas.keys + deltas.keys).associateWith {
            (receivedDeltas[it] ?: 0L) + (deltas[it] ?: 0L)
        }
        deltaJob = scope.launch {
            delay(deltaMillis)
            receivedDeltas = emptyMap()
        }
    }

    private fun clearDelta() {
        deltaJob?.cancel()
        deltaJob = null
        receivedDeltas = emptyMap()
    }

    private fun confirm(receipts: List<HomeReceipt>) {
        if (receipts.isNotEmpty()) onConfirm(receipts)
    }
}

/** The live side of [HomeSnapshot]: Recent's rows and the balances. */
internal fun WalletState.homeSnapshot(rows: List<WalletTransaction>): HomeSnapshot = HomeSnapshot(
    rows = rows.map { HomeSnapshot.Row(it.id, it.type == TransactionType.Incoming) },
    balancesByUnit = balancesByUnit,
    satBalance = balance,
)

/** Recent's rows as the wallet lists them right now. */
internal fun WalletState.liveRecentRows(): List<WalletTransaction> =
    recentPaymentTransactions(transactions, HOME_RECENT_LIMIT)

/** True once the wallet has read its transactions since the runtime started. */
internal val WalletState.hasLoadedTransactions: Boolean
    get() = isRuntimeReady && transactionUpdateVersion > 0

internal const val HOME_RECENT_LIMIT = 5

/** Spoken receipt confirmation, respecting the current balance visibility. */
internal fun homeReceiptAnnouncement(
    receipts: List<HomeReceipt>,
    hideBalances: Boolean,
    formatter: AmountFormatter = AmountFormatter(),
): String {
    if (hideBalances) return "Payment received"
    val totals = LinkedHashMap<String, Long>()
    receipts.forEach { totals[it.unitKey] = (totals[it.unitKey] ?: 0L) + it.amount }
    val amounts = totals.map { (unit, amount) ->
        if (unit == "sat") {
            formatter.satsParts(amount, useBitcoinSymbol = false).spoken
        } else {
            CurrencyAmount(amount, CurrencyRegistry.currencyForMintUnit(unit)).formatted()
        }
    }
    return "Received " + amounts.joinToString(" and ")
}
