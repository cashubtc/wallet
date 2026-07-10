package org.cashu.wallet.Core

import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cashu.wallet.Core.CDK.CdkWalletGateway
import org.cashu.wallet.Core.Platform.WalletDatabasePathManager
import org.cashu.wallet.Core.Protocols.SecureStorage
import org.cashu.wallet.Core.Protocols.StorageKeys
import org.cashu.wallet.Core.Protocols.WalletServiceProtocol
import org.cashu.wallet.Models.MeltPaymentResult
import org.cashu.wallet.Models.MeltQuoteInfo
import org.cashu.wallet.Models.MeltQuoteState
import org.cashu.wallet.Models.MeltSettlement
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.PaymentMethodKind
import org.cashu.wallet.Models.PendingReceiveToken
import org.cashu.wallet.Models.PendingToken
import org.cashu.wallet.Models.RestoreMintResult
import org.cashu.wallet.Models.SendTokenResult

class WalletManager(
    private val secureStorage: SecureStorage,
    private val walletStore: WalletStore,
    private val cashuRequestStore: CashuRequestStore,
    private val settingsManager: SettingsManager,
    private val nostrService: NostrService,
    private val npcService: NPCService,
    private val nostrMintBackupService: NostrMintBackupService,
    private val databasePathManager: WalletDatabasePathManager,
    private val gateway: CdkWalletGateway,
) : WalletServiceProtocol, NPCQuoteClaimHandler {
    private val exceptionHandler = CoroutineExceptionHandler { _, error ->
        AppLogger.wallet.error("Unhandled wallet coroutine error", error)
        update { copy(isLoading = false, errorMessage = error.message ?: error::class.simpleName) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + exceptionHandler)
    private val mutableState = MutableStateFlow(WalletState())
    val state: StateFlow<WalletState> = mutableState.asStateFlow()
    private val mintMetadataFetcher = WalletMintMetadataFetcher()
    private val mintQuoteSyncService = WalletMintQuoteSyncService(gateway, walletStore)
    private val transactionLoader = WalletTransactionLoader(walletStore, gateway)
    private val npcQuotesInFlight = mutableSetOf<String>()
    private val pendingMeltWaiters = mutableMapOf<String, Job>()
    private var processedNPCQuotes = walletStore.loadProcessedNPCQuotes().toMutableSet()
    var walletBoundaryPauseHandler: (() -> Unit)? = null
    var walletBoundaryResetHandler: (() -> Unit)? = null
    var walletBoundaryResumeHandler: (() -> Unit)? = null

    override suspend fun initialize() {
        if (mutableState.value.isInitialized) return
        update { copy(isLoading = true, errorMessage = null) }
        runCatching {
            gateway.initializeLogging()
            secureStorage.loadString(StorageKeys.secureWalletMnemonic)?.let { mnemonic ->
                openWalletRepositoryWithRecovery(mnemonic)
                deriveNostrKey(mnemonic)
                loadCachedState(needsOnboarding = false)
            } ?: update {
                copy(
                    isInitialized = true,
                    isLoading = false,
                    needsOnboarding = true,
                    canExitOnboarding = false,
                    mints = walletStore.loadMints(),
                )
            }
        }.onFailure { error ->
            AppLogger.wallet.error("Wallet initialization failed", error)
            update {
                copy(
                    isInitialized = true,
                    isLoading = false,
                    needsOnboarding = true,
                    canExitOnboarding = false,
                    errorMessage = error.message,
                )
            }
        }
    }

    override suspend fun createNewWallet() {
        withLoading {
            val mnemonic = gateway.generateMnemonic()
            installCleanWallet(mnemonic, needsOnboarding = false)
        }
    }

    suspend fun generateMnemonicForOnboarding(): String =
        withLoadingResult { gateway.generateMnemonic() }

    suspend fun createNewWalletFromMnemonic(mnemonic: String) {
        val normalized = MnemonicInput.normalize(mnemonic)
        require(MnemonicInput.hasSupportedWordCount(normalized)) {
            "Seed phrase must be ${MnemonicInput.supportedWordCountLabel} words."
        }
        require(gateway.validateMnemonic(normalized)) { "Invalid seed phrase." }
        withLoading { installCleanWallet(normalized, needsOnboarding = false) }
    }

    suspend fun initializeNewWalletForOnboarding(mnemonic: String) {
        val normalized = MnemonicInput.normalize(mnemonic)
        require(MnemonicInput.hasSupportedWordCount(normalized)) {
            "Seed phrase must be ${MnemonicInput.supportedWordCountLabel} words."
        }
        require(gateway.validateMnemonic(normalized)) { "Invalid seed phrase." }
        withLoading { installCleanWallet(normalized, needsOnboarding = true) }
    }

    suspend fun initializeRestoredWallet(mnemonic: String) {
        val normalized = MnemonicInput.normalize(mnemonic)
        require(MnemonicInput.hasSupportedWordCount(normalized)) {
            "Seed phrase must be ${MnemonicInput.supportedWordCountLabel} words."
        }
        require(gateway.validateMnemonic(normalized)) { "Invalid seed phrase." }
        withLoading { installCleanWallet(normalized, needsOnboarding = true) }
    }

    override suspend fun restoreWallet(mnemonic: String) {
        val normalized = MnemonicInput.normalize(mnemonic)
        require(MnemonicInput.hasSupportedWordCount(normalized)) {
            "Seed phrase must be ${MnemonicInput.supportedWordCountLabel} words."
        }
        require(gateway.validateMnemonic(normalized)) { "Invalid seed phrase." }
        withLoading {
            installCleanWallet(normalized, needsOnboarding = false)
        }
    }

    override suspend fun deleteWallet() {
        withLoading {
            cancelPendingMeltWaiters()
            walletBoundaryPauseHandler?.invoke()
            gateway.closeWalletRepository()
            secureStorage.delete(StorageKeys.secureWalletMnemonic)
            secureStorage.delete(StorageKeys.secureNostrPrivateKey)
            databasePathManager.removeWalletDatabaseFiles()
            walletStore.removeAllWalletData()
            settingsManager.resetWalletScopedData()
            npcService.resetForWalletBoundary()
            nostrMintBackupService.resetForWalletBoundary()
            walletBoundaryResetHandler?.invoke()
            update {
                WalletState(
                    isInitialized = true,
                    needsOnboarding = true,
                    canExitOnboarding = false,
                )
            }
        }
    }

    override suspend fun addMint(url: String) {
        withLoading {
            val normalized = mintMetadataFetcher.normalizeMintUrl(url)
            mintMetadataFetcher.validateMintUrl(normalized)?.let { throw IllegalArgumentException(it) }
            if (mutableState.value.mints.any { it.url == normalized }) {
                throw IllegalArgumentException("Mint already exists.")
            }
            runCatching { gateway.ensureWallet(normalized) }
                .onFailure { AppLogger.wallet.error("CDK wallet preparation is not available yet for $normalized", it) }
            val fetched = gateway.fetchMintInfo(normalized)
                ?: throw IllegalStateException("Mint did not return info via CDK.")
            val updated = mutableState.value.mints + fetched
            walletStore.saveMints(updated)
            if (mutableState.value.activeMint == null) walletStore.activeMintURL = fetched.url
            loadCachedState(needsOnboarding = false)
            refreshBalance()
        }
        scope.launch { nostrMintBackupService.backupCurrentMintsIfEnabled() }
    }

    override suspend fun removeMint(mint: MintInfo) {
        withLoading {
            runCatching { gateway.removeWallet(mint.url) }
                .onFailure { AppLogger.wallet.error("CDK wallet removal is not available yet for ${mint.url}", it) }
            val updated = mutableState.value.mints.filterNot { it.url == mint.url }
            walletStore.saveMints(updated)
            if (walletStore.activeMintURL == mint.url) {
                walletStore.activeMintURL = updated.firstOrNull()?.url
            }
            loadCachedState(needsOnboarding = false)
            refreshBalance()
        }
        scope.launch { nostrMintBackupService.backupCurrentMintsIfEnabled() }
    }

    override suspend fun setActiveMint(mint: MintInfo) {
        walletStore.activeMintURL = mint.url
        loadCachedState(needsOnboarding = false)
    }

    /** Whether a mint URL is already trusted/tracked by this wallet. */
    fun isMintKnown(url: String): Boolean {
        val candidate = normalizedMintIdentity(url)
        return mutableState.value.mints.any { normalizedMintIdentity(it.url) == candidate }
    }

    override suspend fun restoreFromMint(url: String): RestoreMintResult =
        withLoadingResult {
            val normalized = mintMetadataFetcher.normalizeMintUrl(url)
            mintMetadataFetcher.validateMintUrl(normalized)?.let { throw IllegalArgumentException(it) }
            val trackedMintUrl = ensureMintTracked(normalized)
            val result = withContext(Dispatchers.IO) { gateway.restoreMint(trackedMintUrl) }
            refreshBalance()
            loadTransactions()
            result
        }

    suspend fun refreshBalance() {
        val mints = mutableState.value.mints
        var total = 0L
        val unitTotals = mutableMapOf<String, Long>()
        val updated = mints.map { mint ->
            val balance = runCatching { gateway.totalBalance(mint.url) }.getOrDefault(mint.balance)
            total += balance
            // Only sum unit wallets that already exist — never register a unit
            // wallet just because the mint advertises the unit.
            mint.units.filter { !it.equals("sat", ignoreCase = true) }.forEach { unit ->
                runCatching { gateway.unitBalanceIfExists(mint.url, unit) }.getOrNull()?.let {
                    unitTotals[unit] = (unitTotals[unit] ?: 0L) + it
                }
            }
            mint.copy(balance = balance)
        }
        unitTotals["sat"] = total
        walletStore.saveMints(updated)
        update {
            copy(
                balance = total,
                balancesByUnit = unitTotals.toMap(),
                mints = updated,
                activeMint = activeMintFrom(updated),
            )
        }
    }

    /**
     * Balance of one (mint, unit). Sat answers from the cached mint balance;
     * non-sat registers the unit wallet on demand. Null when unavailable.
     */
    suspend fun unitBalance(mintUrl: String, unit: String): Long? {
        if (unit.equals("sat", ignoreCase = true)) {
            return mutableState.value.mints.firstOrNull { it.url == mintUrl }?.balance
        }
        return runCatching {
            withContext(Dispatchers.IO) { gateway.unitBalance(mintUrl, unit) }
        }.getOrNull()
    }

    override suspend fun createMintQuote(amount: Long?, method: PaymentMethodKind, unit: String): MintQuoteInfo {
        val active = mutableState.value.activeMint ?: throw IllegalStateException("No active mint.")
        return withLoadingResult {
            gateway.createMintQuote(amount, method, active.url, unit).also {
                mintQuoteSyncService.rememberMintQuoteTimestamp(it.id)
            }
        }
    }

    suspend fun createMintQuoteForMint(
        mintUrl: String,
        amount: Long?,
        method: PaymentMethodKind = PaymentMethodKind.Bolt11,
        unit: String = "sat",
    ): MintQuoteInfo =
        withLoadingResult {
            val trackedMintUrl = ensureMintTracked(mintUrl)
            gateway.createMintQuote(amount, method, trackedMintUrl, unit).also {
                mintQuoteSyncService.rememberMintQuoteTimestamp(it.id)
            }
        }

    suspend fun checkMintQuote(quoteId: String): MintQuoteInfo =
        withLoadingResult {
            gateway.checkMintQuote(quoteId).also {
                mintQuoteSyncService.rememberMintQuoteTimestamp(it.id)
            }
        }

    suspend fun pollMintQuote(quoteId: String): MintQuoteInfo =
        gateway.checkMintQuote(quoteId).also {
            mintQuoteSyncService.rememberMintQuoteTimestamp(it.id)
        }

    fun subscribeToMintQuote(quoteId: String): Flow<MintQuoteInfo> = gateway.subscribeToMintQuote(quoteId)

    override suspend fun mintTokens(quoteId: String): Long =
        withLoadingResult {
            gateway.mintTokens(quoteId).also {
                refreshBalance()
                loadTransactions()
            }
        }

    suspend fun refreshPendingMintQuote(quoteId: String): Boolean =
        withLoadingResult {
            val minted = mintQuoteSyncService.syncPendingMintQuote(
                quoteId,
                allowPendingOnchainMintAttempt = true,
            )
            if (minted) refreshBalance()
            loadTransactions()
            minted
        }

    suspend fun syncPendingMintQuotes(): Int =
        withLoadingResult {
            val pendingQuotes = runCatching { gateway.listUnissuedMintQuotes() }
                .getOrDefault(emptyList())
            var mintedCount = 0
            pendingQuotes.forEach { quote ->
                if (
                    mintQuoteSyncService.syncPendingMintQuote(
                        quote.id,
                        allowPendingOnchainMintAttempt = false,
                    )
                ) {
                    mintedCount += 1
                }
            }
            if (mintedCount > 0) refreshBalance()
            loadTransactions()
            mintedCount
        }

    override fun isNPCQuoteProcessed(quoteId: String): Boolean =
        quoteId in processedNPCQuotes || quoteId in walletStore.loadProcessedNPCQuotes()

    override suspend fun claimNPCQuote(quote: NPCQuote, p2pkPubkey: String?): Boolean {
        if (isNPCQuoteProcessed(quote.id) || quote.id in npcQuotesInFlight) return true
        npcQuotesInFlight += quote.id
        return try {
            val mintUrl = quote.mintUrl ?: mutableState.value.activeMint?.url
                ?: throw IllegalStateException("npub.cash quote ${quote.id} has no mint URL.")
            val normalizedMintUrl = ensureMintTracked(mintUrl)
            val amount = gateway.mintNPCQuote(quote.copy(mintUrl = normalizedMintUrl), p2pkPubkey)
            markNPCQuoteProcessed(quote.id)
            p2pkPubkey?.let(settingsManager::markP2PKKeyUsed)
            refreshBalance()
            loadTransactions()
            amount > 0 || isNPCQuoteProcessed(quote.id)
        } catch (error: Throwable) {
            if (mintQuoteSyncService.isAlreadyIssuedMintError(error)) {
                markNPCQuoteProcessed(quote.id)
                true
            } else {
                AppLogger.wallet.error("Failed to mint NPC quote ${quote.id}", error)
                false
            }
        } finally {
            npcQuotesInFlight -= quote.id
        }
    }

    override suspend fun createMeltQuote(request: String, amountSats: Long?, preferredMintURL: String?): MeltQuoteInfo =
        withLoadingResult { gateway.createMeltQuote(request, amountSats, preferredMintURL) }

    override suspend fun meltTokens(quoteId: String, mintUrl: String?): MeltPaymentResult =
        withLoadingResult {
            val result = gateway.meltTokens(quoteId, mintUrl)
            if (result.settlement == MeltSettlement.Pending) {
                rememberPendingMeltQuote(quoteId, result.mintUrl)
                watchPendingMelt(quoteId)
            } else {
                transactionLoader.saveMeltPaymentMetadata(quoteId, result)
            }
            refreshBalance()
            loadTransactions()
            result
        }

    /** Reconcile async-accepted NUT-05 melts after process death or foregrounding. */
    suspend fun syncPendingMeltQuotes(): Int {
        val tracked = walletStore.loadPendingMeltQuotes()
        if (tracked.isEmpty()) return 0

        var terminalCount = 0
        tracked.forEach { (quoteId, mintUrl) ->
            if (pendingMeltWaiters[quoteId]?.isActive == true) return@forEach
            runCatching { gateway.checkMeltQuote(quoteId, mintUrl) }
                .onSuccess { quote ->
                    when (quote.state) {
                        MeltQuoteState.Paid -> {
                            transactionLoader.saveMeltPaymentMetadata(
                                quoteId,
                                MeltPaymentResult(
                                    preimage = quote.paymentProof,
                                    amount = quote.amount,
                                    // The actual fee is unavailable after relaunch;
                                    // retain the quote reserve as the display fallback.
                                    feePaid = quote.feeReserve,
                                    mintUrl = quote.mintUrl,
                                    paymentMethod = quote.paymentMethod,
                                    request = quote.request,
                                ),
                                persistActualFee = false,
                            )
                            forgetPendingMeltQuote(quoteId)
                            terminalCount += 1
                        }
                        MeltQuoteState.Unpaid, MeltQuoteState.Failed -> {
                            // Once async processing was accepted, unpaid is a
                            // terminal compensated saga, not a quote to retry.
                            forgetPendingMeltQuote(quoteId)
                            transactionLoader.removeMeltTransaction(quoteId)
                            terminalCount += 1
                        }
                        MeltQuoteState.Pending, MeltQuoteState.Unknown -> Unit
                    }
                }
                .onFailure { AppLogger.wallet.error("Pending melt status check failed for $quoteId", it) }
        }
        if (terminalCount > 0) {
            refreshBalance()
            loadTransactions()
        }
        return terminalCount
    }

    private fun watchPendingMelt(quoteId: String) {
        if (pendingMeltWaiters[quoteId]?.isActive == true) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val completion = gateway.waitForPendingMelt(quoteId) ?: return@launch
                when (completion.state) {
                    MeltQuoteState.Paid -> transactionLoader.saveMeltPaymentMetadata(quoteId, completion.result)
                    MeltQuoteState.Unpaid, MeltQuoteState.Failed -> transactionLoader.removeMeltTransaction(quoteId)
                    MeltQuoteState.Pending, MeltQuoteState.Unknown -> return@launch
                }
                forgetPendingMeltQuote(quoteId)
                refreshBalance()
                loadTransactions()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                // Keep the durable marker; foreground sync will retry.
                AppLogger.wallet.error("Pending melt wait failed for $quoteId", error)
            } finally {
                pendingMeltWaiters.remove(quoteId)
            }
        }
        pendingMeltWaiters[quoteId] = job
        job.start()
    }

    private fun rememberPendingMeltQuote(quoteId: String, mintUrl: String) {
        walletStore.savePendingMeltQuotes(walletStore.loadPendingMeltQuotes() + (quoteId to mintUrl))
    }

    private fun forgetPendingMeltQuote(quoteId: String) {
        val tracked = walletStore.loadPendingMeltQuotes()
        if (quoteId in tracked) walletStore.savePendingMeltQuotes(tracked - quoteId)
    }

    private fun cancelPendingMeltWaiters() {
        pendingMeltWaiters.values.forEach(Job::cancel)
        pendingMeltWaiters.clear()
    }

    override suspend fun sendTokens(amount: Long, memo: String?, p2pkPubkey: String?, mintUrl: String?, unit: String): SendTokenResult {
        val selectedMint = mintUrl ?: mutableState.value.activeMint?.url ?: throw IllegalStateException("No active mint.")
        val normalizedP2PKPubkey = SettingsManager.normalizeP2PKPublicKeyForSend(p2pkPubkey)
        return withLoadingResult {
            val result = gateway.sendEcashToken(
                amount,
                memo,
                normalizedP2PKPubkey,
                selectedMint,
                unit,
                // Full signing set so proofs already locked to our keys can be
                // swapped into the outgoing token (iOS TokenService parity).
                settingsManager.allP2PKSigningKeyHexes(),
            )
            val pending = PendingToken(
                tokenId = UUID.randomUUID().toString(),
                token = result.token,
                amount = amount,
                fee = result.fee,
                dateEpochMillis = System.currentTimeMillis(),
                mintUrl = selectedMint,
                memo = memo,
                unit = unit,
            )
            normalizedP2PKPubkey?.let(settingsManager::markP2PKKeyUsed)
            walletStore.savePendingTokens(walletStore.loadPendingTokens() + pending)
            refreshBalance()
            loadTransactions()
            result
        }
    }

    override suspend fun receiveTokens(tokenString: String): Long =
        withLoadingResult {
            val p2pkPubkeys = TokenParser.p2pkPubkeys(tokenString)
            val signingKeys = settingsManager.p2pkSigningKeysFor(p2pkPubkeys)
            gateway.receiveEcashToken(tokenString, signingKeys).also {
                p2pkPubkeys.forEach(settingsManager::markP2PKKeyUsed)
                // iOS parity (WalletManager+Tokens.receiveTokens): track the
                // token's mint only after a successful receive, so an
                // unredeemed token never adds the mint. Without this,
                // refreshBalance/loadTransactions skip the unknown mint and
                // the claimed funds stay invisible.
                trackMintForReceivedToken(
                    tokenString = tokenString,
                    onTrackingFailed = {
                        AppLogger.wallet.error("Failed to track mint for received token", it)
                    },
                    ensureMintTracked = { ensureMintTracked(it) },
                )
                refreshBalance()
                loadTransactions()
            }
        }

    suspend fun receiveCashuRequestPayment(tokenString: String, requestId: String?): Long {
        val beforeIds = incomingTransactionIds(tokenString)
        val amount = receiveTokens(tokenString)
        val transactionId = incomingTransactionIds(tokenString).subtract(beforeIds).firstOrNull()
        if (!requestId.isNullOrBlank() && transactionId != null) {
            cashuRequestStore.attachPayment(
                requestId = requestId,
                transactionId = transactionId,
                amount = amount,
            )
        }
        return amount
    }

    fun savePendingReceiveToken(token: PendingReceiveToken) {
        val current = walletStore.loadPendingReceiveTokens()
        val existing = current.firstOrNull { it.tokenId == token.tokenId || it.token == token.token }
        val saved = if (existing == null) {
            token
        } else {
            token.copy(
                tokenId = existing.tokenId,
                dateEpochMillis = existing.dateEpochMillis,
                cashuRequestId = existing.cashuRequestId ?: token.cashuRequestId,
                memo = token.memo ?: existing.memo,
            )
        }
        val updated = current.filterNot { it.tokenId == existing?.tokenId } + saved
        walletStore.savePendingReceiveTokens(updated)
        update { copy(pendingReceiveTokens = updated) }
    }

    fun removePendingReceiveToken(tokenId: String) {
        val updated = walletStore.loadPendingReceiveTokens().filterNot { it.tokenId == tokenId }
        walletStore.savePendingReceiveTokens(updated)
        update { copy(pendingReceiveTokens = updated) }
    }

    suspend fun claimPendingReceiveToken(token: PendingReceiveToken): Long {
        val amount = if (token.cashuRequestId != null) {
            receiveCashuRequestPayment(
                tokenString = token.token,
                requestId = token.cashuRequestId.takeIf(String::isNotBlank),
            )
        } else {
            receiveTokens(token.token)
        }
        removePendingReceiveToken(token.tokenId)
        loadTransactions()
        return amount
    }

    fun removePendingToken(tokenId: String) {
        val updated = walletStore.loadPendingTokens().filterNot { it.tokenId == tokenId }
        walletStore.savePendingTokens(updated)
        update { copy(pendingTokens = updated) }
    }

    suspend fun checkPendingTokenStatus(pendingToken: PendingToken): Boolean =
        withLoadingResult {
            val claimed = gateway.checkTokenSpendable(pendingToken.token, pendingToken.mintUrl)
            if (claimed) {
                val mutation = transactionLoader.markPendingTokenClaimed(pendingToken)
                update {
                    copy(
                        pendingTokens = mutation.pendingTokens,
                        claimedTokens = mutation.claimedTokens,
                    )
                }
                loadTransactions()
            }
            claimed
        }

    suspend fun checkAllPendingTokens(): Int =
        withLoadingResult {
            val pendingTokens = walletStore.loadPendingTokens()
            var claimedCount = 0
            pendingTokens.forEach { token ->
                val claimed = runCatching { gateway.checkTokenSpendable(token.token, token.mintUrl) }
                    .getOrDefault(false)
                if (claimed) {
                    val mutation = transactionLoader.markPendingTokenClaimed(token)
                    update {
                        copy(
                            pendingTokens = mutation.pendingTokens,
                            claimedTokens = mutation.claimedTokens,
                        )
                    }
                    claimedCount += 1
                }
            }
            loadTransactions()
            claimedCount
        }

    suspend fun reclaimPendingToken(pendingToken: PendingToken): Long {
        val amount = receiveTokens(pendingToken.token)
        removePendingToken(pendingToken.tokenId)
        loadTransactions()
        return amount
    }

    suspend fun calculateReceiveFee(tokenString: String): Long = gateway.calculateReceiveFee(tokenString)

    suspend fun checkTokenSpent(tokenString: String, mintUrl: String): Boolean =
        gateway.checkTokenSpendable(tokenString, mintUrl)

    suspend fun payCashuPaymentRequest(encoded: String, customAmountSats: Long?, preferredMintURL: String?) {
        withLoading {
            payCashuPaymentRequestAndRefresh(
                encoded = encoded,
                customAmountSats = customAmountSats,
                preferredMintURL = preferredMintURL,
                payCashuPaymentRequest = { request, amount, mintUrl ->
                    gateway.payCashuPaymentRequest(request, amount, mintUrl)
                },
                refreshBalance = { refreshBalance() },
                loadTransactions = { loadTransactions() },
            )
        }
    }

    suspend fun addMintAndPayCashuPaymentRequest(encoded: String, customAmountSats: Long?, mintUrl: String) {
        withLoading {
            addMintAndPayCashuPaymentRequestAndRefresh(
                encoded = encoded,
                customAmountSats = customAmountSats,
                mintUrl = mintUrl,
                ensureMintTracked = { ensureMintTracked(it) },
                payCashuPaymentRequest = { request, amount, trackedMintUrl ->
                    gateway.payCashuPaymentRequest(request, amount, trackedMintUrl)
                },
                refreshBalance = { refreshBalance() },
                loadTransactions = { loadTransactions() },
            )
        }
    }

    suspend fun loadTransactions() {
        val mintUrls = mutableState.value.mints.map { it.url }
        val result = transactionLoader.load(mintUrls)
        update {
            copy(
                transactions = result.transactions,
                pendingTokens = result.pendingTokens,
                pendingReceiveTokens = result.pendingReceiveTokens,
                claimedTokens = result.claimedTokens,
                transactionUpdateVersion = nextTransactionUpdateVersion(transactionUpdateVersion),
            )
        }
    }

    fun clearError() = update { copy(errorMessage = null) }

    fun backupMnemonic(): String? = secureStorage.loadString(StorageKeys.secureWalletMnemonic)

    fun openRestoreFlow() {
        if (!secureStorage.contains(StorageKeys.secureWalletMnemonic)) return
        update { copy(needsOnboarding = true, canExitOnboarding = true, errorMessage = null) }
    }

    fun closeRestoreFlow() {
        if (!mutableState.value.canExitOnboarding) return
        update { copy(needsOnboarding = false, errorMessage = null) }
    }

    suspend fun completeOnboarding() {
        loadCachedState(needsOnboarding = false)
        refreshBalance()
        loadTransactions()
    }

    /** Restore complete (iOS completeRestore): dismiss onboarding, then refresh the Nostr backup. */
    suspend fun completeRestore() {
        completeOnboarding()
        // The restored mint list is final now — refresh the Nostr backup with it.
        // (Must not run earlier: publishing while the repository is still empty
        // would replace the addressable backup event with an empty list.)
        scope.launch { nostrMintBackupService.backupCurrentMintsIfEnabled() }
    }

    private suspend fun installCleanWallet(mnemonic: String, needsOnboarding: Boolean) {
        val previousMnemonic = secureStorage.loadString(StorageKeys.secureWalletMnemonic)
        val backups = databasePathManager.backupWalletDatabaseFiles()
        val walletSnapshot = walletStore.snapshotWalletScopedData()
        val settingsSnapshot = settingsManager.snapshotWalletScopedData()
        cancelPendingMeltWaiters()
        walletBoundaryPauseHandler?.invoke()

        runCatching {
            gateway.closeWalletRepository()
            walletStore.removeAllWalletData()
            settingsManager.prepareForWalletReplacement()
            nostrService.resetForWalletBoundary(deleteStoredKey = false)
            openWalletRepositoryWithRecovery(mnemonic)
            deriveNostrKey(mnemonic)
            secureStorage.saveString(StorageKeys.secureWalletMnemonic, mnemonic)
            settingsManager.deleteWalletScopedSecrets(settingsSnapshot, deleteNostrPrivateKey = true)
            npcService.resetForWalletBoundary()
            nostrMintBackupService.resetForWalletBoundary()
            walletBoundaryResetHandler?.invoke()
            databasePathManager.removeWalletFileBackups(backups)
            loadCachedState(needsOnboarding = needsOnboarding)
        }.onFailure { error ->
            gateway.closeWalletRepository()
            walletStore.restoreWalletScopedData(walletSnapshot)
            settingsManager.restoreWalletScopedData(settingsSnapshot)
            nostrMintBackupService.reloadStoredState()
            databasePathManager.removeWalletDatabaseFiles()
            databasePathManager.restoreWalletFileBackups(backups)
            if (previousMnemonic != null) {
                secureStorage.saveString(StorageKeys.secureWalletMnemonic, previousMnemonic)
                runCatching {
                    openWalletRepositoryWithRecovery(previousMnemonic)
                    deriveNostrKey(previousMnemonic)
                    loadCachedState(needsOnboarding = false)
                    walletBoundaryResumeHandler?.invoke()
                }
            } else {
                walletBoundaryResetHandler?.invoke()
                secureStorage.delete(StorageKeys.secureWalletMnemonic)
                update {
                    WalletState(
                        isInitialized = true,
                        needsOnboarding = true,
                        canExitOnboarding = false,
                    )
                }
            }
            throw error
        }
    }

    private fun loadCachedState(needsOnboarding: Boolean) {
        val mints = walletStore.loadMints()
        val active = activeMintFrom(mints)
        val preimages = walletStore.loadPaymentPreimages()
        val meltFees = walletStore.loadMeltQuoteFees()
        val transactions = walletStore.loadTransactions()
            .map { it.withStoredMeltMetadata(preimages, meltFees) }
        val pendingTokens = walletStore.loadPendingTokens()
        val pendingReceiveTokens = walletStore.loadPendingReceiveTokens()
        val claimedTokens = walletStore.loadClaimedTokens()
        processedNPCQuotes = walletStore.loadProcessedNPCQuotes().toMutableSet()
        update {
            copy(
                balance = mints.sumOf { it.balance },
                isInitialized = true,
                isLoading = false,
                needsOnboarding = needsOnboarding,
                canExitOnboarding = secureStorage.contains(StorageKeys.secureWalletMnemonic),
                mints = mints,
                activeMint = active,
                transactions = transactions,
                pendingTokens = pendingTokens,
                pendingReceiveTokens = pendingReceiveTokens,
                claimedTokens = claimedTokens,
            )
        }
    }

    private fun markNPCQuoteProcessed(quoteId: String) {
        processedNPCQuotes += quoteId
        walletStore.saveProcessedNPCQuotes(processedNPCQuotes.sorted())
    }

    private fun activeMintFrom(mints: List<MintInfo>): MintInfo? {
        val saved = walletStore.activeMintURL
        return mints.firstOrNull { it.url == saved } ?: mints.firstOrNull()
    }

    private suspend fun ensureMintTracked(url: String): String {
        val normalized = mintMetadataFetcher.normalizeMintUrl(url)
        runCatching { gateway.ensureWallet(normalized) }
            .onFailure { AppLogger.wallet.error("CDK wallet preparation is not available yet for $normalized", it) }
        if (walletStore.loadMints().any { it.url == normalized }) return normalized

        val fetched = runCatching { gateway.fetchMintInfo(normalized) }.getOrElse {
            AppLogger.wallet.error("Failed to fetch CDK mint info for $normalized", it)
            MintInfo(
                url = normalized,
                name = runCatching { URL(normalized).host }.getOrNull() ?: "Unknown Mint",
            )
        } ?: MintInfo(
            url = normalized,
            name = runCatching { URL(normalized).host }.getOrNull() ?: "Unknown Mint",
        )
        val updated = walletStore.loadMints().filterNot { it.url == normalized } + fetched
        walletStore.saveMints(updated)
        if (walletStore.activeMintURL == null) walletStore.activeMintURL = fetched.url
        update {
            copy(
                mints = updated,
                activeMint = activeMintFrom(updated),
                balance = updated.sumOf { it.balance },
            )
        }
        return normalized
    }

    private suspend fun incomingTransactionIds(tokenString: String): Set<String> {
        val mintUrl = org.cashu.wallet.Models.TokenInfo.parse(tokenString)?.mint ?: return emptySet()
        return runCatching { gateway.listTransactions(listOf(mintUrl)) }
            .getOrDefault(emptyList())
            .filter {
                it.type == org.cashu.wallet.Models.TransactionType.Incoming &&
                    it.kind == org.cashu.wallet.Models.TransactionKind.Ecash
            }
            .mapTo(mutableSetOf()) { it.id }
    }

    private fun normalizedMintIdentity(url: String): String =
        runCatching {
            val parsed = java.net.URI.create(url.trim())
            buildString {
                append(parsed.host?.lowercase().orEmpty())
                if (parsed.port != -1) append(":${parsed.port}")
                append(parsed.path.orEmpty().trim('/'))
            }
        }.getOrDefault(url.trim().trimEnd('/').lowercase())

    private suspend fun deriveNostrKey(mnemonic: String) {
        // iOS parity (WalletManager+NPC.initializeNostrKeypairLocally): the Nostr
        // seed is sha256(mnemonic utf8) — NOT the BIP39 entropy, which is only
        // 16 bytes for a 12-word mnemonic and would fail the ≥32-byte check.
        // The same seed phrase must derive the same Nostr/P2PK identity on both
        // platforms so locked ecash stays recoverable across them.
        runCatching {
            val seed = java.security.MessageDigest.getInstance("SHA-256")
                .digest(mnemonic.toByteArray(Charsets.UTF_8))
            nostrService.deriveKeypairFromSeed(seed)
        }.onFailure { AppLogger.wallet.error("Nostr key derivation failed", it) }
    }

    private suspend fun openWalletRepositoryWithRecovery(mnemonic: String) {
        val databasePath = databasePathManager.databasePathAfterLegacyMigration()
        val initialResult = runCatching { gateway.openWalletRepository(mnemonic, databasePath) }
        val error = initialResult.exceptionOrNull() ?: return
        if (!shouldAttemptWalletDatabaseRecovery(error)) throw error
        val backup = databasePathManager.backupCorruptedDatabase() ?: throw error
        AppLogger.wallet.info("Wallet DB recovery: moved corrupted database to ${backup.absolutePath}")
        gateway.openWalletRepository(mnemonic, databasePath)
    }

    private suspend fun withLoading(block: suspend () -> Unit) {
        withLoadingResult { block() }
    }

    private suspend fun <T> withLoadingResult(block: suspend () -> T): T {
        update { copy(isLoading = true, errorMessage = null) }
        return runCatching { block() }
            .onSuccess { update { copy(isLoading = false) } }
            .onFailure { error ->
                AppLogger.wallet.error("Wallet operation failed", error)
                update { copy(isLoading = false, errorMessage = error.message) }
            }
            .getOrThrow()
    }

    private fun update(transform: WalletState.() -> WalletState) {
        mutableState.value = mutableState.value.transform()
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch { block() }
    }

    fun reopenOnboarding() {
        update { copy(needsOnboarding = true, canExitOnboarding = true) }
    }
}
