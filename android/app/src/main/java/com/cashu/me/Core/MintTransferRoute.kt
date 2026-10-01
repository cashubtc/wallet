package com.cashu.me.Core

import com.cashu.me.Core.CDK.mintRemovalUrlsMatch
import com.cashu.me.Models.MintInfo

/**
 * Which two held mints a transfer runs between, and the rules for choosing
 * and changing them (iOS `MintTransferRoute.swift` parity) — extracted for
 * unit testing.
 */
internal data class MintTransferRoute(
    val sourceMintUrl: String,
    val destinationMintUrl: String,
) {
    /** One end of the route. */
    enum class Slot { Source, Destination }

    val swapped: MintTransferRoute
        get() = MintTransferRoute(sourceMintUrl = destinationMintUrl, destinationMintUrl = sourceMintUrl)

    /**
     * Put [mintUrl] in one slot. Choosing the mint that sits in the other slot
     * swaps the two, so a pick never dead-ends on "already in use".
     */
    fun choosing(mintUrl: String, slot: Slot): MintTransferRoute = when (slot) {
        Slot.Source ->
            if (mintRemovalUrlsMatch(mintUrl, destinationMintUrl)) swapped else copy(sourceMintUrl = mintUrl)
        Slot.Destination ->
            if (mintRemovalUrlsMatch(mintUrl, sourceMintUrl)) swapped else copy(destinationMintUrl = mintUrl)
    }

    companion object {
        /**
         * The pair the screen opens on, or null when the wallet holds fewer
         * than two mints.
         *
         * Opened from the Mints list it moves the largest balance toward the
         * default mint — the likeliest intent, and one tap on the arrow away
         * from the other. Opened from a mint, that mint is the source when it
         * has something to send and the destination when it is empty.
         */
        fun initial(
            mints: List<MintInfo>,
            activeMintUrl: String?,
            openedFromMintUrl: String? = null,
        ): MintTransferRoute? {
            if (mints.size < 2) return null

            fun source(excluding: MintInfo?): MintInfo? {
                val candidates = mints.filterNot { same(it.url, excluding?.url) }
                val funded = candidates.filter { it.balance > 0 && MintTransferEligibility.canSend(it) }
                // `maxByOrNull` keeps the first of equals, so list order breaks a tie.
                return funded.maxByOrNull { it.balance }
                    ?: candidates.firstOrNull(MintTransferEligibility::canSend)
                    ?: candidates.firstOrNull()
            }

            fun destination(excluding: MintInfo): MintInfo? {
                val candidates = mints.filterNot { same(it.url, excluding.url) }
                val receiving = candidates.filter(MintTransferEligibility::canReceive)
                return receiving.firstOrNull { same(it.url, activeMintUrl) }
                    ?: receiving.firstOrNull()
                    ?: candidates.firstOrNull()
            }

            mints.firstOrNull { same(it.url, openedFromMintUrl) }?.let { opened ->
                if (opened.balance > 0 && MintTransferEligibility.canSend(opened)) {
                    val destination = destination(excluding = opened) ?: return null
                    return MintTransferRoute(sourceMintUrl = opened.url, destinationMintUrl = destination.url)
                }
                val source = source(excluding = opened) ?: return null
                return MintTransferRoute(sourceMintUrl = source.url, destinationMintUrl = opened.url)
            }

            val source = source(excluding = null) ?: return null
            val destination = destination(excluding = source) ?: return null
            return MintTransferRoute(sourceMintUrl = source.url, destinationMintUrl = destination.url)
        }

        private fun same(url: String, other: String?): Boolean =
            other != null && mintRemovalUrlsMatch(url, other)
    }
}

/** What stands between the entered amount and the Continue button. */
internal sealed interface MintTransferEntry {
    data object Empty : MintTransferEntry

    data class Blocked(val blocker: MintTransferEligibility.Blocker) : MintTransferEntry

    /** More than the source holds. Fees are settled on the review step. */
    data object OverBalance : MintTransferEntry

    data object Ready : MintTransferEntry

    companion object {
        fun validation(amount: Long, source: MintInfo, destination: MintInfo): MintTransferEntry {
            MintTransferEligibility.blocker(source, destination)?.let { return Blocked(it) }
            if (amount <= 0) return Empty
            return if (amount > source.balance) OverBalance else Ready
        }

        /**
         * True when the whole source balance is typed in by hand. The fee comes
         * on top of the amount, so that much can never leave; a Max quote has
         * already allowed for it.
         */
        fun isWholeBalance(entry: MintTransferEntry, amount: Long, source: MintInfo, holdsMaxQuote: Boolean): Boolean =
            entry == Ready && !holdsMaxQuote && amount == source.balance
    }
}
