package com.cashu.me.Core

import java.net.URL
import java.util.UUID
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import com.cashu.me.Core.CDK.CdkWalletGateway
import com.cashu.me.Core.Platform.WalletDatabasePathManager
import com.cashu.me.Core.Protocols.SecureStorage
import com.cashu.me.Core.Protocols.StorageKeys
import com.cashu.me.Core.Protocols.WalletServiceProtocol
import com.cashu.me.Models.MeltPaymentResult
import com.cashu.me.Models.MeltQuoteInfo
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.PendingReceiveToken
import com.cashu.me.Models.PendingToken
import com.cashu.me.Models.RestoreMintResult
import com.cashu.me.Models.SendTokenResult

class WalletManager(
    private val secureStorage: SecureStorage,
    private val walletStore: WalletStore,
    private val cashuRequestStore: CashuRequestStore,
    private val settingsManager: SettingsManager,
    private val nostrService: NostrService,
    private val npcService: NPCService,
    private val nwcManager: NwcManager,
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
    private val initializationMutex = Mutex()
    private val mintMetadataFetcher = WalletMintMetadataFetcher()
    private val mintQuoteSyncService = WalletMintQuoteSyncService(gateway, walletStore)
    private val transactionLoader = WalletTransactionLoader(walletStore, gateway)
    private val npcQuotesInFlight = mutableSetOf<String>()
    private var processedNPCQuotes = walletStore.loadProcessedNPCQuotes().toMutableSet()

    override suspend fun initialize() {
        initializationMutex.withLock {
            if (mutableState.value.isRuntimeReady) return@withLock

            withContext(Dispatchers.IO) {
                val hasStoredWallet = secureStorage.contains(StorageKeys.secureWalletMnemonic)

                // Publish the complete cached home model before opening SQLite,
                // decrypting the seed, deriving keys, or starting background services.
                // This is the same cache-first boundary used by iOS.
                if (hasStoredWallet) {
                    loadCachedState(needsOnboarding = false)
                } else {
                    update {
                        copy(
                            isInitialized = true,
                            isLoading = false,
                            needsOnboarding = true,
                            canExitOnboarding = false,
                            mints = walletStore.loadMints(),
                        )
                    }
                }

                runCatching {
                    gateway.initializeLogging()
                    if (hasStoredWallet) {
                        val mnemonic = checkNotNull(secureStorage.loadString(StorageKeys.secureWalletMnemonic)) {
                            "Stored wallet seed could not be decrypted."
                        }
                        openWalletRepositoryWithRecovery(mnemonic)
                        deriveNostrKey(mnemonic)
                    }
                    update { copy(isRuntimeReady = true, errorMessage = null) }
                    startDeferredStartupMaintenance(hasStoredWallet)
                }.onFailure { error ->
                    AppLogger.wallet.error("Wallet runtime initialization failed", error)
                    update {
                        copy(
                            isInitialized = true,
                            isRuntimeReady = false,
                            isLoading = false,
                            errorMessage = error.message,
                        )
                    }
                }
            }
        }
    }

    private fun startDeferredStartupMaintenance(hasStoredWallet: Boolean) {
        scope.launch(Dispatchers.IO) {
            // Give Compose a scheduling opportunity to render cached state before
            // background maintenance contends for CDK's operation mutex.
            kotlinx.coroutines.yield()
            if (hasStoredWallet) {
                runCatching { refreshBalance() }
                    .onFailure { AppLogger.wallet.error("Deferred balance refresh failed", it) }
            }
            runCatching { nwcManager.startIfEnabled() }
                .onFailure { AppLogger.wallet.error("Deferred NWC startup failed", it) }
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

    /**
     * Phase 1 of seed restore (iOS `initializeRestoredWallet`): install the
     * repository for this mnemonic. Does **not** force first-launch onboarding
     * — [needsOnboarding] is preserved so:
     *   - first-launch onboarding stays on its restore faces, and
     *   - Settings → Restore keeps the in-app shell (mint staging) instead of
     *     cross-fading to the welcome splash.
     * Phase 3 [completeRestore] clears onboarding when finishing first launch.
     */
    suspend fun initializeRestoredWallet(mnemonic: String) {
        val normalized = MnemonicInput.normalize(mnemonic)
        require(MnemonicInput.hasSupportedWordCount(normalized)) {
            "Seed phrase must be ${MnemonicInput.supportedWordCountLabel} words."
        }
        require(gateway.validateMnemonic(normalized)) { "Invalid seed phrase." }
        val keepOnboarding = mutableState.value.needsOnboarding
        withLoading { installCleanWallet(normalized, needsOnboarding = keepOnboarding) }
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
            nwcManager.resetForWalletBoundary()
            gateway.closeWalletRepository()
            secureStorage.delete(StorageKeys.secureWalletMnemonic)
            secureStorage.delete(StorageKeys.secureNostrPrivateKey)
            databasePathManager.removeWalletDatabaseFiles()
            walletStore.removeAllWalletData()
            settingsManager.resetWalletScopedData()
            npcService.resetForWalletBoundary()
            nostrMintBackupService.resetForWalletBoundary()
            MintLogoBitmapCache.clear()
            update {
                WalletState(
                    isInitialized = true,
                    isRuntimeReady = true,
                    needsOnboarding = true,
                    canExitOnboarding = false,
                )
            }
        }
    }

    override suspend fun addMint(url: String) {
        var addedMintUrl: String? = null
        withLoading {
            val normalized = mintMetadataFetcher.normalizeMintUrl(url)
            mintMetadataFetcher.validateMintUrl(normalized)?.let { throw IllegalArgumentException(it) }
            if (mutableState.value.mints.any { it.url == normalized }) {
                throw IllegalArgumentException("Mint already exists.")
            }
            // Connect and commit first so the Mints view responds promptly.
            // NUT-09 recovery starts below on the app-lifetime scope and
            // refreshes balances/history after it completes.
            gateway.ensureWallet(normalized)
            val fetched = gateway.fetchMintInfo(normalized)
                ?: throw IllegalStateException("Mint did not return info via CDK.")
            val updated = mutableState.value.mints + fetched
            walletStore.saveMints(updated)
            if (mutableState.value.activeMint == null) walletStore.activeMintURL = fetched.url
            loadCachedState(needsOnboarding = false)
            addedMintUrl = fetched.url
        }

        addedMintUrl?.let { mintUrl ->
            scope.launch {
                if (mutableState.value.mints.none { it.url == mintUrl }) return@launch

                runCatching {
                    restoreProofsForAddedMint(
                        mintUrl = mintUrl,
                        restoreMint = { withContext(Dispatchers.IO) { gateway.restoreMint(it) } },
                    )
                    if (mutableState.value.mints.none { it.url == mintUrl }) return@runCatching
                    refreshBalance()
                    loadTransactions()
                }.onSuccess {
                    AppLogger.wallet.info("Background restore completed for added mint $mintUrl")
                }.onFailure { error ->
                    AppLogger.wallet.error("Background restore failed for added mint $mintUrl", error)
                }
            }
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
        walletStore.saveBalancesByUnit(unitTotals)
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
     * Refresh NUT-06 mint metadata for every tracked mint. Does not toggle
     * [WalletState.isLoading] — call from the Mints screen so the list stays
     * interactive while names/icons update in place (iOS `refreshMintInfo`).
     */
    suspend fun refreshMintInfo() {
        val current = mutableState.value.mints
        if (current.isEmpty()) return

        var changed = false
        val updated = current.map { mint ->
            val fetched = runCatching {
                gateway.ensureWallet(mint.url)
                gateway.fetchMintInfo(mint.url)
            }.onFailure {
                AppLogger.wallet.error("Failed to refresh mint info for ${mint.url}", it)
            }.getOrNull() ?: return@map mint

            val merged = mint.copy(
                name = fetched.name.takeUnless { it == "Unknown Mint" } ?: mint.name,
                description = fetched.description ?: mint.description,
                iconUrl = fetched.iconUrl ?: mint.iconUrl,
                units = fetched.units.ifEmpty { mint.units },
                mintUnits = fetched.mintUnits.ifEmpty { mint.mintUnits },
                supportedMintMethods = fetched.supportedMintMethods.ifEmpty { mint.supportedMintMethods },
                supportedMeltMethods = fetched.supportedMeltMethods.ifEmpty { mint.supportedMeltMethods },
                lastUpdatedEpochMillis = System.currentTimeMillis(),
                balance = mint.balance,
                isActive = mint.isActive,
            )
            if (merged != mint) {
                changed = true
                merged
            } else {
                mint
            }
        }

        if (!changed) return
        walletStore.saveMints(updated)
        update {
            copy(
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

    /**
     * Best-effort mint identity for staging / discovery (iOS `fetchMintPreviewInfo`
     * / detail reachability). Creates a CDK wallet entry if needed so logos and
     * names can load, but does not add the mint to the app's saved list.
     * Throws when the mint is unreachable so callers can map Checking → Offline.
     */
    suspend fun fetchLiveMintInfo(mintUrl: String): MintInfo? {
        val normalized = mintMetadataFetcher.normalizeMintUrl(mintUrl)
        return gateway.fetchMintInfo(normalized)
    }

    override suspend fun createMintQuote(amount: Long?, method: PaymentMethodKind, unit: String): MintQuoteInfo {
        val active = mutableState.value.activeMint ?: throw IllegalStateException("No active mint.")
        return withLoadingResult {
            gateway.createMintQuote(amount, method, active.url, unit).also {
                mintQuoteSyncService.rememberMintQuoteTimestamp(it.id)
            }
        }
    }

    /** Returns the active mint's reusable amountless BOLT12 offer, if present. */
    suspend fun existingAmountlessBolt12Offer(unit: String): MintQuoteInfo? {
        val activeMint = mutableState.value.activeMint ?: return null
        return findExistingAmountlessBolt12Offer(
            quotes = gateway.listUnissuedMintQuotes(),
            mintUrl = activeMint.url,
            unit = unit,
        )
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
            transactionLoader.saveMeltPaymentMetadata(quoteId, result)
            refreshBalance()
            loadTransactions()
            result
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

    suspend fun receiveCashuRequestPayment(tokenString: String, requestId: String?, processedId: String? = requestId): Long {
        val normalizedProcessedId = processedId?.trim()?.takeIf { it.isNotEmpty() }
        if (normalizedProcessedId != null && normalizedProcessedId in walletStore.loadProcessedCashuRequests()) {
            return 0
        }
        val amount = receiveTokens(tokenString)
        normalizedProcessedId?.let { id ->
            walletStore.saveProcessedCashuRequests((walletStore.loadProcessedCashuRequests() + id).distinct().sorted())
        }
        return amount
    }

    suspend fun receiveNfcCashuRequestPayment(
        tokenString: String,
        processedId: String,
    ): com.cashu.me.Core.CDK.NfcReceiveReceipt = withLoadingResult {
        require(processedId !in walletStore.loadProcessedCashuRequests()) { "This payment was already received." }
        val p2pkPubkeys = TokenParser.p2pkPubkeys(tokenString)
        val signingKeys = settingsManager.p2pkSigningKeysFor(p2pkPubkeys)
        gateway.receiveNfcEcashToken(tokenString, signingKeys).also {
            p2pkPubkeys.forEach(settingsManager::markP2PKKeyUsed)
            trackMintForReceivedToken(
                tokenString = tokenString,
                onTrackingFailed = { AppLogger.wallet.error("Failed to track mint for received NFC token", it) },
                ensureMintTracked = { ensureMintTracked(it) },
            )
            walletStore.saveProcessedCashuRequests(
                (walletStore.loadProcessedCashuRequests() + processedId).distinct().sorted(),
            )
            refreshBalance()
            loadTransactions()
        }
    }

    suspend fun settleForeignNfcToken(
        tokenString: String,
        settlementMintUrl: String,
        processedId: String,
    ): com.cashu.me.Core.CDK.ForeignNfcSettlement = withLoadingResult {
        require(processedId !in walletStore.loadProcessedCashuRequests()) { "This payment was already received." }
        gateway.settleForeignNfcToken(tokenString, settlementMintUrl).also {
            walletStore.saveProcessedCashuRequests(
                (walletStore.loadProcessedCashuRequests() + processedId).distinct().sorted(),
            )
            refreshBalance()
            loadTransactions()
        }
    }

    fun savePendingReceiveToken(token: PendingReceiveToken) {
        val current = walletStore.loadPendingReceiveTokens()
        val updated = current.filterNot { it.tokenId == token.tokenId } + token
        walletStore.savePendingReceiveTokens(updated)
        update { copy(pendingReceiveTokens = updated) }
    }

    fun removePendingReceiveToken(tokenId: String) {
        val updated = walletStore.loadPendingReceiveTokens().filterNot { it.tokenId == tokenId }
        walletStore.savePendingReceiveTokens(updated)
        update { copy(pendingReceiveTokens = updated) }
    }

    suspend fun claimPendingReceiveToken(token: PendingReceiveToken): Long {
        val amount = receiveTokens(token.token)
        removePendingReceiveToken(token.tokenId)
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

    suspend fun checkAllPendingTokens(): Int {
        // Cached transactions are already published by loadCachedState().
        // Avoid locking the CDK gateway for a full all-mint history pass when
        // startup has no outgoing tokens whose spent state needs checking.
        val pendingTokens = walletStore.loadPendingTokens()
        if (pendingTokens.isEmpty()) return 0

        return withLoadingResult {
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
        val result = transactionLoader.load(mutableState.value.mints)
        // Quote-backed requests (notably the long-lived BOLT12 offer) own their
        // received payments in history. Reconcile before publishing so the UI
        // shows the one reusable-invoice row rather than duplicate payments.
        cashuRequestStore.reconcileIncomingQuotePayments(result.transactions)
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
        val nwcSnapshot = nwcManager.snapshotWalletScopedData()

        runCatching {
            // NwcService retains the native CDK wallet, so it must stop before
            // the repository/database it is backed by is closed.
            nwcManager.resetForWalletBoundary()
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
            databasePathManager.removeWalletFileBackups(backups)
            loadCachedState(needsOnboarding = needsOnboarding)
        }.onFailure { error ->
            gateway.closeWalletRepository()
            walletStore.restoreWalletScopedData(walletSnapshot)
            settingsManager.restoreWalletScopedData(settingsSnapshot)
            nwcManager.restoreWalletScopedData(nwcSnapshot)
            nostrMintBackupService.reloadStoredState()
            databasePathManager.removeWalletDatabaseFiles()
            databasePathManager.restoreWalletFileBackups(backups)
            if (previousMnemonic != null) {
                secureStorage.saveString(StorageKeys.secureWalletMnemonic, previousMnemonic)
                runCatching {
                    openWalletRepositoryWithRecovery(previousMnemonic)
                    deriveNostrKey(previousMnemonic)
                    loadCachedState(needsOnboarding = false)
                    nwcManager.startIfEnabled()
                }
            } else {
                secureStorage.delete(StorageKeys.secureWalletMnemonic)
                update {
                    WalletState(
                        isInitialized = true,
                        isRuntimeReady = true,
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
        val cachedBalance = mints.sumOf { it.balance }
        val cachedBalancesByUnit = walletStore.loadBalancesByUnit().toMutableMap().apply {
            this["sat"] = cachedBalance
        }
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
                balance = cachedBalance,
                balancesByUnit = cachedBalancesByUnit,
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
