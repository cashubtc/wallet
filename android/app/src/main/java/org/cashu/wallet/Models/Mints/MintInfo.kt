package org.cashu.wallet.Models

import kotlinx.serialization.Serializable

@Serializable
data class MintInfo(
    val url: String,
    val name: String = "Unknown Mint",
    val description: String? = null,
    val isActive: Boolean = true,
    val balance: Long = 0,
    val iconUrl: String? = null,
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
