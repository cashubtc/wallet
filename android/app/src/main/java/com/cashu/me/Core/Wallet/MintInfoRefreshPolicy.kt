package com.cashu.me.Core

import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.MintUrlIdentity
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Coordinates passive NUT-06 mint metadata refreshes with a freshness TTL and
 * semantic diffing (iOS `MintService.refreshMintInfo` parity).
 *
 * Unchanged mints record their last-checked timestamp in memory so repeated
 * Mints tab visits respect the TTL without rewriting persistent storage or
 * re-emitting UI state.
 */
internal class MintInfoRefresher(
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) {
    private val lastCheckedAtMs = ConcurrentHashMap<String, Long>()

    fun recordChecked(mintUrl: String, checkedAtEpochMillis: Long = nowEpochMillis()) {
        lastCheckedAtMs[freshnessKey(mintUrl)] = checkedAtEpochMillis
    }

    fun forget(mintUrl: String) {
        lastCheckedAtMs.remove(freshnessKey(mintUrl))
    }

    fun clear() {
        lastCheckedAtMs.clear()
    }

    fun shouldRefresh(
        mint: MintInfo,
        force: Boolean,
        now: Long = nowEpochMillis(),
    ): Boolean {
        if (force) return true
        val lastChecked = lastCheckedAtMs[freshnessKey(mint.url)]
        val lastFreshness = maxOf(mint.lastUpdatedEpochMillis, lastChecked ?: Long.MIN_VALUE)
        if (lastFreshness > now) return true
        return now - lastFreshness >= ttlMillis
    }

    suspend fun refresh(
        mints: List<MintInfo>,
        force: Boolean = false,
        fetchMintInfo: suspend (String) -> MintInfo?,
        onFetchFailed: (String, Throwable) -> Unit = { _, _ -> },
    ): Map<String, MintInfo> {
        if (mints.isEmpty()) return emptyMap()

        val updatesByUrl = linkedMapOf<String, MintInfo>()
        for (mint in mints) {
            currentCoroutineContext().ensureActive()
            if (!shouldRefresh(mint, force = force)) continue

            val fetched = try {
                fetchMintInfo(mint.url)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                onFetchFailed(mint.url, error)
                continue
            } ?: continue

            val checkedAt = nowEpochMillis()
            recordChecked(mint.url, checkedAt)
            val merged = mergeIfChanged(
                existing = mint,
                fetched = fetched,
                nowEpochMillis = checkedAt,
            )
            if (merged != null) {
                updatesByUrl[mint.url] = merged
            }
        }
        return updatesByUrl
    }

    private fun freshnessKey(url: String): String =
        MintUrlIdentity.comparisonKey(url) ?: MintUrlIdentity.normalized(url)

    companion object {
        const val DEFAULT_TTL_MILLIS: Long = 5 * 60 * 1_000L

        /**
         * Merges [fetched] metadata into [existing], returning null when no
         * semantic field changed (ignoring [MintInfo.lastUpdatedEpochMillis]).
         */
        fun mergeIfChanged(
            existing: MintInfo,
            fetched: MintInfo,
            nowEpochMillis: Long,
        ): MintInfo? {
            val candidate = existing.copy(
                name = fetched.name.takeUnless { it == "Unknown Mint" } ?: existing.name,
                description = fetched.description ?: existing.description,
                iconUrl = fetched.iconUrl ?: existing.iconUrl,
                units = fetched.units.ifEmpty { existing.units },
                mintUnits = fetched.mintUnits.ifEmpty { existing.mintUnits },
                // A live report is authoritative — including a reported-empty
                // list (the mint dropped a rail); only an unknown (unfetched)
                // value keeps the previously stored one.
                supportedMintMethods = fetched.supportedMintMethods ?: existing.supportedMintMethods,
                supportedMeltMethods = fetched.supportedMeltMethods ?: existing.supportedMeltMethods,
                // Live NUT-04 advertisement is authoritative, including false
                // (the mint dropped description support).
                supportsBolt12MintDescription = fetched.supportsBolt12MintDescription,
                // Same for NUT-05 amountless; only an unread value keeps the stored one.
                supportsAmountlessBolt11Melt = fetched.supportsAmountlessBolt11Melt
                    ?: existing.supportsAmountlessBolt11Melt,
                // Likewise authoritative once reported: a mint that pauses a
                // direction or drops the rail must stop being offered for
                // transfers. Only an unreported value keeps the stored one.
                bolt11Sat = fetched.bolt11Sat ?: existing.bolt11Sat,
                contacts = fetched.contacts.ifEmpty { existing.contacts },
                tosUrl = fetched.tosUrl ?: existing.tosUrl,
                software = fetched.software ?: existing.software,
                descriptionLong = fetched.descriptionLong ?: existing.descriptionLong,
                motd = fetched.motd ?: existing.motd,
                nutSupport = fetched.nutSupport,
                balance = existing.balance,
                isActive = existing.isActive,
                lastUpdatedEpochMillis = existing.lastUpdatedEpochMillis,
            )
            if (candidate.hasSameContentAs(existing)) return null
            return candidate.copy(lastUpdatedEpochMillis = nowEpochMillis)
        }
    }
}
