package org.cashu.wallet.Core

import org.cashu.wallet.Models.MintContactInfo
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.MintPaymentMethodSetting
import org.cashu.wallet.Models.PaymentMethodKind

fun mintCapabilitySummary(mint: MintInfo): String {
    val capabilities = buildList {
        if (mint.supportedMintMethods.any { it != PaymentMethodKind.Onchain } ||
            mint.supportedMeltMethods.any { it != PaymentMethodKind.Onchain }
        ) {
            add("Lightning")
        }
        if (mint.supportedMintMethods.contains(PaymentMethodKind.Onchain) ||
            mint.supportedMeltMethods.contains(PaymentMethodKind.Onchain)
        ) {
            add("On-chain")
        }
        if (mint.nuts.nut10 || mint.nuts.nut11) add("Locked ecash")
        if (mint.nuts.nut14) add("HTLC")
        if (mint.nuts.nut20) add("WebSockets")
    }
    return capabilities.distinct().joinToString().ifBlank { "Basic ecash" }
}

fun mintPaymentMethodSettingLabel(setting: MintPaymentMethodSetting): String {
    val parts = mutableListOf(setting.unit.uppercase())
    setting.minAmount?.let { parts += "min $it" }
    setting.maxAmount?.let { parts += "max $it" }
    if (setting.supportsDescription == true) parts += "description"
    if (setting.supportsAmountless == true) parts += "amountless"
    return parts.joinToString(" · ")
}

fun mintContactTarget(contact: MintContactInfo): String? {
    val method = contact.method.lowercase()
    val info = contact.info.trim()
    return when (method) {
        "email", "mail" -> "mailto:$info"
        "web", "website", "url" -> info.withHttpsFallback()
        "twitter", "x" -> if (info.startsWith("http")) info else "https://x.com/${info.removePrefix("@")}"
        "telegram" -> if (info.startsWith("http")) info else "https://t.me/${info.removePrefix("@")}"
        "nostr" -> info.takeIf { it.startsWith("nostr:", ignoreCase = true) }
        else -> null
    }
}

fun externalTargetWithHttpsFallback(value: String): String = value.trim().withHttpsFallback()

private fun String.withHttpsFallback(): String =
    if (startsWith("http://", ignoreCase = true) ||
        startsWith("https://", ignoreCase = true) ||
        startsWith("mailto:", ignoreCase = true)
    ) {
        this
    } else {
        "https://$this"
    }
