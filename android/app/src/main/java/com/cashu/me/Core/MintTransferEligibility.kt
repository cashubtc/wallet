package com.cashu.me.Core

import com.cashu.me.Core.CDK.mintRemovalUrlsMatch
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.PaymentMethodKind

/**
 * Pure rules for whether ecash can move from one held mint to another
 * (iOS `MintTransferEligibility.swift` parity).
 *
 * A transfer melts at the source to pay a BOLT11 invoice minted at the
 * destination, in sat. Balance is deliberately not a rule here: an empty
 * source is a valid selection, it just has nothing to send.
 */
internal object MintTransferEligibility {
    enum class Blocker { SameMint, SourceCannotSend, DestinationCannotReceive }

    fun blocker(source: MintInfo, destination: MintInfo): Blocker? = when {
        mintRemovalUrlsMatch(source.url, destination.url) -> Blocker.SameMint
        !canSend(source) -> Blocker.SourceCannotSend
        !canReceive(destination) -> Blocker.DestinationCannotReceive
        else -> null
    }

    fun eligible(source: MintInfo, destination: MintInfo): Boolean =
        blocker(source, destination) == null

    /**
     * A mint whose capability was never fetched falls back to its method
     * lists, which default to BOLT11 — the same benefit of the doubt every
     * other flow gives an unfetched mint.
     */
    fun canSend(mint: MintInfo): Boolean =
        mint.bolt11Sat?.canMelt ?: mint.effectiveMeltMethods.contains(PaymentMethodKind.Bolt11)

    fun canReceive(mint: MintInfo): Boolean =
        mint.bolt11Sat?.canMint
            ?: (mint.effectiveMintMethods.contains(PaymentMethodKind.Bolt11) &&
                mint.effectiveMintUnits.contains("sat"))

    /**
     * Amounts both mints advertise they accept: the destination's mint limits
     * intersected with the source's melt limits. Null when they do not overlap.
     * Advisory only — limits may have changed since the last fetch, and the
     * mint is the final judge.
     */
    fun amountRange(source: MintInfo, destination: MintInfo): LongRange? {
        val lower = maxOf(destination.bolt11Sat?.mintMin ?: 1L, source.bolt11Sat?.meltMin ?: 1L, 1L)
        val upper = minOf(
            destination.bolt11Sat?.mintMax ?: Long.MAX_VALUE,
            source.bolt11Sat?.meltMax ?: Long.MAX_VALUE,
        )
        return if (lower <= upper) lower..upper else null
    }
}
