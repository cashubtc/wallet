package com.cashu.me.Core

import com.cashu.me.Core.Protocols.CurrencyAmount
import com.cashu.me.Core.Protocols.CurrencyRegistry
import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.MintQuoteState
import com.cashu.me.Models.PaymentMethodKind
import java.net.URI

data class DepositRecoveryRequest(
    val mintUrl: String,
    val quoteId: String,
    val method: PaymentMethodKind,
    val unit: String = "sat",
    val searchLimit: UInt = 1_000u,
) {
    fun validated(): DepositRecoveryRequest {
        val text = mintUrl.trim().let { if ("://" in it) it else "https://$it" }
        val url = runCatching { URI(text) }.getOrNull()
        require(url != null && !url.host.isNullOrBlank() && url.userInfo == null &&
            url.rawQuery == null && url.rawFragment == null && (url.port == -1 || url.port in 1..65535) &&
            (url.scheme == "https" || (url.scheme == "http" && url.host in listOf("localhost", "127.0.0.1", "[::1]", "::1", "10.0.2.2")))) {
            "Enter a valid HTTPS mint URL without a login, query, or fragment."
        }
        val id = quoteId.trim()
        require(id.isNotEmpty() && id.toByteArray().size <= 1_024 && id.none { it.isWhitespace() || it.isISOControl() }) {
            "Enter the deposit's quote ID, without spaces."
        }
        require(unit.lowercase() in units && searchLimit in searchLimits) { "Choose a supported currency and search depth." }
        return copy(mintUrl = text.trimEnd('/'), quoteId = id, unit = unit.lowercase())
    }

    companion object {
        val units = listOf("sat", "msat", "usd", "eur")
        val searchLimits = listOf(1_000u, 10_000u)
    }
}

data class DepositRecoveryLookup(val quote: MintQuoteInfo, val hasSigningKey: Boolean)

enum class DepositRecoveryStatus { Recovered, KeyNotFound, Waiting, AlreadyClaimed, Pending, Expired }

data class DepositRecoveryResult(
    val status: DepositRecoveryStatus,
    val unit: String,
    val amount: Long = 0,
    val remaining: Long = 0,
) {
    val message: String get() = when (status) {
        DepositRecoveryStatus.Recovered -> {
            val value = CurrencyAmount(amount, CurrencyRegistry.currencyForMintUnit(unit)).formatted()
            if (remaining > 0) "Recovered $value. Remaining funds are pending."
            else "Recovered $value."
        }
        DepositRecoveryStatus.KeyNotFound -> "No matching key. Try a deeper search or restore the original seed phrase. Random keys need a database backup."
        DepositRecoveryStatus.Waiting -> "No payment yet. We'll keep checking."
        DepositRecoveryStatus.AlreadyClaimed -> "Already claimed. Use Restore to recover the ecash."
        DepositRecoveryStatus.Pending -> "Recovery pending. We'll retry automatically."
        DepositRecoveryStatus.Expired -> "This unpaid deposit has expired."
    }

    companion object {
        fun from(quote: MintQuoteInfo, newlyIssued: Long = 0, retryPending: Boolean = false): DepositRecoveryResult = when {
            newlyIssued > 0 -> DepositRecoveryResult(DepositRecoveryStatus.Recovered, quote.unit, newlyIssued, quote.mintableAmount)
            quote.mintableAmount > 0 || retryPending -> DepositRecoveryResult(DepositRecoveryStatus.Pending, quote.unit)
            quote.hasSettledPayment || quote.state == MintQuoteState.Issued -> DepositRecoveryResult(DepositRecoveryStatus.AlreadyClaimed, quote.unit)
            quote.isExpired && quote.paymentMethod == PaymentMethodKind.Bolt11 -> DepositRecoveryResult(DepositRecoveryStatus.Expired, quote.unit)
            else -> DepositRecoveryResult(DepositRecoveryStatus.Waiting, quote.unit)
        }
    }
}
