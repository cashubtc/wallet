package com.cashu.me.Core.NfcReceive

import com.cashu.me.Models.MintUrlIdentity
import com.cashu.me.Models.CashuRequest
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.mintMatching

internal enum class NfcSettlementRoute { Direct, Foreign }

internal fun CashuRequest.shouldOfferNfcReceive(): Boolean = isEcashRequest || receivedPayments.isEmpty()

internal fun CashuRequest.canReceiveByNfc(): Boolean =
    shouldOfferNfcReceive() && amount?.let { it > 0 } == true

internal fun validateNfcReceiveTerms(
    request: CashuRequest,
    sourceMint: String,
    tokenUnit: String,
    grossAmount: Long,
    settlementMint: String,
    trackedMints: List<MintInfo> = emptyList(),
): NfcSettlementRoute {
    require(grossAmount > 0) { "The received token has no value." }
    require(tokenUnit.equals(request.unit, ignoreCase = true)) {
        "Expected ${request.unit.uppercase()} but received ${tokenUnit.uppercase()}."
    }
    request.amount?.takeIf { it > 0 }?.let { required ->
        require(grossAmount >= required) { "Expected at least $required ${request.unit}." }
    }
    // Federation member URLs all name one tracked mint; compare by that entry.
    fun canonical(url: String): String =
        normalizeNfcMint(trackedMints.mintMatching(url)?.url ?: url)
    val source = canonical(sourceMint)
    val target = canonical(settlementMint)
    val allowed = request.mints.map(::canonical)
    if (allowed.isNotEmpty()) {
        require(source in allowed) { "This request only accepts ecash from the selected mint." }
        return NfcSettlementRoute.Direct
    }
    if (source == target) return NfcSettlementRoute.Direct
    require(tokenUnit.equals("sat", ignoreCase = true)) {
        "Foreign-mint conversion is currently available for sat requests only."
    }
    return NfcSettlementRoute.Foreign
}

internal fun normalizeNfcMint(url: String): String = MintUrlIdentity.normalized(url)
