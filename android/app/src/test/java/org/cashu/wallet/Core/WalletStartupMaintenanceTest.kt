package org.cashu.wallet.Core

import kotlinx.coroutines.runBlocking
import org.cashu.wallet.Models.MintInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletStartupMaintenanceTest {
    @Test
    fun startupMaintenanceEnsuresEveryTrackedMintAndUnitBeforeSyncingState() = runBlocking {
        val events = mutableListOf<String>()

        runBestEffortWalletStartupMaintenance(
            trackedMints = listOf(
                MintInfo(
                    url = "https://mint-a.example",
                    units = listOf("sat", "usd", "usd"),
                ),
                MintInfo(
                    url = "https://mint-b.example",
                    units = listOf("eur"),
                ),
            ),
            ensureWallet = { mintUrl, unit -> events += "ensure:$mintUrl:$unit" },
            refreshBalance = { events += "refreshBalance" },
            loadTransactions = { events += "loadTransactions" },
            performForegroundMaintenance = { events += "foregroundMaintenance" },
            onError = { message, _ -> events += "error:$message" },
        )

        assertEquals(
            listOf(
                "ensure:https://mint-a.example:sat",
                "ensure:https://mint-a.example:usd",
                "ensure:https://mint-b.example:sat",
                "ensure:https://mint-b.example:eur",
                "refreshBalance",
                "loadTransactions",
                "foregroundMaintenance",
            ),
            events,
        )
    }

    @Test
    fun startupMaintenanceLogsFailuresAndContinuesThroughRemainingSteps() = runBlocking {
        val events = mutableListOf<String>()

        runBestEffortWalletStartupMaintenance(
            trackedMints = listOf(
                MintInfo(
                    url = "https://mint-a.example",
                    units = listOf("sat", "usd"),
                ),
            ),
            ensureWallet = { mintUrl, unit ->
                events += "ensure:$unit"
                if (unit == "sat") error("keyset refresh failed")
                if (mintUrl.isBlank()) error("unreachable")
            },
            refreshBalance = {
                events += "refreshBalance"
                error("balance unavailable")
            },
            loadTransactions = { events += "loadTransactions" },
            performForegroundMaintenance = {
                events += "foregroundMaintenance"
                error("pending check failed")
            },
            onError = { message, error ->
                events += "error:$message:${error.message}"
            },
        )

        assertEquals(
            listOf(
                "ensure:sat",
                "error:Startup wallet refresh failed for mint:keyset refresh failed",
                "ensure:usd",
                "refreshBalance",
                "error:Startup balance refresh failed:balance unavailable",
                "loadTransactions",
                "foregroundMaintenance",
                "error:Startup pending-state maintenance failed:pending check failed",
            ),
            events,
        )
    }
}
