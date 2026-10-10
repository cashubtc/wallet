package com.cashu.me.ui.send

import com.cashu.me.Core.PaymentRequestDecodeResult
import com.cashu.me.Core.PaymentRequestDecoder
import com.cashu.me.Core.TokenParser
import com.cashu.me.Core.compatibleMintsForCashuPaymentRequest
import com.cashu.me.Core.isAmountlessBolt11
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.PaymentMethodKind

/** Shown only when no held mint advertises NUT-05 `amountless` (iOS parity). */
internal const val AmountlessBolt11Hint =
    "None of your mints can pay invoices without an amount. Ask for one with the amount set."

internal sealed interface SendDestinationResolution {
    data class Hint(val message: String) : SendDestinationResolution
    data class Melt(
        val request: String,
        val decoded: PaymentRequestDecodeResult,
        val knownAmount: Long?,
        val requiresAmountEntry: Boolean,
    ) : SendDestinationResolution
    data class CashuRequest(
        val request: String,
        val decoded: PaymentRequestDecodeResult.CashuPaymentRequest,
        val knownAmount: Long?,
        val requiresAmountEntry: Boolean,
    ) : SendDestinationResolution
    data class EcashToken(val token: String) : SendDestinationResolution
    data object Unrecognized : SendDestinationResolution
}

internal fun resolveSendDestination(
    raw: String,
    walletMints: List<MintInfo>,
): SendDestinationResolution {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return SendDestinationResolution.Unrecognized
    var decoded = PaymentRequestDecoder.decode(
        trimmed,
        includeCashuPaymentRequests = true,
        preferCashuPaymentRequests = true,
    )
    var request = trimmed
    if (decoded is PaymentRequestDecodeResult.CashuPaymentRequest &&
        compatibleMintsForCashuPaymentRequest(decoded.summary, walletMints).isEmpty()
    ) {
        val fallback = PaymentRequestDecoder.decode(trimmed)
        if (fallback !is PaymentRequestDecodeResult.Unrecognized) {
            decoded = fallback
            request = PaymentRequestDecoder.encodedLightningRequest(trimmed) ?: trimmed
        }
    }
    return when (decoded) {
        is PaymentRequestDecodeResult.Bolt11 -> {
            // An amountless invoice opens amount entry, like a BOLT12 offer —
            // unless no held mint can pay one, which would dead-end the quote.
            if (decoded.isAmountlessBolt11 &&
                walletMints.none { it.canMelt(PaymentMethodKind.Bolt11, amountless = true) }
            ) {
                SendDestinationResolution.Hint(AmountlessBolt11Hint)
            } else {
                val known = decoded.amountSats?.takeIf { it > 0L }
                SendDestinationResolution.Melt(
                    request = PaymentRequestDecoder.encodedLightningRequest(request) ?: request,
                    decoded = decoded,
                    knownAmount = known,
                    requiresAmountEntry = known == null,
                )
            }
        }
        is PaymentRequestDecodeResult.Bolt12 -> {
            val known = decoded.amountSats?.takeIf { it > 0L }
            SendDestinationResolution.Melt(
                request = PaymentRequestDecoder.encodedLightningRequest(request) ?: request,
                decoded = decoded,
                knownAmount = known,
                requiresAmountEntry = known == null,
            )
        }
        is PaymentRequestDecodeResult.LightningAddress,
        is PaymentRequestDecodeResult.Onchain -> SendDestinationResolution.Melt(
            request = request,
            decoded = decoded,
            knownAmount = null,
            requiresAmountEntry = true,
        )
        is PaymentRequestDecodeResult.CashuPaymentRequest -> {
            val known = decoded.summary.amount?.takeIf { it > 0 }
            SendDestinationResolution.CashuRequest(
                request = request,
                decoded = decoded,
                knownAmount = known,
                requiresAmountEntry = decoded.summary.isSatUnit && known == null,
            )
        }
        PaymentRequestDecodeResult.Unrecognized -> {
            TokenParser.extractToken(trimmed)
                ?.let(SendDestinationResolution::EcashToken)
                ?: SendDestinationResolution.Unrecognized
        }
    }
}
