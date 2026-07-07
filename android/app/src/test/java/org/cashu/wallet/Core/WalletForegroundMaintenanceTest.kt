package org.cashu.wallet.Core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class WalletForegroundMaintenanceTest {
    @Test
    fun skipsAllRuntimeWorkBeforeWalletIsReady() = runBlocking {
        assertEquals(
            emptyList<String>(),
            runPolicy(walletState = WalletState(isInitialized = false, needsOnboarding = true)),
        )
        assertEquals(
            emptyList<String>(),
            runPolicy(walletState = WalletState(isInitialized = true, needsOnboarding = true)),
        )
    }

    @Test
    fun sentTokenStartupToggleAndSentTokenToggleBothGateClaimChecks() = runBlocking {
        assertEquals(
            listOf("checkSentTokens", "syncMintQuotes"),
            runPolicy(
                settings = SettingsState(
                    checkPendingOnStartup = true,
                    checkSentTokens = true,
                ),
            ),
        )
        assertEquals(
            listOf("syncMintQuotes"),
            runPolicy(
                settings = SettingsState(
                    checkPendingOnStartup = false,
                    checkSentTokens = true,
                ),
            ),
        )
        assertEquals(
            listOf("syncMintQuotes"),
            runPolicy(
                settings = SettingsState(
                    checkPendingOnStartup = true,
                    checkSentTokens = false,
                ),
            ),
        )
    }

    @Test
    fun mintQuoteSyncStillRunsWhenSentTokenCheckFails() = runBlocking {
        val events = runPolicy(
            checkSentTokenFailure = IllegalStateException("sent check failed"),
        )

        assertEquals(
            listOf(
                "checkSentTokens",
                "error:Pending sent-token foreground check failed:sent check failed",
                "syncMintQuotes",
            ),
            events,
        )
    }

    @Test
    fun mintQuoteSyncFailuresAreLogged() = runBlocking {
        val events = runPolicy(
            syncMintQuoteFailure = IllegalStateException("mint quote sync failed"),
        )

        assertEquals(
            listOf(
                "checkSentTokens",
                "syncMintQuotes",
                "error:Pending mint quote foreground sync failed:mint quote sync failed",
            ),
            events,
        )
    }

    private suspend fun runPolicy(
        walletState: WalletState = WalletState(isInitialized = true, needsOnboarding = false),
        settings: SettingsState = SettingsState(
            checkPendingOnStartup = true,
            checkSentTokens = true,
        ),
        checkSentTokenFailure: Throwable? = null,
        syncMintQuoteFailure: Throwable? = null,
    ): List<String> {
        val events = mutableListOf<String>()
        runWalletForegroundMaintenance(
            walletState = walletState,
            settings = settings,
            checkAllPendingTokens = {
                events += "checkSentTokens"
                checkSentTokenFailure?.let { throw it }
            },
            syncPendingMintQuotesIfStale = {
                events += "syncMintQuotes"
                syncMintQuoteFailure?.let { throw it }
            },
            onError = { message, error ->
                events += "error:$message:${error.message}"
            },
        )
        return events
    }
}
