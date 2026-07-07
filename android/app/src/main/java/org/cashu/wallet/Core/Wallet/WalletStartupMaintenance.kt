package org.cashu.wallet.Core

import org.cashu.wallet.Models.MintInfo

internal suspend fun runBestEffortWalletStartupMaintenance(
    trackedMints: List<MintInfo>,
    ensureWallet: suspend (mintUrl: String, unit: String) -> Unit,
    refreshBalance: suspend () -> Unit,
    loadTransactions: suspend () -> Unit,
    performForegroundMaintenance: suspend () -> Unit,
    onError: (message: String, error: Throwable) -> Unit,
) {
    trackedMints.forEach { mint ->
        runCatching { ensureWallet(mint.url, "sat") }
            .onFailure { onError("Startup wallet refresh failed for mint", it) }
        mint.units
            .distinct()
            .filterNot { it.equals("sat", ignoreCase = true) }
            .forEach { unit ->
                runCatching { ensureWallet(mint.url, unit) }
                    .onFailure { onError("Startup unit wallet refresh failed for mint", it) }
            }
    }
    runCatching { refreshBalance() }
        .onFailure { onError("Startup balance refresh failed", it) }
    runCatching { loadTransactions() }
        .onFailure { onError("Startup transaction load failed", it) }
    runCatching { performForegroundMaintenance() }
        .onFailure { onError("Startup pending-state maintenance failed", it) }
}
