package org.cashu.wallet.Core

internal suspend fun runWalletForegroundMaintenance(
    walletState: WalletState,
    settings: SettingsState,
    checkAllPendingTokens: suspend () -> Unit,
    syncPendingMintQuotesIfStale: suspend () -> Unit,
    onError: (message: String, error: Throwable) -> Unit,
) {
    if (!walletState.isInitialized || walletState.needsOnboarding) return
    if (settings.checkPendingOnStartup && settings.checkSentTokens) {
        runCatching { checkAllPendingTokens() }
            .onFailure { onError("Pending sent-token foreground check failed", it) }
    }
    runCatching { syncPendingMintQuotesIfStale() }
        .onFailure { onError("Pending mint quote foreground sync failed", it) }
}
