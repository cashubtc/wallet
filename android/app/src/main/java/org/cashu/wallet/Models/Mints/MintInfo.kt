package org.cashu.wallet.Models

import kotlinx.serialization.Serializable

@Serializable
data class MintInfo(
    val url: String,
    val name: String = "Unknown Mint",
    val pubkey: String? = null,
    val description: String? = null,
    val descriptionLong: String? = null,
    val isActive: Boolean = true,
    val balance: Long = 0,
    val iconUrl: String? = null,
    val urls: List<String> = emptyList(),
    val motd: String? = null,
    val serverTimeEpochSeconds: Long? = null,
    val tosUrl: String? = null,
    val softwareName: String? = null,
    val softwareVersion: String? = null,
    val contacts: List<MintContactInfo> = emptyList(),
    val nuts: MintNutSupport = MintNutSupport(),
    val mintMethodSettings: List<MintPaymentMethodSetting> = emptyList(),
    val meltMethodSettings: List<MintPaymentMethodSetting> = emptyList(),
    val units: List<String> = listOf("sat"),
    // NUT-04 mintable units. Empty for records stored before multi-unit landed;
    // effectiveMintUnits falls back to the full unit set until the next refresh.
    val mintUnits: List<String> = emptyList(),
    val supportedMintMethods: List<PaymentMethodKind> = listOf(PaymentMethodKind.Bolt11),
    val supportedMeltMethods: List<PaymentMethodKind> = listOf(PaymentMethodKind.Bolt11),
    val onchainMintConfirmations: Int? = null,
    val lastUpdatedEpochMillis: Long = System.currentTimeMillis(),
) {
    val id: String get() = url

    val effectiveMintUnits: List<String> get() = mintUnits.ifEmpty { units }

    /** Ecash units this mint holds/advertises (send-side gating). */
    val supportsMultipleUnits: Boolean get() = units.size > 1
    val defaultUnit: String get() = defaultOf(units)
    fun resolvedUnit(unit: String?): String =
        if (unit != null && units.contains(unit)) unit else defaultUnit

    /** Units mintable over Lightning per NUT-04 (receive-side gating). */
    val supportsMultipleMintUnits: Boolean get() = effectiveMintUnits.size > 1
    val defaultMintUnit: String get() = defaultOf(effectiveMintUnits)
    fun resolvedMintUnit(unit: String?): String =
        if (unit != null && effectiveMintUnits.contains(unit)) unit else defaultMintUnit

    private fun defaultOf(candidates: List<String>): String = when {
        candidates.contains("sat") -> "sat"
        else -> candidates.sorted().firstOrNull() ?: "sat"
    }
}

@Serializable
data class MintContactInfo(
    val method: String,
    val info: String,
)

@Serializable
data class MintPaymentMethodSetting(
    val method: PaymentMethodKind,
    val unit: String,
    val minAmount: Long? = null,
    val maxAmount: Long? = null,
    val supportsDescription: Boolean? = null,
    val supportsAmountless: Boolean? = null,
)

@Serializable
data class MintNutSupport(
    val nut04: Boolean = false,
    val nut05: Boolean = false,
    val nut07: Boolean = false,
    val nut08: Boolean = false,
    val nut09: Boolean = false,
    val nut10: Boolean = false,
    val nut11: Boolean = false,
    val nut12: Boolean = false,
    val nut14: Boolean = false,
    val nut20: Boolean = false,
    val nut21: Boolean = false,
    val nut22: Boolean = false,
    val nut29: Boolean = false,
)
