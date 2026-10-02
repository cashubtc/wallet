package com.cashu.me.Core

import com.cashu.me.Models.MintQuoteRetryState

/** A status line and its screen-reader form. */
data class StatusText(val text: String, val spoken: String)

/**
 * Where an on-chain deposit address stands, from waiting through the ecash
 * landing. The receive sheet, the History row and the receipt all read it, so
 * a deposit says the same thing everywhere (DESIGN.md → On-chain receive
 * status; iOS `OnchainDepositStatus` parity). The mint's counters outrank the
 * block explorer, and a sighting outranks the quote's expiry: a late deposit
 * is still a deposit.
 */
sealed interface OnchainDepositStatus {
    data object Waiting : OnchainDepositStatus
    data object Expired : OnchainDepositStatus
    data class InMempool(val amount: Long) : OnchainDepositStatus
    data class Confirming(val amount: Long, val confirmations: Int) : OnchainDepositStatus
    data class Adding(val amount: Long) : OnchainDepositStatus
    data class Retrying(val amount: Long, override val needsAttention: Boolean) : OnchainDepositStatus
    data class Received(val amount: Long) : OnchainDepositStatus

    /**
     * The receive sheet's Status value. The amount rides inside it once it is
     * known, so nothing above or below the row appears with the deposit.
     */
    fun sheetValue(format: (Long) -> AmountParts): StatusText = when (this) {
        Waiting -> StatusText("Waiting for deposit", "Waiting for deposit")
        Expired -> StatusText("Expired", "Expired")
        is InMempool -> format(amount).let {
            StatusText("${it.joined} · in mempool", "${it.spoken}, in mempool")
        }
        is Confirming -> format(amount).let {
            val count = confirmationText(confirmations)
            StatusText("${it.joined} · $count", "${it.spoken}, $count")
        }
        is Adding -> format(amount).let {
            StatusText("Adding ${it.joined} to wallet…", "Adding ${it.spoken} to wallet")
        }
        is Retrying -> format(amount).let {
            StatusText("${it.joined} · retrying", "${it.spoken}, retrying")
        }
        is Received -> format(amount).let {
            StatusText("${it.joined} received", "${it.spoken} received")
        }
    }

    /**
     * History's row note and the receipt's Status value. The receipt's hero
     * already shows the amount, so this never repeats it. Null leaves the
     * row's own lifecycle word (Expired, Confirmed) in place.
     */
    val historyText: String?
        get() = when (this) {
            Waiting -> "Waiting for deposit"
            is InMempool -> "In mempool"
            is Confirming -> confirmationText(confirmations)
            is Adding -> "Adding to wallet…"
            is Retrying -> "Retrying"
            Expired, is Received -> null
        }

    val needsAttention: Boolean
        get() = false

    companion object {
        fun resolve(
            amountPaid: Long,
            amountIssued: Long,
            observation: OnchainPaymentObservation?,
            retryState: MintQuoteRetryState,
            isPastExpiry: Boolean,
        ): OnchainDepositStatus {
            if (amountPaid > 0 && amountIssued >= amountPaid) return Received(amountPaid)
            if (amountPaid > amountIssued) {
                val outstanding = amountPaid - amountIssued
                return when (retryState) {
                    MintQuoteRetryState.None -> Adding(outstanding)
                    MintQuoteRetryState.RetryScheduled -> Retrying(outstanding, needsAttention = false)
                    MintQuoteRetryState.NeedsAttention -> Retrying(outstanding, needsAttention = true)
                }
            }
            if (observation != null) {
                val confirmations = observation.confirmations
                return when {
                    confirmations != null && confirmations > 0 -> Confirming(observation.amount, confirmations)
                    observation.confirmed -> Confirming(observation.amount, 1)
                    else -> InMempool(observation.amount)
                }
            }
            return if (isPastExpiry) Expired else Waiting
        }

        private fun confirmationText(confirmations: Int): String =
            if (confirmations == 1) "1 confirmation" else "$confirmations confirmations"
    }
}
