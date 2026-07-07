package org.cashu.wallet.Core

object WalletUserErrors {
    fun message(error: Throwable): String {
        val raw = error.message.orEmpty()
        val lower = raw.lowercase()
        return when {
            lower.contains("insufficient") || lower.contains("balance") ->
                "Insufficient balance for this payment."
            lower.contains("invoice") || lower.contains("bolt11") || lower.contains("bolt12") ->
                "This Lightning request could not be processed."
            lower.contains("token") || lower.contains("proof") ->
                "This ecash token could not be processed."
            lower.contains("mint") && (lower.contains("unreachable") || lower.contains("network") || lower.contains("connect")) ->
                "Could not reach the mint. Check your connection and try again."
            lower.contains("database") || lower.contains("sqlite") || lower.contains("walletdb") ->
                "The local wallet database could not be opened."
            lower.contains("timeout") || lower.contains("timed out") ->
                "The wallet request timed out. Try again."
            else -> AppLogger.privacySafeMessage(raw)
                .takeIf { it.isNotBlank() && it.length <= 180 }
                ?: "Wallet operation failed."
        }
    }
}
