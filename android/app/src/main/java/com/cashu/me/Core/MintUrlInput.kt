package com.cashu.me.Core

import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.mintMatching
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

private const val FederationInvitePrefix = "cashufed"

/** A federation invite code (`cashufed…`), as pasted, scanned, or typed. */
internal fun isFederationInviteCode(raw: String): Boolean {
    val value = raw.trim().trim('"', '\'')
    return value.length > FederationInvitePrefix.length &&
        value.startsWith(FederationInvitePrefix, ignoreCase = true) &&
        value.none { it.isWhitespace() }
}

/**
 * Add-mint input: a federation invite code is kept verbatim (trimmed), any
 * other value must be a mint URL. Null when neither.
 */
internal fun normalizeUserMintInput(
    rawInput: String,
    allowCleartextLocalTestMints: Boolean = false,
): String? {
    val value = rawInput.trim().trim('"', '\'')
    if (isFederationInviteCode(value)) return value
    return normalizeUserMintUrl(value, allowCleartextLocalTestMints)
}

/** Mint URLs and federation invite codes found in pasted text, in order. */
internal fun mintInputCandidates(
    rawInput: String,
    allowCleartextLocalTestMints: Boolean = false,
): List<String> =
    rawInput
        .split(Regex("""[\s,;]+"""))
        .mapNotNull { normalizeUserMintInput(it, allowCleartextLocalTestMints) }
        .distinct()

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
    mints.mintMatching(url)?.name?.trim()?.takeIf { it.isNotEmpty() }
