package com.cashu.me.Core

import java.net.URI
import com.cashu.me.Core.CDK.FederationInfo
import com.cashu.me.Models.FederationMintMetadata
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.MintUrlIdentity

internal const val FederationAlreadyExistsMessage = "Federation already exists."

/**
 * The single wallet entry for a registered federation: keyed by CDK's local
 * mint URL, carrying the members so any member URL matches it. [info] is the
 * federation's NUT-06 info when it could be read.
 */
internal fun federationMintEntry(federation: FederationInfo, info: MintInfo?): MintInfo {
    val localUrl = MintUrlIdentity.normalized(federation.localMintUrl)
    val base = info ?: MintInfo(
        url = localUrl,
        name = runCatching { URI(localUrl).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Federation",
        units = federation.units.ifEmpty { listOf("sat") },
    )
    val members = (federation.memberMintUrls.ifEmpty { listOf(localUrl) })
        .map(MintUrlIdentity::normalized)
        .distinctBy { MintUrlIdentity.comparisonKey(it) }
    return base.copy(
        url = localUrl,
        units = (base.units + federation.units).distinct(),
        federation = FederationMintMetadata(
            federationId = federation.federationId,
            memberMintUrls = members,
            memberCount = federation.memberCount,
            signatureThreshold = federation.signatureThreshold,
        ),
        advertisesFederation = true,
    )
}

/**
 * The tracked entry that already stands for [federation]: the same federation
 * id, or any entry addressed by its local or a member URL.
 */
internal fun List<MintInfo>.entryForFederation(federation: FederationInfo): MintInfo? {
    val urls = federation.memberMintUrls + federation.localMintUrl
    return firstOrNull { it.federation?.federationId == federation.federationId }
        ?: firstOrNull { mint -> urls.any(mint::matchesMintUrl) }
}

/**
 * Replace every entry [federation] covers (placeholders created for a member
 * URL by an incoming token, or a plain entry for a member) with [entry],
 * keeping their balance and units. Returns the new list and the entries removed.
 */
internal fun List<MintInfo>.withFederationEntry(
    federation: FederationInfo,
    entry: MintInfo,
): Pair<List<MintInfo>, List<MintInfo>> {
    val urls = federation.memberMintUrls + federation.localMintUrl
    val covered = filter { mint ->
        mint.federation?.federationId == federation.federationId || urls.any(mint::matchesMintUrl)
    }
    val merged = entry.copy(
        units = (covered.flatMap { it.units } + entry.units).distinct(),
        balance = covered.maxOfOrNull { it.balance } ?: entry.balance,
        isActive = covered.firstOrNull()?.isActive ?: entry.isActive,
    )
    val firstIndex = indexOfFirst { it in covered }
    val remaining = filterNot { it in covered }.toMutableList()
    if (firstIndex < 0) remaining += merged else remaining.add(firstIndex.coerceAtMost(remaining.size), merged)
    return remaining to covered
}
