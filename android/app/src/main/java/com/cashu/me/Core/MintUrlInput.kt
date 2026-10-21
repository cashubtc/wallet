package com.cashu.me.Core

import com.cashu.me.Models.MintInfo
import java.net.URL

internal fun normalizeUserMintUrl(
    rawUrl: String,
    allowCleartextLocalTestMints: Boolean = false,
): String? {
    val value = rawUrl.trim().trim('"', '\'')
    if (value.isBlank()) return null
    val url = com.cashu.me.Models.MintUrlIdentity.withDefaultScheme(value)
    if (url.any { it.isWhitespace() }) return null
    if (com.cashu.me.Models.MintUrlIdentity.validationError(url, allowCleartextLocalTestMints) != null) return null
    return url
}

internal fun mintUrlCandidates(rawInput: String): List<String> =
    rawInput
        .split(Regex("""[\s,;]+"""))
        .mapNotNull { normalizeUserMintUrl(it) }
        .distinct()

internal fun shortenMintUrl(url: String): String =
    com.cashu.me.Models.MintUrlIdentity.display(url)

internal fun mintDisplayName(url: String, mints: List<MintInfo>): String =
    heldMintName(url, mints) ?: shortenMintUrl(url.trim())

/** The name the wallet holds [url] under, or null when it holds no such mint. */
internal fun heldMintName(url: String, mints: List<MintInfo>): String? =
    mints.firstOrNull {
        normalizedMintUrlForSelection(it.url) == normalizedMintUrlForSelection(url)
    }?.name?.trim()?.takeIf { it.isNotEmpty() }
