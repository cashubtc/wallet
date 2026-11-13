package com.cashu.me.Core.Services

import com.cashu.me.Core.PaymentRequestDecoder
import com.cashu.me.Core.PaymentRequestDecodeResult
import com.cashu.me.Core.LightningRequestParser
import com.cashu.me.Core.WalletManager
import com.cashu.me.Core.selectMintForCashuPaymentRequest

data class NFCPaymentState(
    val isReading: Boolean = false,
    val isAuthorizing: Boolean = false,
    val request: String? = null,
    val preparedToken: String? = null,
    val error: String? = null,
)

sealed interface NFCPaymentInput {
    data class CashuRequest(val summary: com.cashu.me.Core.CashuPaymentRequestSummary) : NFCPaymentInput
    data class LightningRequest(val request: String) : NFCPaymentInput
}

object NFCPaymentInputDecoder {
    fun decode(payload: String): NFCPaymentInput {
        val trimmed = payload.trim()
        require(trimmed.isNotEmpty()) { "Empty payment request." }

        val lightningFallback = PaymentRequestDecoder.encodedLightningRequest(trimmed)
            ?: runCatching { LightningRequestParser.parse(trimmed).request }.getOrNull()
        val cashuRequest = PaymentRequestDecoder.cashuPaymentRequestSummary(trimmed)
        if (cashuRequest != null && (cashuRequest.isSatUnit || lightningFallback == null)) {
            return NFCPaymentInput.CashuRequest(cashuRequest)
        }

        if (lightningFallback != null) return NFCPaymentInput.LightningRequest(lightningFallback)

        throw IllegalArgumentException("No supported NFC payment request.")
    }
}

class NFCPaymentService(
    private val walletManager: WalletManager,
    private val policy: ContactlessSpendPolicy,
    private val isActive: () -> Boolean,
    private val authenticate: suspend () -> Boolean,
    private val showPayment: suspend (String) -> Unit,
) {
    fun decodeRequest(payload: String): PaymentRequestDecodeResult =
        PaymentRequestDecoder.decode(payload, includeCashuPaymentRequests = true, preferCashuPaymentRequests = true)

    fun decodePaymentInput(payload: String): NFCPaymentInput = NFCPaymentInputDecoder.decode(payload)

    suspend fun pay(payload: String, deliver: suspend (String) -> Unit) {
        when (val input = decodePaymentInput(payload)) {
            is NFCPaymentInput.CashuRequest -> payCashuRequest(input.summary, deliver)
            is NFCPaymentInput.LightningRequest -> {
                throw IllegalStateException("Lightning NFC requests should be routed to Send.")
            }
        }
    }

    fun tokenRecord(token: String) = NDEFTextRecordCoder.encode(token)

    private suspend fun payCashuRequest(summary: com.cashu.me.Core.CashuPaymentRequestSummary, deliver: suspend (String) -> Unit) {
        check(isActive() && walletManager.state.value.isRuntimeReady) { ContactlessErrors.Inactive }
        val amount = summary.amount?.takeIf { it > 0 }
            ?: throw IllegalArgumentException("Cashu payment request requires an amount.")
        require(summary.isSatUnit) { "Only sat Cashu payment requests are supported." }
        val state = walletManager.state.value
        val selectedMint = selectMintForCashuPaymentRequest(
            request = summary,
            mints = state.mints,
            selectedMintUrl = state.activeMint?.url,
            activeMintUrl = state.activeMint?.url,
            amountSats = amount,
        ) ?: throw IllegalArgumentException("No compatible mint has enough balance.")

        walletManager.sendContactlessToken(
            amount = amount,
            mintUrl = selectedMint.url,
            callbacks = ContactlessSendCallbacks(
                approve = { total ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        val host = runCatching { java.net.URI(selectedMint.url).host }.getOrNull() ?: selectedMint.url
                        val memo = summary.description?.lineSequence()?.joinToString(" ")?.take(120) ?: "None"
                        showPayment("Amount: $amount sat\nMint: $host\nMemo: $memo\nTotal: $total sat")
                        policy.authorizeAndRecord(total, isActive, authenticate)
                    }
                },
                deliver = { token ->
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main.immediate) {
                        check(isActive()) { ContactlessErrors.Inactive }
                        deliver(token)
                    }
                },
            ),
        )
    }
}
