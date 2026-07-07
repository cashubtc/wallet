package org.cashu.wallet.ui.receive

import java.util.Locale
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Models.TokenInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiveEcashReviewTest {
    private val knownMint = "https://mint.example.com"
    private val knownKey = "02ABCDEF"

    @Test
    fun unlockedKnownMintTokenHasNoWarnings() {
        val flags = receiveEcashReviewFlags(
            info = tokenInfo(mint = "$knownMint/"),
            tokenLockPubkeys = emptyList(),
            knownP2PKPubkeys = emptySet(),
            walletMintUrls = listOf(knownMint),
        )

        assertEquals(TokenLockState.None, flags.lockState)
        assertFalse(flags.unknownMint)
    }

    @Test
    fun tokenFromUnknownMintIsFlagged() {
        val flags = receiveEcashReviewFlags(
            info = tokenInfo(mint = "https://unknown.example.com"),
            tokenLockPubkeys = emptyList(),
            knownP2PKPubkeys = emptySet(),
            walletMintUrls = listOf(knownMint),
        )

        assertTrue(flags.unknownMint)
    }

    @Test
    fun tokenLockedToKnownPrimaryKeyCanBeReceived() {
        val flags = receiveEcashReviewFlags(
            info = tokenInfo(),
            tokenLockPubkeys = listOf(knownKey.lowercase()),
            knownP2PKPubkeys = setOf(knownKey),
            walletMintUrls = listOf(knownMint),
        )

        assertEquals(TokenLockState.YourKey, flags.lockState)
        assertFalse(flags.unknownMint)
    }

    @Test
    fun tokenLockedToUnknownKeyIsFlagged() {
        val flags = receiveEcashReviewFlags(
            info = tokenInfo(),
            tokenLockPubkeys = listOf("02unknown"),
            knownP2PKPubkeys = setOf(knownKey),
            walletMintUrls = listOf(knownMint),
        )

        assertEquals(TokenLockState.UnknownKey, flags.lockState)
    }

    @Test
    fun nonSatTokensUseMintUnitFormatting() {
        val label = formatTokenAmount(
            info = tokenInfo(amount = 1234, unit = "usd"),
            formatter = AmountFormatter(Locale.US),
            useBitcoinSymbol = false,
        )

        assertEquals("$12.34", label)
    }

    private fun tokenInfo(
        amount: Long = 21,
        mint: String = knownMint,
        unit: String = "sat",
    ): TokenInfo = TokenInfo(
        amount = amount,
        mint = mint,
        unit = unit,
        memo = null,
        proofCount = 1,
    )
}
