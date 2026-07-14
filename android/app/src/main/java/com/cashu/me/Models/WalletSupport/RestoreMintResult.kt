package com.cashu.me.Models

import kotlinx.serialization.Serializable

@Serializable
data class RestoreMintResult(
    val mintUrl: String,
    val mintName: String,
    /** Mint logo URL from CDK `fetchMintInfo` (iOS RestoreMintResult.iconUrl). */
    val iconUrl: String? = null,
    val spent: Long,
    val unspent: Long,
    val pending: Long,
) {
    val id: String get() = mintUrl
    val totalRecovered: Long get() = unspent + pending
}
