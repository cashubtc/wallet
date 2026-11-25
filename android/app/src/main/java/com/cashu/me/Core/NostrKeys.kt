package com.cashu.me.Core

import org.cashudevkit.FfiException
import org.cashudevkit.nostrGenerateSecretKey
import org.cashudevkit.nostrGetPubkey

/**
 * secp256k1 key operations used for the wallet's Nostr and P2PK identities.
 *
 * Production code uses [CdkNostrKeys]. The seam exists so JVM unit tests,
 * which cannot load CDK's Android-only native library, can supply a fake.
 */
interface NostrKeys {
    /** Returns a new random secret key as 64 lowercase hex characters. */
    fun generateSecretKeyHex(): String

    /**
     * Returns the 64-character hex x-only public key for [secretKeyHex].
     *
     * @throws IllegalArgumentException if [secretKeyHex] is not a valid secp256k1 secret key.
     */
    fun publicKeyHex(secretKeyHex: String): String
}

object CdkNostrKeys : NostrKeys {
    override fun generateSecretKeyHex(): String = nostrGenerateSecretKey()

    override fun publicKeyHex(secretKeyHex: String): String =
        try {
            nostrGetPubkey(secretKeyHex)
        } catch (error: FfiException) {
            throw IllegalArgumentException("Invalid Nostr private key.", error)
        }
}
