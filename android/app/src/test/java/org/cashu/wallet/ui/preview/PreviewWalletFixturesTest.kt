package org.cashu.wallet.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreviewWalletFixturesTest {
    @Test
    fun fixturesProvideUsableWalletState() {
        val walletState = PreviewWalletFixtures.walletState

        assertTrue(walletState.isInitialized)
        assertFalse(walletState.needsOnboarding)
        assertEquals(walletState.activeMint, walletState.mints.first())
        assertTrue(walletState.transactions.isNotEmpty())
        assertTrue(walletState.hasAnyBalance)
    }

    @Test
    fun fixturesAvoidSecretMaterial() {
        val rendered = listOf(
            PreviewWalletFixtures.cashuRequests.joinToString(),
            PreviewWalletFixtures.settings.toString(),
            PreviewWalletFixtures.walletState.toString(),
        ).joinToString(separator = "\n")

        assertFalse(rendered.contains("nsec1", ignoreCase = true))
        assertFalse(rendered.contains("cashuA", ignoreCase = true))
        assertFalse(rendered.contains("mnemonic", ignoreCase = true))
        assertFalse(rendered.contains("privateKey", ignoreCase = true))
    }
}
