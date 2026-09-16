package com.cashu.me.Core

/**
 * Signed BOLT12 payer proof (`lnp1…`) vs the Lightning preimage CDK stores
 * today in `Transaction.payment_proof`. The encodings stay separate so a
 * future FFI field or `create_bolt12_payer_proof(quote_id)` bind does not
 * overwrite NUT-25 preimages. Verify opens lnproof.space, which decodes the
 * proof entirely on-device.
 */
object Bolt12PayerProof {
    const val BECH32_PREFIX = "lnp1"
    const val EXPLORER_HOST = "https://lnproof.space/"
    /** Bech32 charset (BIP-173). Mixed-case payloads and non-charset bytes are not proofs. */
    private const val BECH32_CHARSET = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    /** Bech32 checksum is 6 characters; shorter payloads are not proofs. */
    private const val MINIMUM_PAYLOAD_COUNT = 6

    fun isSigned(value: String?): Boolean {
        val proof = normalized(value) ?: return false
        val payload = proof.removePrefix(BECH32_PREFIX)
        return payload.length >= MINIMUM_PAYLOAD_COUNT && payload.all { it in BECH32_CHARSET }
    }

    fun verifyUrl(value: String): String? {
        val proof = normalized(value)?.takeIf(::isSigned) ?: return null
        return EXPLORER_HOST + proof
    }

    data class Split(
        val preimage: String?,
        val payerProof: String?,
    )

    /** Classify a CDK `payment_proof` string. Hex (or any non-`lnp1`) stays
     *  the Lightning preimage; `lnp1…` is the signed payer proof. */
    fun split(paymentProof: String?): Split {
        val value = paymentProof?.trim()?.takeIf { it.isNotEmpty() } ?: return Split(null, null)
        return if (isSigned(value)) Split(preimage = null, payerProof = normalized(value))
        else Split(preimage = value, payerProof = null)
    }

    /** Value shown on the Payment Proof receipt row: signed proof when
     *  present, otherwise the hex preimage. */
    fun displayedProof(payerProof: String?, preimage: String?): String? =
        normalized(payerProof)?.takeIf(::isSigned) ?: preimage

    private fun normalized(value: String?): String? {
        val proof = value?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        if (!proof.startsWith(BECH32_PREFIX)) return null
        return proof
    }
}
