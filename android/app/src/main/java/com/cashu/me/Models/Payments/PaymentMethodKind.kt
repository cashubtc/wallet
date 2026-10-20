package com.cashu.me.Models

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.Serializable

@Serializable(with = PaymentMethodKindSerializer::class)
@ConsistentCopyVisibility
data class PaymentMethodKind private constructor(val rawValue: String) {
    val isCustom: Boolean get() = this != Bolt11 && this != Bolt12 && this != Onchain

    /** Protocol jargon (BOLT11 / BOLT12 / On-chain). Prefer [friendlyTitle] in receive UI. */
    val displayName: String
        get() = when (this) {
            Bolt11 -> "BOLT11"
            Bolt12 -> "BOLT12"
            Onchain -> "On-chain"
            else -> rawValue.split('_', '-').filter { it.isNotEmpty() }
                .joinToString(" ") { it.replaceFirstChar(Char::titlecase) }.ifEmpty { rawValue }
        }

    /**
     * Plain-language title for the receive method picker, in place of protocol
     * jargon ([displayName]). iOS parity with PaymentMethodKind.friendlyTitle.
     */
    val friendlyTitle: String
        get() = when (this) {
            Bolt11 -> "Lightning invoice"
            Bolt12 -> "Reusable invoice"
            Onchain -> "On-chain address"
            else -> displayName
        }

    /**
     * One-line descriptor shown beneath [friendlyTitle] in the receive method
     * picker. iOS parity with PaymentMethodKind.friendlyDescriptor.
     */
    val friendlyDescriptor: String
        get() = when (this) {
            // Match iOS ReceiveMethodOption picker rows (what the sheet actually
            // shows), not the dormant fixed-amount reusable copy.
            Bolt11 -> "One-time, instant"
            Bolt12 -> "Any amount, paid many times"
            Onchain -> "Slower, for larger amounts"
            else -> "Pay using this mint’s payment method"
        }

    /**
     * Verb-phrase for the create CTA on the receive amount screen.
     * iOS parity with PaymentMethodKind.createActionTitle.
     */
    val createActionTitle: String
        get() = when (this) {
            Bolt11 -> "Create invoice"
            Bolt12 -> "Create invoice"
            Onchain -> "Create address"
            else -> "Create request"
        }

    val symbol: String
        get() = when (this) {
            Bolt11 -> "\u26A1"
            Bolt12 -> "\uD83D\uDD17"
            Onchain -> "\u20BF"
            else -> "↔"
        }

    val requestDisplayName: String
        get() = when (this) {
            Bolt11 -> "Invoice"
            Bolt12 -> "Invoice"
            Onchain -> "Address"
            else -> "Payment request"
        }

    val sortOrder: Int
        get() = when (this) {
            Bolt11 -> 0
            Bolt12 -> 1
            Onchain -> 2
            else -> 3
        }

    /** True when a mint quote for this rail requires a positive amount up front. */
    val requiresMintAmount: Boolean
        get() = this != Bolt12 && this != Onchain

    val supportsOptionalMintAmount: Boolean
        get() = this == Bolt12

    companion object {
        val Bolt11 = PaymentMethodKind("bolt11")
        val Bolt12 = PaymentMethodKind("bolt12")
        val Onchain = PaymentMethodKind("onchain")
        private val pattern = Regex("[a-z0-9_-]{1,32}")

        fun fromRaw(value: String?): PaymentMethodKind? = when (value?.lowercase()) {
            "bolt11" -> Bolt11
            "bolt12" -> Bolt12
            "onchain" -> Onchain
            else -> value?.takeIf(pattern::matches)?.let(::PaymentMethodKind)
        }

        fun ordered(methods: List<PaymentMethodKind>): List<PaymentMethodKind> =
            methods.distinct().sortedWith(compareBy({ it.sortOrder }, { it.rawValue }))
    }
}

object PaymentMethodKindSerializer : KSerializer<PaymentMethodKind> {
    override val descriptor = PrimitiveSerialDescriptor("PaymentMethodKind", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: PaymentMethodKind) = encoder.encodeString(value.rawValue)
    override fun deserialize(decoder: Decoder): PaymentMethodKind =
        PaymentMethodKind.fromRaw(decoder.decodeString()) ?: throw SerializationException("Invalid payment method")
}
