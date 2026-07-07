package org.cashu.wallet.Core

import java.security.MessageDigest

fun stablePendingReceiveTokenId(token: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(token.trim().toByteArray())
    return digest.joinToString("") { byte -> "%02x".format(byte) }
}
