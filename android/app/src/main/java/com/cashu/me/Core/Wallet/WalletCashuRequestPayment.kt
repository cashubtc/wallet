package com.cashu.me.Core

internal suspend fun payCashuPaymentRequestAndRefresh(
    encoded: String,
    customAmountSats: Long?,
    preferredMintURL: String?,
    payCashuPaymentRequest: suspend (encoded: String, customAmountSats: Long?, preferredMintURL: String?) -> Unit,
    refreshBalance: suspend () -> Unit,
    loadTransactions: suspend () -> Unit,
) {
    payCashuPaymentRequest(encoded, customAmountSats, preferredMintURL)
    refreshBalance()
    loadTransactions()
}

internal suspend fun addMintAndPayCashuPaymentRequestAndRefresh(
    encoded: String,
    customAmountSats: Long?,
    mintUrl: String,
    ensureMintTracked: suspend (mintUrl: String) -> String,
    payCashuPaymentRequest: suspend (encoded: String, customAmountSats: Long?, preferredMintURL: String?) -> Unit,
    refreshBalance: suspend () -> Unit,
    loadTransactions: suspend () -> Unit,
) {
    val trackedMintUrl = ensureMintTracked(mintUrl)
    payCashuPaymentRequest(encoded, customAmountSats, trackedMintUrl)
    refreshBalance()
    loadTransactions()
}
