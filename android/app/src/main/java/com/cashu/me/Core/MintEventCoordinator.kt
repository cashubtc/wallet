package com.cashu.me.Core

import com.cashu.me.Models.PaymentMethodKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** CDK event queue. The released SDK may use WebSockets or its own HTTP fallback. */
interface MintEventSubscription {
    suspend fun drain(): Boolean
    suspend fun stop()
}

/** Subscription existence is not socket readiness. Keep a safety check even with a handle.
 * Durable payment retry deadlines always take precedence over transport events. */
internal class MintEventSchedule(now: Long) {
    private var nextCheck = now + 10_000L
    private var fallbackDelay = 5_000L
    private var wasSubscribed = false
    private var dirty = false

    fun due(now: Long, subscribed: Boolean, event: Boolean, retryAt: Long?, failed: Boolean): Boolean {
        dirty = dirty || event || (subscribed && !wasSubscribed)
        if (!subscribed && wasSubscribed) nextCheck = minOf(nextCheck, now + 5_000L)
        wasSubscribed = subscribed
        if (retryAt != null && retryAt > now) return false
        return failed || dirty || now >= nextCheck
    }

    fun checked(now: Long, subscribed: Boolean) {
        dirty = false
        nextCheck = now + if (subscribed) 60_000L else fallbackDelay
        fallbackDelay = if (subscribed) 5_000L else (fallbackDelay * 2).coerceAtMost(60_000L)
    }
}

/** One job per observed quote; unobserved ledger entries keep bounded passive recovery. */
internal class MintEventCoordinator(
    private val scope: CoroutineScope,
    private val socketsEnabled: () -> Boolean,
    private val subscribe: suspend (String) -> MintEventSubscription,
    private val retry: (String) -> Pair<Long?, Boolean>,
    private val reconcile: suspend (String, Boolean) -> MintQuoteSyncResult,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private class Watch {
        val state = MutableStateFlow<MintQuoteSyncResult?>(null)
        var job: Job? = null
        var observers = 0
        var inFlowObservers = 0
        var complete = false
    }
    private val watches = mutableMapOf<String, Watch>()
    private var active = true
    private val pendingChecks = linkedSetOf<String>()
    private var nextPassiveCheck = 0L

    @Synchronized private fun claimCheck(id: String, visible: Boolean, now: Long): Boolean {
        pendingChecks.add(id)
        if (!visible && (now < nextPassiveCheck || pendingChecks.firstOrNull() != id)) return false
        pendingChecks.remove(id)
        if (!visible) nextPassiveCheck = now + 5_000L
        return true
    }

    @Synchronized
    fun ensure(id: String) {
        if (!active) return
        val watch = watches.getOrPut(id) { Watch() }
        if (watch.job != null || watch.complete) return
        watch.job = scope.launch {
            var subscription: MintEventSubscription? = null
            var nextSubscribe = 0L
            var subscribeDelay = 2_000L
            val schedule = MintEventSchedule(nowMillis())
            try {
                while (isActive) {
                    val now = nowMillis()
                    if (!socketsEnabled()) {
                        subscription?.stop()
                        subscription = null
                    } else if (subscription == null && now >= nextSubscribe) {
                        try {
                            subscription = subscribe(id)
                            subscribeDelay = 2_000L
                        } catch (cancel: CancellationException) { throw cancel
                        } catch (_: Exception) {
                            nextSubscribe = now + subscribeDelay
                            subscribeDelay = (subscribeDelay * 2).coerceAtMost(30_000L)
                        }
                    }
                    var event = false
                    try {
                        event = subscription?.drain() == true
                    } catch (cancel: CancellationException) { throw cancel
                    } catch (_: Exception) {
                        subscription?.stop()
                        subscription = null
                        nextSubscribe = now + subscribeDelay
                        subscribeDelay = (subscribeDelay * 2).coerceAtMost(30_000L)
                    }
                    if (!isActive) break
                    val subscribed = subscription != null
                    val (deadline, failed) = retry(id)
                    val visible = synchronized(this@MintEventCoordinator) { watch.observers > 0 }
                    if (schedule.due(now, subscribed, event, deadline, failed) && claimCheck(id, visible, now)) {
                        val result = try { reconcile(id, synchronized(this@MintEventCoordinator) { watch.inFlowObservers > 0 }) }
                        catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) {
                            schedule.checked(nowMillis(), subscribed)
                            delay(5_000)
                            continue
                        }
                        if (!isActive) break
                        schedule.checked(nowMillis(), subscribed)
                        watch.state.value = result
                        val quote = result.quote
                        if (quote != null && quote.paymentMethod != PaymentMethodKind.Bolt12 &&
                            (quote.hasSettledPayment || (quote.paymentMethod == PaymentMethodKind.Bolt11 &&
                                quote.isExpired && quote.mintableAmount == 0L))) { watch.complete = true; break }
                    }
                    // This checks the local event queue only; network work is deadline-gated above.
                    delay(250)
                }
            } finally {
                withContext(NonCancellable) { subscription?.stop() }
            }
        }
    }

    fun observe(id: String, inFlow: Boolean = true): Flow<MintQuoteSyncResult> {
        val watch = synchronized(this) { watches.getOrPut(id) { Watch() } }
        return watch.state.filterNotNull()
            .onStart {
                synchronized(this@MintEventCoordinator) { watch.observers++; if (inFlow) watch.inFlowObservers++ }
                ensure(id)
            }
            .onCompletion {
                synchronized(this@MintEventCoordinator) {
                    watch.observers--
                    if (inFlow) watch.inFlowObservers--
                    if (watch.observers == 0) {
                        watch.job?.cancel()
                        watch.job = null
                        pendingChecks.remove(id)
                    }
                }
            }
    }

    @Synchronized fun isObserved(id: String): Boolean = active && (watches[id]?.observers ?: 0) > 0

    @Synchronized fun start() {
        active = true
        watches.filterValues { it.observers > 0 }.keys.toList().forEach(::ensure)
    }
    @Synchronized fun stop(clear: Boolean = false) {
        active = false
        watches.values.forEach { it.job?.cancel(); it.job = null }
        pendingChecks.clear()
        nextPassiveCheck = 0L
        if (clear) watches.clear()
    }
}
