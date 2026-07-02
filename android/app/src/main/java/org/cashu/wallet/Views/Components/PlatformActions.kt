package org.cashu.wallet.Views.Components

fun cashuTokenShareContent(token: String): String {
    val trimmed = token.trim()
    return if (trimmed.startsWith("cashu:", ignoreCase = true)) trimmed else "cashu:$trimmed"
}
