package com.cashu.me.Models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class PaymentMethodKind {
    @SerialName("bolt11")
    Bolt11,

    @SerialName("bolt12")
    Bolt12,

    @SerialName("onchain")
    Onchain;

    val rawValue: String
        get() = when (this) {
            Bolt11 -> "bolt11"
            Bolt12 -> "bolt12"
            Onchain -> "onchain"
        }

    /** Protocol jargon (BOLT11 / BOLT12 / On-chain). Prefer [friendlyTitle] in receive UI. */
    val displayName: String
        get() = when (this) {
            Bolt11 -> "BOLT11"
            Bolt12 -> "BOLT12"
            Onchain -> "On-chain"
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
        }

    val symbol: String
        get() = when (this) {
            Bolt11 -> "\u26A1"
            Bolt12 -> "\uD83D\uDD17"
            Onchain -> "\u20BF"
        }

    val requestDisplayName: String
        get() = when (this) {
            Bolt11 -> "Invoice"
            Bolt12 -> "Invoice"
            Onchain -> "Address"
        }

    val sortOrder: Int
        get() = when (this) {
            Bolt11 -> 0
            Bolt12 -> 1
            Onchain -> 2
        }

    /** True when a mint quote for this rail requires a positive amount up front. */
    val requiresMintAmount: Boolean
        get() = this != Bolt12 && this != Onchain

    val supportsOptionalMintAmount: Boolean
        get() = this == Bolt12

    companion object {
        fun fromRaw(value: String?): PaymentMethodKind? = when (value?.lowercase()) {
            "bolt11" -> Bolt11
            "bolt12" -> Bolt12
            "onchain" -> Onchain
            else -> null
        }
    }
}
