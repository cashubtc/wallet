package com.cashu.me.Core

import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.PaymentMethodKind
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** The visible payment code owns the loop; cancellation releases maintenance. */
internal class FocusedMintQuoteMonitor {
    private val sessions = ConcurrentHashMap<UUID, String>()
    val isActive: Boolean get() = sessions.isNotEmpty()

    fun isMonitoring(quoteId: String): Boolean = sessions.containsValue(quoteId)

    suspend fun monitor(
        quoteId: String,
        refresh: suspend (String) -> MintQuoteInfo?,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ) {
        if (!currentCoroutineContext().isActive) return
        val session = UUID.randomUUID()
        sessions[session] = quoteId
        try {
            while (currentCoroutineContext().isActive) {
                // Direct reconciliation bypasses passive batch/age scheduling,
                // while the reconciler still applies persisted failure backoff.
                val quote = refresh(quoteId)
                if (!currentCoroutineContext().isActive) return
                if (quote != null && quote.paymentMethod != PaymentMethodKind.Bolt12 &&
                    (quote.hasSettledPayment ||
                        (quote.paymentMethod == PaymentMethodKind.Bolt11 &&
                            quote.isExpired && quote.mintableAmount == 0L))
                ) return
                sleep(if (quote?.paymentMethod == PaymentMethodKind.Onchain) 10_000 else 2_000)
            }
        } finally {
            sessions.remove(session)
        }
    }
}
