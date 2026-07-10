package org.cashu.wallet.Models

import kotlinx.serialization.Serializable

@Serializable
data class SendTokenResult(
    val token: String,
    val fee: Long,
)

@Serializable
data class PendingToken(
    val tokenId: String,
    val token: String,
    val amount: Long,
    val fee: Long,
    val dateEpochMillis: Long,
    val mintUrl: String,
    val memo: String? = null,
    // Amount is denominated in this unit (a $5.00 token stores 500, unit "usd").
    val unit: String = "sat",
) {
    val id: String get() = tokenId
}

@Serializable
data class PendingReceiveToken(
    val tokenId: String,
    val token: String,
    val amount: Long,
    val dateEpochMillis: Long,
    val mintUrl: String,
    // Defaults keep records written by older Android versions decodable.
    val unit: String = "sat",
    // Non-null marks a NUT-18 payment held by the foreground listener. An
    // empty value means the payload did not carry a request id.
    val cashuRequestId: String? = null,
    val memo: String? = null,
) {
    val id: String get() = tokenId
}

@Serializable
data class ClaimedToken(
    val tokenId: String,
    val token: String,
    val amount: Long,
    val fee: Long,
    val dateEpochMillis: Long,
    val mintUrl: String,
    val memo: String? = null,
    val claimedDateEpochMillis: Long,
) {
    val id: String get() = tokenId
}
