package com.cashu.me.Models

import kotlinx.serialization.Serializable
import com.cashu.me.Core.TokenParser

@Serializable
data class TokenInfo(
    val amount: Long,
    val mint: String,
    val unit: String,
    val memo: String?,
    val proofCount: Int,
) {
    companion object {
        fun parse(tokenString: String): TokenInfo? = TokenParser.tokenInfo(tokenString)
    }
}
