package com.cashu.me.Models

import java.net.URI
import java.net.URL
import java.util.Locale

object MintUrlIdentity {
    fun normalized(url: String): String = url.trim().trimEnd('/')

    fun comparisonKey(url: String?): String? {
        val value = url?.let(::normalized)?.takeIf { it.isNotBlank() } ?: return null
        val parsed = runCatching { URI(value) }.getOrNull() ?: return value
        val scheme = parsed.scheme ?: return value
        val host = parsed.host ?: return value
        val authority = buildString {
            parsed.rawUserInfo?.let { append(it); append('@') }
            append(host.lowercase(Locale.ROOT))
            if (parsed.port != -1) { append(':'); append(parsed.port) }
        }
        return buildString {
            append(scheme.lowercase(Locale.ROOT)); append("://"); append(authority)
            append(parsed.rawPath.orEmpty())
            parsed.rawQuery?.let { append('?'); append(it) }
            parsed.rawFragment?.let { append('#'); append(it) }
        }
    }

    fun withDefaultScheme(url: String): String {
        val value = url.trim()
        return normalized(if (value.startsWith("https://", true) || value.startsWith("http://", true)) value else "https://$value")
    }

    fun display(url: String): String = normalized(url).replaceFirst(Regex("^https?://", RegexOption.IGNORE_CASE), "")

    fun validationError(url: String, allowCleartextLocalTestMints: Boolean = false): String? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return "Invalid URL format."
        if (parsed.host.isNullOrBlank()) return "Invalid URL format."
        val localHost = parsed.host in setOf("localhost", "127.0.0.1", "10.0.2.2", "::1", "[::1]")
        if (parsed.protocol != "https" && !(allowCleartextLocalTestMints && parsed.protocol == "http" && localHost)) {
            return "Mint URL must use HTTPS for security."
        }
        return null
    }
}
