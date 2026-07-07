package org.cashu.wallet.ui.receive

import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Models.TokenInfo

internal enum class TokenLockState { None, YourKey, UnknownKey }

internal data class ReceiveEcashReviewFlags(
    val lockState: TokenLockState,
    val unknownMint: Boolean,
)

internal fun receiveEcashReviewFlags(
    info: TokenInfo,
    tokenLockPubkeys: List<String>,
    knownP2PKPubkeys: Set<String>,
    walletMintUrls: List<String>,
): ReceiveEcashReviewFlags {
    val normalizedKnownKeys = knownP2PKPubkeys.mapTo(mutableSetOf()) { it.lowercase() }
    val lockState = when {
        tokenLockPubkeys.isEmpty() -> TokenLockState.None
        tokenLockPubkeys.any { it.lowercase() in normalizedKnownKeys } -> TokenLockState.YourKey
        else -> TokenLockState.UnknownKey
    }
    val tokenMint = normalizedMintUrl(info.mint)
    val unknownMint = walletMintUrls.none { normalizedMintUrl(it) == tokenMint }
    return ReceiveEcashReviewFlags(lockState = lockState, unknownMint = unknownMint)
}

internal fun formatTokenAmount(
    info: TokenInfo,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
): String {
    val isSatToken = info.unit.equals("sat", ignoreCase = true)
    return if (isSatToken) {
        formatter.formatWalletSats(info.amount, useBitcoinSymbol)
    } else {
        CurrencyAmount(info.amount, CurrencyRegistry.currencyForMintUnit(info.unit)).formatted()
    }
}

private fun normalizedMintUrl(url: String): String =
    url.trim().trimEnd('/').lowercase()
