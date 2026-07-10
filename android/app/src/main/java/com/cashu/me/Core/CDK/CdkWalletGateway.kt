package com.cashu.me.Core.CDK

import kotlinx.coroutines.flow.Flow
import com.cashu.me.Core.NPCQuote
import com.cashu.me.Models.MeltPaymentResult
import com.cashu.me.Models.MeltQuoteInfo
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.RestoreMintResult
import com.cashu.me.Models.SendTokenResult
import com.cashu.me.Models.WalletTransaction

interface CdkWalletGateway {
    suspend fun initializeLogging(level: String = "info")
    suspend fun generateMnemonic(): String
    suspend fun mnemonicEntropy(mnemonic: String): ByteArray
    suspend fun validateMnemonic(mnemonic: String): Boolean
    suspend fun openWalletRepository(mnemonic: String, databasePath: String)
    suspend fun closeWalletRepository()

    /** Whether the repository currently tracks any mint wallets. */
    suspend fun hasWallets(): Boolean

    /** NUT-27: publish the encrypted mint-list backup for the open seed to the given relays. */
    suspend fun backupMints(relays: List<String>, client: String)

    /** NUT-27: fetch the newest mint-list backup for the open seed; returns the backed-up mint URLs. */
    suspend fun fetchMintBackup(relays: List<String>, timeoutSecs: ULong): List<String>
    suspend fun ensureWallet(mintUrl: String, unit: String = "sat")
    suspend fun removeWallet(mintUrl: String, unit: String = "sat")
    suspend fun fetchMintInfo(mintUrl: String): MintInfo?
    suspend fun restoreMint(mintUrl: String): RestoreMintResult
    suspend fun totalBalance(mintUrl: String): Long

    /** Balance of the (mint, unit) wallet, registering the unit wallet if needed. */
    suspend fun unitBalance(mintUrl: String, unit: String): Long

    /**
     * Balance of the (mint, unit) wallet WITHOUT creating it — null when the
     * wallet was never registered. Used by refreshBalance so advertising a unit
     * never registers keysets/counters the user hasn't touched.
     */
    suspend fun unitBalanceIfExists(mintUrl: String, unit: String): Long?
    suspend fun createMintQuote(amount: Long?, method: PaymentMethodKind, mintUrl: String, unit: String = "sat"): MintQuoteInfo
    suspend fun checkMintQuote(quoteId: String): MintQuoteInfo
    fun subscribeToMintQuote(quoteId: String): Flow<MintQuoteInfo>
    suspend fun listUnissuedMintQuotes(): List<MintQuoteInfo>
    suspend fun mintTokens(quoteId: String): Long
    suspend fun mintNPCQuote(quote: NPCQuote, p2pkPubkey: String?): Long
    suspend fun createMeltQuote(request: String, amountSats: Long? = null, preferredMintURL: String? = null): MeltQuoteInfo
    suspend fun listMeltQuotes(): List<MeltQuoteInfo>
    suspend fun meltTokens(quoteId: String, mintUrl: String? = null): MeltPaymentResult
    suspend fun sendEcashToken(amount: Long, memo: String?, p2pkPubkey: String?, mintUrl: String, unit: String = "sat", p2pkSigningKeys: List<String> = emptyList()): SendTokenResult
    suspend fun receiveEcashToken(tokenString: String, p2pkSigningKeys: List<String> = emptyList()): Long
    suspend fun calculateReceiveFee(tokenString: String): Long
    suspend fun checkTokenSpendable(token: String, mintUrl: String): Boolean
    suspend fun listTransactions(mintUrls: List<String>): List<WalletTransaction>
    suspend fun payCashuPaymentRequest(encoded: String, customAmountSats: Long?, preferredMintURL: String?)
}

class CdkGatewayUnavailable(message: String) : IllegalStateException(message)
