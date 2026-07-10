package com.cashu.me.Core

import java.net.URL

internal class WalletMintMetadataFetcher {
    fun normalizeMintUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        return normalized.trimEnd('/')
    }

    fun validateMintUrl(url: String): String? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return "Invalid URL format."
        if (parsed.host.isNullOrBlank()) return "Invalid URL format."
        if (parsed.protocol != "https") return "Mint URL must use HTTPS for security."
        return null
    }
}
