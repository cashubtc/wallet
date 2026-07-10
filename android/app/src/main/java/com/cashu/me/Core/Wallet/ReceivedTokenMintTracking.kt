package com.cashu.me.Core

/**
 * iOS parity (WalletManager+Tokens.receiveTokens → ensureMintTrackedForToken):
 * after a successful receive, the token's mint must be added to the tracked
 * mint list, because refreshBalance()/loadTransactions() only consider tracked
 * mints — without this, ecash claimed from an unknown mint is redeemed into
 * the CDK store but stays invisible (balance unchanged, no mint listed).
 *
 * Tracking runs only after a successful redeem so an unredeemed token never
 * adds a mint, and tracking failures are swallowed (reported via
 * [onTrackingFailed]) because the ecash was already redeemed — the claim must
 * still succeed.
 */
internal suspend fun trackMintForReceivedToken(
    tokenString: String,
    tokenMintUrl: (String) -> String? = { TokenParser.tokenInfo(it)?.mint },
    onTrackingFailed: (Throwable) -> Unit = {},
    ensureMintTracked: suspend (String) -> Unit,
) {
    runCatching {
        tokenMintUrl(tokenString)
            // TokenParser.tokenInfo falls back to the literal "Unknown mint"
            // when the URL can't be decoded — never track such placeholders.
            ?.takeIf { it.startsWith("http", ignoreCase = true) }
            ?.let { ensureMintTracked(it) }
    }.onFailure(onTrackingFailed)
}
