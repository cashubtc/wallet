package com.cashu.me.Core.CDK

import com.cashu.me.Core.toDomainUnit
import com.cashu.me.Models.MintUrlIdentity
import org.cashudevkit.FederationWalletInfo as CdkFederationWalletInfo

/** The registered federation [mintUrl] belongs to, matched by local or member URL. */
internal fun List<FederationInfo>.federationFor(mintUrl: String?): FederationInfo? {
    val key = MintUrlIdentity.comparisonKey(mintUrl) ?: return null
    return firstOrNull { federation ->
        (federation.memberMintUrls + federation.localMintUrl)
            .any { MintUrlIdentity.comparisonKey(it) == key }
    }
}

internal fun CdkFederationWalletInfo.toFederationInfo(): FederationInfo = FederationInfo(
    federationId = federationId,
    localMintUrl = MintUrlIdentity.normalized(walletConfig.localMintUrl.url),
    memberMintUrls = memberMintUrls.map { MintUrlIdentity.normalized(it.url) }.distinct(),
    memberCount = memberCount.toInt(),
    signatureThreshold = signatureThreshold.toInt(),
    consensusThreshold = consensusThreshold.toInt(),
    units = units.map { it.toDomainUnit() }.distinct(),
)
