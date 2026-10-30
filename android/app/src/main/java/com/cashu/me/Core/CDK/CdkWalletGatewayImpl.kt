package com.cashu.me.Core.CDK

import com.cashu.me.Core.PendingSendRecovery
import com.cashu.me.Core.performRecoverableSend
import com.cashu.me.Core.performRecoverablePaymentRequest
import com.cashu.me.Models.MintUrlIdentity
import com.cashu.me.Core.toDomainUnit
import com.cashu.me.Core.Services.ContactlessSendCallbacks
import com.cashu.me.Core.Services.completeContactlessSend

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import com.cashu.me.Core.LightningRequestParser
import com.cashu.me.Core.NPCQuote
import com.cashu.me.Core.mintQuoteAmountForDomain
import com.cashu.me.Core.mintQuoteDisplayExpiry
import com.cashu.me.Core.mintQuoteStateForDomain
import com.cashu.me.Core.PaymentRequestDecodeResult
import com.cashu.me.Core.PaymentRequestDecoder
import com.cashu.me.Core.PaymentRequestParser
import com.cashu.me.Models.MeltFeeAccounting
import com.cashu.me.Models.MeltPaymentResult
import com.cashu.me.Models.MeltQuoteInfo
import com.cashu.me.Models.MeltQuoteState
import com.cashu.me.Models.MeltSettlement
import com.cashu.me.Models.MintContact
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.MintQuoteState
import com.cashu.me.Models.MintSoftware
import com.cashu.me.Models.NutSupport
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.Models.RestoreMintResult
import com.cashu.me.Models.SagaTransactionId
import com.cashu.me.Models.ReceiveTokenPreview
import com.cashu.me.Models.SendTokenResult
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import org.cashudevkit.Amount as CdkAmount
import org.cashudevkit.BackupOptions as CdkBackupOptions
import org.cashudevkit.BitcoinNetwork as CdkBitcoinNetwork
import org.cashudevkit.CurrencyUnit as CdkCurrencyUnit
import org.cashudevkit.FinalizedMelt as CdkFinalizedMelt
import org.cashudevkit.KeysetLoadPolicy as CdkKeysetLoadPolicy
import org.cashudevkit.MeltConfirmOptions as CdkMeltConfirmOptions
import org.cashudevkit.MeltConfirmOutcome as CdkMeltConfirmOutcome
import org.cashudevkit.MeltOptions as CdkMeltOptions
import org.cashudevkit.MeltQuote as CdkMeltQuote
import org.cashudevkit.MintInfo as CdkMintInfo
import org.cashudevkit.MintQuote as CdkMintQuote
import org.cashudevkit.MintUrl as CdkMintUrl
import org.cashudevkit.NotificationPayload as CdkNotificationPayload
import org.cashudevkit.NwcService as CdkNwcService
import org.cashudevkit.P2pkLockedProofSendMode as CdkP2pkLockedProofSendMode
import org.cashudevkit.PaymentMethod as CdkPaymentMethod
import org.cashudevkit.QuoteState as CdkQuoteState
import org.cashudevkit.ReceiveOptions as CdkReceiveOptions
import org.cashudevkit.RestoreOptions as CdkRestoreOptions
import org.cashudevkit.SendKind as CdkSendKind
import org.cashudevkit.SendMemo as CdkSendMemo
import org.cashudevkit.SendOptions as CdkSendOptions
import org.cashudevkit.SecretKey as CdkSecretKey
import org.cashudevkit.SplitTarget as CdkSplitTarget
import org.cashudevkit.SpendingConditions as CdkSpendingConditions
import org.cashudevkit.Token as CdkToken
import org.cashudevkit.Transaction as CdkTransaction
import org.cashudevkit.TransactionDirection as CdkTransactionDirection
import org.cashudevkit.TransactionStatus as CdkTransactionStatus
import org.cashudevkit.Wallet as CdkWallet
import org.cashudevkit.WalletRepository as CdkWalletRepository
import org.cashudevkit.WalletSqliteDatabase as CdkWalletSqliteDatabase
import com.cashu.me.Core.NfcReceive.settleForeignNfcTokenWithCdk
import org.cashudevkit.customWalletStore
import org.cashudevkit.decodePaymentRequest
import org.cashudevkit.generateMnemonic as cdkGenerateMnemonic
import org.cashudevkit.initLogging
import org.cashudevkit.mnemonicToEntropy
import org.cashudevkit.nwcDeriveServiceSecretKeyFromSeed
import org.cashudevkit.proofsTotalAmount
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private val sagaJsonFormat = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

// Ceiling on how long a lightning melt may hold the gateway lane while CDK's
// `PendingMelt.wait()` drives settlement. A fallback exit, not a polling knob —
// `wait()` polls the mint internally; this only bounds how long the send
// screen (and the serialized gateway) stay committed before the pending face
// takes over (iOS `LightningService.MeltSettlementWait` parity).
internal const val LIGHTNING_SETTLEMENT_WAIT_MS = 30_000L

/**
 * In-lane settlement wait for an async-accepted melt. Returns the
 * [CdkFinalizedMelt] when the melt is a lightning method and CDK's `wait()`
 * reaches a terminal state within [waitMs]; null when the method doesn't wait
 * or the cap expires. Errors propagate to the status-aware recovery path.
 */
internal suspend fun awaitLightningSettlementOrNull(
    method: CdkPaymentMethod?,
    waitMs: Long = LIGHTNING_SETTLEMENT_WAIT_MS,
    wait: suspend () -> CdkFinalizedMelt,
): CdkFinalizedMelt? {
    if (method != CdkPaymentMethod.Bolt11 && method != CdkPaymentMethod.Bolt12) return null
    return withTimeoutOrNull(waitMs) { wait() }
}

/** Builds CDK's explicit amount override for amountless Lightning requests. */
internal fun meltOptionsForLightningRequest(
    decoded: PaymentRequestDecodeResult,
    amountSats: Long?,
): CdkMeltOptions? {
    val requestAmountSats = when (decoded) {
        is PaymentRequestDecodeResult.Bolt11 -> decoded.amountSats
        is PaymentRequestDecodeResult.Bolt12 -> decoded.amountSats
        else -> return null
    }
    if (requestAmountSats != null && requestAmountSats > 0L) return null

    val amount = amountSats?.takeIf { it > 0L }
        ?: throw CdkGatewayUnavailable(
            "This Lightning request doesn't include an amount. Enter an amount before requesting a quote.",
        )
    val amountMsat = try {
        Math.multiplyExact(amount, 1_000L)
    } catch (_: ArithmeticException) {
        throw CdkGatewayUnavailable("Amount is too large.")
    }
    return CdkMeltOptions.Amountless(CdkAmount(amountMsat.toULong()))
}

class CdkWalletGatewayImpl : WalletGateway {
    private var database: CdkWalletSqliteDatabase? = null
    private var repository: CdkWalletRepository? = null
    private val operationMutex = Mutex()

    // Confirmations that outlived their cap. They hold the native wallet and
    // store, so the repository must not close under them.
    private val settlementScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val residualSettlements = ConcurrentHashMap.newKeySet<Deferred<*>>()

    override suspend fun initializeLogging(level: String) = cdkCall {
        initLogging(level)
    }

    override suspend fun generateMnemonic(): String = cdkCall { cdkGenerateMnemonic() }

    override suspend fun mnemonicEntropy(mnemonic: String): ByteArray = cdkCall { mnemonicToEntropy(mnemonic) }

    override suspend fun validateMnemonic(mnemonic: String): Boolean = cdkCall {
        runCatching { mnemonicToEntropy(mnemonic); true }.getOrDefault(false)
    }

    override suspend fun openWalletRepository(mnemonic: String, databasePath: String) = cdkCall {
        residualSettlements.toList().joinAll()
        closeWalletRepositoryUnlocked()
        val db = CdkWalletSqliteDatabase(databasePath)
        database = db
        repository = CdkWalletRepository(mnemonic, customWalletStore(db))
    }

    override suspend fun closeWalletRepository() = cdkCall {
        residualSettlements.toList().joinAll()
        closeWalletRepositoryUnlocked()
    }

    override suspend fun hasWallets(): Boolean = cdkCall {
        requireRepository().getWallets().isNotEmpty()
    }

    /**
     * Creates or restores CDK's NIP-47 wallet service against the same native
     * sat wallet used by the rest of the app. CDK owns relay protocol handling,
     * invoice creation/payment, balance responses, and payment-limit checks.
     */
    override suspend fun createOrRestoreNwcService(
        mintUrl: String,
        relays: List<String>,
        seed: ByteArray,
        clientSecretKey: String?,
        maxPaymentMsat: ULong?,
    ): NwcServiceHandle = cdkCall {
        val normalizedMintUrl = normalizeMintUrl(mintUrl)
        ensureWalletUnlocked(normalizedMintUrl)
        val wallet = walletFor(normalizedMintUrl)
        val serviceSecretKey = nwcDeriveServiceSecretKeyFromSeed(seed)
        val nativeService = if (clientSecretKey.isNullOrBlank()) {
            CdkNwcService.create(
                wallet = wallet,
                relays = relays,
                serviceSecretKey = serviceSecretKey,
                maxPaymentMsat = maxPaymentMsat,
            )
        } else {
            CdkNwcService.restore(
                wallet = wallet,
                relays = relays,
                serviceSecretKey = serviceSecretKey,
                clientSecretKey = clientSecretKey,
                maxPaymentMsat = maxPaymentMsat,
            )
        }
        CdkNwcServiceHandle(
            service = nativeService,
            connectionUri = nativeService.connectionUri(),
        )
    }

    override suspend fun backupMints(relays: List<String>, client: String) = cdkCall {
        requireRepository().backupMints(relays, CdkBackupOptions(client = client))
        Unit
    }

    override suspend fun fetchMintBackup(relays: List<String>, timeoutSecs: ULong): List<String> = cdkCall {
        requireRepository().fetchMintBackup(relays, CdkRestoreOptions(timeoutSecs = timeoutSecs))
            .mints
            .map { it.url }
    }

    private fun closeWalletRepositoryUnlocked() {
        runCatching { repository?.close() }
        runCatching { database?.close() }
        repository = null
        database = null
    }

    override suspend fun ensureWallet(mintUrl: String, unit: String) = cdkCall {
        ensureWalletUnlocked(mintUrl, cdkUnit(unit))
    }

    override suspend fun removeWallets(mintUrl: String): Boolean = cdkCall {
        val repository = requireRepository()
        val registeredWallets = repository.getWallets()
            .filter { wallet ->
                mintRemovalUrlsMatch(wallet.mintUrl().url, mintUrl)
            }
        // CDK's removeWallet only drops the in-memory (mint, unit) entry;
        // proofs stay in the database. Removing units one at a time cannot
        // lose funds, and a failure part-way keeps the mint listed so a retry
        // removes only the units still registered.
        registeredWallets.forEach { wallet ->
            repository.removeWallet(wallet.mintUrl(), wallet.unit())
        }
        registeredWallets.isNotEmpty()
    }

    override suspend fun fetchMintInfo(mintUrl: String): MintInfo? = cdkCall {
        // iOS `fetchMintPreviewInfo`: CDK requires a wallet entry before
        // fetchMintInfo() — create one if needed. Does not add the mint to the
        // app's saved list (callers own that).
        ensureWalletUnlocked(mintUrl)
        walletFor(mintUrl).fetchMintInfo()?.toDomain(mintUrl)
    }

    override suspend fun restoreMint(mintUrl: String): RestoreMintResult = cdkCall {
        ensureWalletUnlocked(mintUrl)
        val wallet = walletFor(mintUrl)
        val info = runCatching { wallet.fetchMintInfo() }.getOrNull()
        wallet.recoverIncompleteSagas()
        requireSafeMintRestore(checkNotNull(database), mintUrl)
        val restored = wallet.restore()
        RestoreMintResult(
            mintUrl = mintUrl,
            mintName = info?.name ?: "Unknown Mint",
            iconUrl = info?.iconUrl,
            spent = restored.spent.value.toLong(),
            unspent = restored.unspent.value.toLong(),
            pending = restored.pending.value.toLong(),
        )
    }

    override suspend fun storedAccounts(): List<WalletAccountReference> = cdkCall {
        val db = checkNotNull(database)
        // Do not suppress discovery failures: callers must retain their last
        // complete projection instead of interpreting an incomplete scan as zero.
        buildList {
            addAll(requireRepository().getWallets().map { WalletAccountReference(it.mintUrl().url, it.unit().toDomainUnit()) })
            addAll(db.getProofs(null, null, null, null).map { WalletAccountReference(it.mintUrl.url, it.unit.toDomainUnit()) })
            addAll(db.listTransactions(null, null, null).map { WalletAccountReference(it.mintUrl.url, it.unit.toDomainUnit()) })
            addAll(db.getMintQuotes().map { WalletAccountReference(it.mintUrl.url, it.unit.toDomainUnit()) })
            addAll(db.getMeltQuotes().mapNotNull { quote -> quote.mintUrl?.let { WalletAccountReference(it.url, quote.unit.toDomainUnit()) } })
        }.distinct()
    }

    override suspend fun storedAccountBalance(account: WalletAccountReference): Long = cdkCall {
        checkNotNull(database).getBalance(CdkMintUrl(account.mintUrl), cdkUnit(account.unit), listOf(org.cashudevkit.ProofState.UNSPENT)).toLong()
    }

    override suspend fun totalBalance(mintUrl: String): Long = cdkCall {
        walletFor(mintUrl).totalBalance().value.toLong()
    }

    override suspend fun unitBalance(mintUrl: String, unit: String): Long = cdkCall {
        checkNotNull(database).getBalance(CdkMintUrl(mintUrl), cdkUnit(unit), listOf(org.cashudevkit.ProofState.UNSPENT)).toLong()
    }

    override suspend fun createMintQuote(amount: Long?, method: PaymentMethodKind, mintUrl: String, unit: String, description: String?): MintQuoteInfo = cdkCall {
        val cdkUnit = cdkUnit(unit)
        if (!unit.equals("sat", ignoreCase = true)) ensureWalletUnlocked(mintUrl, cdkUnit)
        val wallet = walletFor(mintUrl, cdkUnit)
        val quote = wallet.mintQuote(
            paymentMethod = cdkPaymentMethod(method),
            amount = amount?.toCdkAmount(),
            // Description is only threaded for BOLT12 offers (NUT-04 optional);
            // mint support on other rails is uneven, so they keep nil.
            description = description?.takeIf { method == PaymentMethodKind.Bolt12 },
            extra = if (method == PaymentMethodKind.Onchain) "{}" else null,
        )
        persistMintQuoteLocalMetadataIfNeeded(
            quote = quote,
            method = method,
            fallbackAmount = amount,
        ).toDomain(fallbackAmount = amount, fallbackMethod = method)
    }

    override suspend fun checkMintQuote(quoteId: String): MintQuoteInfo = cdkCall {
        val quote = database?.getMintQuote(quoteId)
            ?: throw CdkGatewayUnavailable("No stored mint quote for $quoteId.")
        // Resolve the same-unit wallet the quote was created against, so
        // resuming a non-sat quote never polls (or mints into) the sat wallet.
        val wallet = walletFor(quote.mintUrl.url, quote.unit)
        val method = quote.paymentMethod.toDomain()
        val fallbackAmount = quote.amount?.value?.toLong()
        val checkedQuote = if (method == PaymentMethodKind.Onchain) wallet.checkMintQuoteStatus(quoteId)
        else wallet.checkMintQuote(quoteId)
        val refreshed = persistMintQuoteLocalMetadataIfNeeded(checkedQuote, method, fallbackAmount)
        refreshed.toDomain(
            fallbackAmount = fallbackAmount,
            fallbackMethod = method,
        )
    }

    override suspend fun recoverDepositKey(request: com.cashu.me.Core.DepositRecoveryRequest): com.cashu.me.Core.DepositRecoveryLookup = cdkCall {
        val input = request.validated()
        val unit = cdkUnit(input.unit)
        val method = cdkPaymentMethod(input.method)
        val existing = database?.getMintQuote(input.quoteId)
        existing?.let { stored ->
            require(mintRemovalUrlsMatch(stored.mintUrl.url, input.mintUrl) && stored.unit == unit && stored.paymentMethod == method) {
                "This quote belongs to a different mint, currency, or payment method. Check the deposit details."
            }
        }
        ensureWalletUnlocked(input.mintUrl, unit)
        val wallet = walletFor(input.mintUrl, unit)
        // Resynchronize counters without overwriting another operation's inputs.
        requireSafeMintRestore(checkNotNull(database), input.mintUrl)
        wallet.restore()
        val quote = wallet.fetchMintQuoteWithKeySearch(input.quoteId, method, input.searchLimit)
        if (!mintRemovalUrlsMatch(quote.mintUrl.url, input.mintUrl) || quote.unit != unit || quote.paymentMethod != method) {
            if (existing == null) database?.removeMintQuote(input.quoteId)
            throw IllegalArgumentException("This quote belongs to a different mint, currency, or payment method. Check the deposit details.")
        }
        // Unmatched imports must not reach automatic issuance. Preserve every
        // pre-existing row, key, counter, and saga on an unsuccessful search.
        if (existing == null && quote.secretKey == null) database?.removeMintQuote(input.quoteId)
        com.cashu.me.Core.DepositRecoveryLookup(
            quote.toDomain(fallbackAmount = quote.amount?.value?.toLong(), fallbackMethod = input.method),
            hasSigningKey = quote.secretKey != null,
        )
    }

    override suspend fun storedMintQuote(quoteId: String): MintQuoteInfo? = cdkCall {
        database?.getMintQuote(quoteId)?.let { quote ->
            val method = quote.paymentMethod.toDomain()
            quote.withLocalMintQuoteMetadata(method).toDomain(
                fallbackAmount = quote.amount?.value?.toLong(),
                fallbackMethod = method,
            )
        }
    }

    override fun subscribeToMintQuote(quoteId: String, mayRefresh: () -> Boolean): Flow<MintQuoteInfo> = flow {
        val subscription = cdkCall {
            val quote = database?.getMintQuote(quoteId)
                ?: throw CdkGatewayUnavailable("No stored mint quote for $quoteId.")
            val method = quote.paymentMethod.toDomain()
            walletFor(quote.mintUrl.url, quote.unit)
                .subscribeMintQuoteState(listOf(quoteId), cdkPaymentMethod(method))
        }
        try {
            while (true) {
                val payload = withContext(Dispatchers.IO) { subscription.recv() }
                if (!payload.referencesMintQuote(quoteId)) continue
                if (!mayRefresh()) continue
                val refreshed = checkMintQuote(quoteId)
                emit(refreshed)
                // An amountless BOLT12 offer remains open after each mint. Keep
                // the subscription alive so its display can show later payments.
                if (refreshed.paymentMethod != PaymentMethodKind.Bolt12 &&
                    (refreshed.state == MintQuoteState.Paid || refreshed.state == MintQuoteState.Issued)
                ) {
                    return@flow
                }
            }
        } finally {
            withContext(Dispatchers.IO) { subscription.close() }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun listUnissuedMintQuotes(): List<MintQuoteInfo> = cdkCall {
        database?.getUnissuedMintQuotes().orEmpty().map { quote ->
            val method = quote.paymentMethod.toDomain()
            quote.withLocalMintQuoteMetadata(method).toDomain(
                fallbackAmount = null,
                fallbackMethod = method,
            )
        }
    }

    override suspend fun mintTokens(quoteId: String): Long = cdkCall {
        val quote = database?.getMintQuote(quoteId)
            ?: throw CdkGatewayUnavailable("No stored mint quote for $quoteId.")
        val method = quote.paymentMethod.toDomain()
        val fallbackAmount = quote.amount?.value?.toLong()
        val currentQuote = if (method == PaymentMethodKind.Onchain) {
            walletFor(quote.mintUrl.url, quote.unit).checkMintQuoteStatus(quoteId)
        } else {
            quote
        }
        val normalizedQuote = persistMintQuoteLocalMetadataIfNeeded(
            quote = currentQuote,
            method = method,
            fallbackAmount = fallbackAmount,
        )
        if (method == PaymentMethodKind.Onchain && !normalizedQuote.hasUnissuedOnchainCredit()) {
            throw CdkGatewayUnavailable(
                "Mint has not credited this on-chain quote yet " +
                    "(amount_paid=${normalizedQuote.amountPaid.value}, amount_issued=${normalizedQuote.amountIssued.value}).",
            )
        }
        if (normalizedQuote.usedByOperation != null) {
            // The live CDK saga owns the deterministic secrets required to
            // replay or restore an ambiguous mint response. Deleting it and
            // issuing fresh outputs can strand a payment the mint already
            // marked issued. Quote checks resume sagas; leave this one intact
            // for the next reconciliation pass when recovery is unavailable.
            throw CdkGatewayUnavailable(
                "A previous mint attempt is still being recovered. " +
                    "The wallet will retry automatically.",
            )
        }
        val wallet = walletFor(normalizedQuote.mintUrl.url, normalizedQuote.unit)
        prepareEmptyMintAccount(checkNotNull(database), wallet, normalizedQuote.mintUrl.url)
        val proofs = wallet.mintUnified(
            quoteId = quoteId,
            amountSplitTarget = CdkSplitTarget.None,
            spendingConditions = null,
        )
        proofsTotalAmount(proofs).value.toLong()
    }

    override suspend fun mintNPCQuote(quote: NPCQuote, p2pkPubkey: String?): Long = cdkCall {
        val mintUrl = quote.mintUrl?.let(::normalizeMintUrl)
            ?: throw CdkGatewayUnavailable("npub.cash quote ${quote.id} has no mint URL.")
        ensureWalletUnlocked(mintUrl)
        // Import only once. A retry must retain CDK's newer counters and saga
        // reservation instead of replacing them with an external quote snapshot.
        if (database?.getMintQuote(quote.id) == null) {
            replaceStoredMintQuote(npcMintQuote(quote, mintUrl))
        }
        val proofs = walletFor(mintUrl).mintUnified(
            quoteId = quote.id,
            amountSplitTarget = CdkSplitTarget.None,
            spendingConditions = p2pkPubkey?.let { CdkSpendingConditions.P2pk(it, null) },
        )
        proofsTotalAmount(proofs).value.toLong()
    }

    override suspend fun createMeltQuote(request: String, amountSats: Long?, preferredMintURL: String?): MeltQuoteInfo = cdkCall {
        val wallet = preferredMintURL?.let { walletFor(it) } ?: firstWallet()
        val decoded = PaymentRequestDecoder.decode(request)
        when (decoded) {
            is PaymentRequestDecodeResult.LightningAddress -> {
                val amount = requirePositiveAmount(amountSats, "Lightning address payments require an amount.")
                val amountMsat = try {
                    Math.multiplyExact(amount, 1_000L)
                } catch (_: ArithmeticException) {
                    throw CdkGatewayUnavailable("Lightning address payment amount is too large.")
                }
                val quote = LightningAddressResolver.quote(
                    lnurl = {
                        wallet.meltLightningAddressQuote(
                            lightningAddress = decoded.address,
                            amountMsat = amountMsat.toCdkAmount(),
                        )
                    },
                    bip353 = {
                        val info = wallet.fetchMintInfo()
                        if (info == null || PaymentMethodKind.Bolt12 !in info.nuts.reportedMeltMethods()) {
                            throw CdkGatewayUnavailable("No mint supports BOLT12 payments required for this address.")
                        }
                        wallet.meltBip353Quote(
                            bip353Address = decoded.address,
                            amountMsat = amountMsat.toCdkAmount(),
                            network = bitcoinNetworkFor(wallet.mintUrl().url),
                        )
                    },
                )
                return@cdkCall quote.toDomain(fallbackMethod = quote.paymentMethod.toDomain())
            }
            is PaymentRequestDecodeResult.Onchain -> {
                val amount = requirePositiveAmount(amountSats, "On-chain payments require an amount.")
                val options = wallet.quoteOnchainMeltOptions(
                    address = decoded.address,
                    amount = amount.toCdkAmount(),
                    maxFeeAmount = null,
                )
                val selected = options.firstOrNull()
                    ?: throw CdkGatewayUnavailable("Mint returned no on-chain fee options.")
                return@cdkCall wallet.selectOnchainMeltQuote(selected).toDomain(fallbackMethod = PaymentMethodKind.Onchain)
            }
            else -> Unit
        }
        val method = PaymentRequestParser.paymentMethod(request) ?: PaymentMethodKind.Bolt11
        val normalized = PaymentRequestDecoder.encodedLightningRequest(request) ?: request.trim()
        val options = meltOptionsForLightningRequest(decoded, amountSats)
        val quote = wallet.meltQuote(
            method = cdkPaymentMethod(method),
            request = normalized,
            options = options,
            extra = null,
        )
        quote.toDomain(fallbackMethod = method)
    }

    override suspend fun listMeltQuotes(): List<MeltQuoteInfo> = cdkCall {
        database?.getMeltQuotes().orEmpty().map { quote ->
            quote.toDomain(fallbackMethod = quote.paymentMethod.toDomain())
        }
    }

    override suspend fun meltTokens(quoteId: String, mintUrl: String?): MeltConfirmation = cdkCall {
        val quote = database?.getMeltQuote(quoteId)
        val wallet = walletFor(mintUrl ?: quote?.mintUrl?.url ?: firstWallet().mintUrl().url)
        val prepared = try { wallet.prepareMelt(quoteId) } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val stored = try { checkNotNull(database).getMeltQuote(quoteId) }
                catch (_: Exception) { throw MeltPaymentRecoveryException(quoteId, null, unresolved = true) }
            val operation = stored?.usedByOperation ?: throw failure
            return@cdkCall resolveMeltFailure(wallet, quoteId, operation)
        }
        val swapFee = prepared.swapFee().value.toLong()
        val preparationFee = prepared.totalFee().value.toLong()
        try {
            // `respond-async` lets the mint accept the payment and pay out in the
            // background (NUT-05) instead of holding the request open — the usual
            // shape for on-chain melts (iOS LightningService.meltTokens parity).
            when (val outcome = prepared.confirmPreferAsync()) {
                is CdkMeltConfirmOutcome.Paid -> {
                    val finalized = outcome.finalized
                    MeltConfirmation(
                        result = MeltPaymentResult(
                            preimage = finalized.preimage,
                            amount = finalized.amount.value.toLong(),
                            feePaid = MeltFeeAccounting.adding(finalized.feePaid.value.toLong(), swapFee),
                            mintUrl = wallet.mintUrl().url,
                            paymentMethod = quote?.paymentMethod?.toDomain(),
                            request = quote?.request,
                            settlement = MeltSettlement.Settled,
                        ),
                        pendingMelt = null,
                    )
                }
                is CdkMeltConfirmOutcome.Pending -> {
                    // A lightning melt settles in seconds in the healthy case, so
                    // hold the lane and let CDK's `wait()` drive the saga to a
                    // terminal state — it polls the mint internally; the app adds
                    // no polling of its own. Kotlin's bindings cancel cleanly, so
                    // the cap is a plain timeout: on expiry the Rust future is
                    // dropped and the handle goes back to the manager, whose
                    // `watchPendingMelt` re-arms a fresh wait outside the lane
                    // (iOS `MeltSettlementWait` parity). On-chain melts genuinely
                    // take minutes and keep the immediate pending path.
                    val finalized = awaitLightningSettlementOrNull(method = quote?.paymentMethod) {
                        outcome.pending.wait()
                    }
                    when {
                        finalized != null &&
                            (finalized.state == CdkQuoteState.PAID || finalized.state == CdkQuoteState.ISSUED) ->
                            MeltConfirmation(
                                result = MeltPaymentResult(
                                    preimage = finalized.preimage,
                                    amount = finalized.amount.value.toLong(),
                                    feePaid = MeltFeeAccounting.adding(finalized.feePaid.value.toLong(), swapFee),
                                    mintUrl = wallet.mintUrl().url,
                                    paymentMethod = quote?.paymentMethod?.toDomain(),
                                    request = quote?.request,
                                    settlement = MeltSettlement.Settled,
                                ),
                                pendingMelt = null,
                            )
                        finalized != null ->
                            // A non-paid result still needs reservation checks before
                            // the UI may describe the funds as returned.
                            resolveMeltFailure(wallet, quoteId, prepared.operationId(), swapFee, preparationFee)
                        else -> MeltConfirmation(
                            // The bound includes both the reserve and preparation
                            // costs; the quote reserve alone excludes mint fees.
                            result = MeltPaymentResult(
                                preimage = null,
                                amount = quote?.amount?.value?.toLong() ?: 0,
                                feePaid = MeltFeeAccounting.adding(quote?.feeReserve?.value?.toLong(), preparationFee),
                                mintUrl = wallet.mintUrl().url,
                                paymentMethod = quote?.paymentMethod?.toDomain(),
                                request = quote?.request,
                                settlement = MeltSettlement.Pending,
                            ),
                            pendingMelt = outcome.pending,
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (recovery: MeltPaymentRecoveryException) {
            throw recovery
        } catch (_: Exception) {
            resolveMeltFailure(wallet, quoteId, prepared.operationId(), swapFee, preparationFee)
        }
    }

    private suspend fun resolveMeltFailure(
        wallet: CdkWallet,
        quoteId: String,
        operationId: String,
        swapFee: Long? = null,
        preparationFee: Long? = null,
    ): MeltConfirmation {
        val checked = resolveAmbiguousMelt(
            quoteId = quoteId,
            operationId = operationId,
            checkStatus = { wallet.checkMeltQuoteStatus(quoteId) },
            recoverSagas = { wallet.recoverIncompleteSagas(); Unit },
            state = { it.state },
            compensationComplete = {
                val db = checkNotNull(database)
                db.getSaga(operationId) == null &&
                    db.getMeltQuote(quoteId)?.usedByOperation == null &&
                    db.getReservedProofs(operationId).isEmpty()
            },
        )
        val settled = checked.state == CdkQuoteState.PAID || checked.state == CdkQuoteState.ISSUED
        val transaction = if (settled) runCatching {
            wallet.listTransactions(CdkTransactionDirection.OUTGOING).lastOrNull {
                it.quoteId == quoteId && it.status == CdkTransactionStatus.COMPLETED
            }
        }.getOrNull() else null
        return MeltConfirmation(
            result = MeltPaymentResult(
                preimage = transaction?.paymentProof ?: checked.paymentProof,
                amount = transaction?.amount?.value?.toLong() ?: checked.amount.value.toLong(),
                feePaid = if (settled) MeltFeeAccounting.adding(transaction?.fee?.value?.toLong(), swapFee)
                    else MeltFeeAccounting.adding(checked.feeReserve.value.toLong(), preparationFee),
                mintUrl = wallet.mintUrl().url,
                paymentMethod = checked.paymentMethod.toDomain(),
                request = checked.request,
                settlement = if (settled) MeltSettlement.Settled else MeltSettlement.Pending,
            ),
            pendingMelt = null,
        )
    }

    override suspend fun createMaxCrossMintQuotes(sourceMintUrl: String, destinationMintUrl: String): CrossMintQuotes = cdkCall {
        val quotes = walletFor(sourceMintUrl).crossMintTransferQuoteMax(walletFor(destinationMintUrl))
        CrossMintQuotes(
            mintQuote = quotes.mintQuote.toDomain(fallbackAmount = null, fallbackMethod = PaymentMethodKind.Bolt11),
            meltQuote = quotes.meltQuote.toDomain(fallbackMethod = PaymentMethodKind.Bolt11),
            inputFee = quotes.inputFee.value.toLong(),
        )
    }

    override suspend fun meltAllUnspentSkippingSwap(quoteId: String, mintUrl: String, expectedInputFee: Long): MeltConfirmation = cdkCall {
        val quote = database?.getMeltQuote(quoteId)
        val wallet = walletFor(mintUrl)
        val prepared = try {
            wallet.prepareMeltProofs(quoteId, wallet.getProofsByStates(listOf(org.cashudevkit.ProofState.UNSPENT)))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            val stored = try { checkNotNull(database).getMeltQuote(quoteId) }
                catch (_: Exception) { throw MeltPaymentRecoveryException(quoteId, null, unresolved = true) }
            val operation = stored?.usedByOperation ?: throw failure
            return@cdkCall resolveMeltFailure(wallet, quoteId, operation)
        }
        val inputFee = prepared.inputFeeWithoutSwap().value.toLong()
        if (inputFee != expectedInputFee) {
            try {
                withContext(NonCancellable) { prepared.cancel() }
            } catch (_: Exception) {
                // The reservation outlived a failed cancel, so the quote is not
                // reusable until recovery has released it.
                return@cdkCall resolveMeltFailure(wallet, quoteId, prepared.operationId(), 0, inputFee)
            }
            throw MeltInputFeeChangedException(expected = expectedInputFee, actual = inputFee)
        }
        // No respond-async confirmation takes options, so the confirmation
        // itself is the bounded wait. It runs outside this call's scope: if the
        // cap fires, or the caller goes away, the native call keeps running as
        // the settlement watcher and is never cancelled mid-payment.
        val confirmation = settlementScope.async {
            prepared.confirmWithOptions(CdkMeltConfirmOptions(skipSwap = true))
        }
        residualSettlements += confirmation
        confirmation.invokeOnCompletion { residualSettlements -= confirmation }
        try {
            val finalized = withTimeoutOrNull(LIGHTNING_SETTLEMENT_WAIT_MS) { confirmation.await() }
            when {
                finalized == null -> MeltConfirmation(
                    result = MeltPaymentResult(
                        preimage = null,
                        amount = quote?.amount?.value?.toLong() ?: 0,
                        feePaid = MeltFeeAccounting.adding(quote?.feeReserve?.value?.toLong(), expectedInputFee),
                        mintUrl = wallet.mintUrl().url,
                        paymentMethod = quote?.paymentMethod?.toDomain(),
                        request = quote?.request,
                        settlement = MeltSettlement.Pending,
                    ),
                    pendingMelt = null,
                    residualSettlement = confirmation,
                )
                finalized.state == CdkQuoteState.PAID || finalized.state == CdkQuoteState.ISSUED -> MeltConfirmation(
                    result = MeltPaymentResult(
                        preimage = finalized.preimage,
                        amount = finalized.amount.value.toLong(),
                        feePaid = finalized.feePaid.value.toLong(),
                        mintUrl = wallet.mintUrl().url,
                        paymentMethod = quote?.paymentMethod?.toDomain(),
                        request = quote?.request,
                        settlement = MeltSettlement.Settled,
                    ),
                    pendingMelt = null,
                )
                else -> resolveMeltFailure(wallet, quoteId, prepared.operationId(), 0, inputFee)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (recovery: MeltPaymentRecoveryException) {
            throw recovery
        } catch (_: Exception) {
            resolveMeltFailure(wallet, quoteId, prepared.operationId(), 0, inputFee)
        }
    }

    override suspend fun removeUnusedQuotes(mintQuoteId: String, meltQuoteId: String?): Boolean = cdkCall {
        val db = database ?: return@cdkCall false
        try {
            if (meltQuoteId != null) {
                val meltQuote = db.getMeltQuote(meltQuoteId)
                if (meltQuote != null &&
                    (meltQuote.state != CdkQuoteState.UNPAID || meltQuote.usedByOperation != null)
                ) {
                    return@cdkCall false
                }
                val attempts = db.listTransactions(null, CdkTransactionDirection.OUTGOING, null)
                if (attempts.any { it.quoteId == meltQuoteId }) return@cdkCall false
            }
            val mintQuote = db.getMintQuote(mintQuoteId)
            if (mintQuote != null) {
                if (mintQuote.amountPaid.value > 0uL ||
                    mintQuote.amountIssued.value > 0uL ||
                    mintQuote.usedByOperation != null
                ) {
                    return@cdkCall false
                }
                db.removeMintQuote(mintQuoteId)
            }
            if (meltQuoteId != null) db.removeMeltQuote(meltQuoteId)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun meltEndedUnpaid(meltQuoteId: String?): Boolean = cdkCall {
        val db = database
        if (meltQuoteId == null || db == null) return@cdkCall false
        try {
            val quote = db.getMeltQuote(meltQuoteId)
            if (quote == null || quote.state != CdkQuoteState.UNPAID || quote.usedByOperation != null) {
                return@cdkCall false
            }
            val attempts = db.listTransactions(null, CdkTransactionDirection.OUTGOING, null)
            attempts.none { it.quoteId == meltQuoteId && it.status == CdkTransactionStatus.PENDING }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        }
    }

    override suspend fun checkMeltQuoteStatus(quoteId: String, mintUrl: String?): MeltQuoteInfo = cdkCall {
        val stored = database?.getMeltQuote(quoteId)
        val wallet = walletFor(mintUrl ?: stored?.mintUrl?.url ?: firstWallet().mintUrl().url)
        val quote = wallet.checkMeltQuoteStatus(quoteId)
        quote.toDomain(fallbackMethod = stored?.paymentMethod?.toDomain() ?: quote.paymentMethod.toDomain())
    }

    override suspend fun receiveRecoveryCandidates(): List<ReceiveRecoveryCandidate> = cdkCall {
        val db = checkNotNull(database)
        val states = listOf(org.cashudevkit.ProofState.UNSPENT, org.cashudevkit.ProofState.RESERVED, org.cashudevkit.ProofState.PENDING)
        val proofs = db.getProofs(null, null, states, null).map {
            ReceiveRecoveryCandidate(it.mintUrl.url, it.unit.toDomainUnit())
        }
        (proofs + db.getIncompleteSagas().mapNotNull(::receiveRecoveryCandidate)).distinct()
    }

    override suspend fun recoverReceiveAccount(candidate: ReceiveRecoveryCandidate): SagaRecoveryReport = cdkCall {
        val unit = cdkUnit(candidate.unit)
        // Reconstruct a missing account locally. Do not fetch mint metadata or
        // replace an existing wallet/keyset counter before resuming its saga.
        val repo = requireRepository()
        if (!repo.getWallets().any { mintRemovalUrlsMatch(it.mintUrl().url, candidate.mintUrl) && it.unit() == unit }) {
            repo.createWallet(CdkMintUrl(candidate.mintUrl), unit, null)
        }
        val report = walletFor(candidate.mintUrl, unit).recoverIncompleteSagas()
        SagaRecoveryReport(report.recovered.toLong(), report.compensated.toLong(), report.skipped.toLong(), report.failed.toLong())
    }

    override suspend fun recoverIncompleteSagas(mintUrl: String): SagaRecoveryReport = cdkCall {
        val report = walletFor(mintUrl, CdkCurrencyUnit.Sat).recoverIncompleteSagas()
        SagaRecoveryReport(
            recovered = report.recovered.toLong(),
            compensated = report.compensated.toLong(),
            skipped = report.skipped.toLong(),
            failed = report.failed.toLong(),
        )
    }

    override suspend fun sendEcashToken(amount: Long, memo: String?, p2pkPubkey: String?, mintUrl: String, unit: String, p2pkSigningKeys: List<String>): SendTokenResult =
        sendPreparedEcash(amount, memo, p2pkPubkey, mintUrl, unit, p2pkSigningKeys, null)

    override suspend fun sendContactlessToken(amount: Long, mintUrl: String, p2pkSigningKeys: List<String>, callbacks: ContactlessSendCallbacks): SendTokenResult =
        sendPreparedEcash(amount, null, null, mintUrl, "sat", p2pkSigningKeys, callbacks)

    private suspend fun sendPreparedEcash(amount: Long, memo: String?, p2pkPubkey: String?, mintUrl: String, unit: String, p2pkSigningKeys: List<String>, contactless: ContactlessSendCallbacks?): SendTokenResult = cdkCall {
        val cdkUnit = cdkUnit(unit)
        if (!unit.equals("sat", ignoreCase = true)) ensureWalletUnlocked(mintUrl, cdkUnit)
        val conditions = p2pkPubkey?.let { CdkSpendingConditions.P2pk(it, null) }
        // includeFee = false — the token carries exactly the requested amount;
        // the redeem fee is the recipient's cost and their wallet shows it
        // (see the receive preview). Crucially this lets Send Max move the
        // whole balance: all proofs go out as-is, no swap, no fee — also on
        // fee-charging mints. The fee returned below is only the sender-side
        // change-swap fee (zero for a max send). Payment requests stay
        // includeFee = true — there the requester must net the asked amount.
        // Mirrors iOS TokenService.sendTokens.
        val sendOptions = CdkSendOptions(
            memo = memo?.let { CdkSendMemo(it, true) },
            conditions = conditions,
            amountSplitTarget = CdkSplitTarget.None,
            sendKind = CdkSendKind.OnlineExact,
            includeFee = false,
            useP2bk = false,
            maxProofs = null,
            metadata = emptyMap(),
            // Wallet signing keys let prepareSend swap proofs that are already
            // P2PK-locked to us (NPC locked quotes, locked receives) — without
            // them that balance is unspendable. Mirrors iOS TokenService.
            p2pkSigningKeys = p2pkSigningKeys.map(::CdkSecretKey),
            p2pkLockedProofSendMode = CdkP2pkLockedProofSendMode.SWAP,
        )
        val wallet = walletFor(mintUrl, cdkUnit)
        val context = PendingSendRecovery(mintUrl, cdkUnit.toDomainUnit())
        performRecoverableSend(
            context = context,
            inspect = { sendRecoverySnapshot(wallet, context, it) },
            recover = { wallet.recoverIncompleteSagas(); Unit },
        ) { remember ->
            val prepared = wallet.prepareSend(amount.toCdkAmount(), sendOptions)
            remember(prepared.operationId())
            val fee = prepared.fee().value.toLong()
            val token = if (contactless == null) prepared.confirm(memo) else completeContactlessSend(
                approve = { contactless.approve(Math.addExact(amount, fee)) },
                confirm = { prepared.confirm(memo) },
                cancel = { prepared.cancel() },
                reclaim = { wallet.revokeSend(prepared.operationId()); Unit },
                deliver = { contactless.deliver(it.encode()) },
            )
            SendTokenResult(
                token = token.encode(), fee = fee,
                transactionId = SagaTransactionId.transactionIdHex(prepared.operationId()),
            )
        }
    }

    override suspend fun receiveEcashToken(tokenString: String, p2pkSigningKeys: List<String>): Long = cdkCall {
        val token = CdkToken.decode(tokenString)
        val mintUrl = token.mintUrl().url
        // Redeem into the token's own unit — a usd/eur token must never target
        // the sat wallet.
        val tokenUnit = token.unit() ?: CdkCurrencyUnit.Sat
        ensureWalletUnlocked(mintUrl, tokenUnit)
        val amount = walletFor(mintUrl, tokenUnit).receive(
            token = token,
            options = CdkReceiveOptions(
                amountSplitTarget = CdkSplitTarget.None,
                p2pkSigningKeys = p2pkSigningKeys.map(::CdkSecretKey),
                preimages = emptyList(),
                metadata = emptyMap(),
            ),
        )
        amount.value.toLong()
    }

    override suspend fun receiveNfcEcashToken(
        tokenString: String,
        p2pkSigningKeys: List<String>,
    ): NfcReceiveReceipt = cdkCall {
        val token = CdkToken.decode(tokenString)
        val mintUrl = token.mintUrl().url
        val tokenUnit = token.unit() ?: CdkCurrencyUnit.Sat
        ensureWalletUnlocked(mintUrl, tokenUnit)
        val wallet = walletFor(mintUrl, tokenUnit)
        val existingIds = wallet.listTransactions(CdkTransactionDirection.INCOMING)
            .map { it.id.hex }
            .toSet()
        val amount = wallet.receive(
            token = token,
            options = CdkReceiveOptions(
                amountSplitTarget = CdkSplitTarget.None,
                p2pkSigningKeys = p2pkSigningKeys.map(::CdkSecretKey),
                preimages = emptyList(),
                metadata = emptyMap(),
            ),
        ).value.toLong()
        val transactionId = wallet.listTransactions(CdkTransactionDirection.INCOMING)
            .firstOrNull { it.id.hex !in existingIds }
            ?.id?.hex
            ?: throw CdkGatewayUnavailable("CDK did not record the received NFC transaction.")
        NfcReceiveReceipt(amountReceived = amount, transactionId = transactionId)
    }

    override suspend fun settleForeignNfcToken(
        tokenString: String,
        settlementMintUrl: String,
    ): ForeignNfcSettlement = cdkCall {
        settleForeignNfcTokenWithCdk(requireRepository(), checkNotNull(database), tokenString, settlementMintUrl)
    }

    override suspend fun previewReceiveToken(tokenString: String): ReceiveTokenPreview = cdkCall {
        val token = CdkToken.decode(tokenString)
        // CDK needs the token's per-unit wallet; app-level mint approval and
        // visible mint tracking remain the caller's responsibility.
        ensureWalletUnlocked(token.mintUrl().url, token.unit() ?: CdkCurrencyUnit.Sat)
        requireRepository().getTokenData(token).toReceiveTokenPreview()
    }

    override suspend fun activeMintInputFeePpk(mintUrl: String): Long? = cdkCall {
        val keysets = walletFor(mintUrl, CdkCurrencyUnit.Sat)
            .keysets(CdkKeysetLoadPolicy.REFRESH)
        val active = keysets.firstOrNull { it.active == true } ?: keysets.firstOrNull()
        active?.inputFeePpk?.toLong()
    }

    override suspend fun estimateCashuPaymentRequestFee(amountSats: Long, mintUrl: String): Long = cdkCall {
        require(amountSats > 0) { "Cashu Request fee preview requires an amount." }
        val options = CdkSendOptions(
            memo = null,
            conditions = null,
            amountSplitTarget = CdkSplitTarget.None,
            sendKind = CdkSendKind.OnlineExact,
            // CDK's pay_request includes the recipient's input fee so they net
            // the requested amount. The preview must use the identical mode.
            includeFee = true,
            useP2bk = false,
            maxProofs = null,
            metadata = emptyMap(),
            p2pkSigningKeys = emptyList(),
            p2pkLockedProofSendMode = CdkP2pkLockedProofSendMode.SWAP,
        )
        val prepared = walletFor(mintUrl, CdkCurrencyUnit.Sat)
            .prepareSend(amountSats.toCdkAmount(), options)
        try {
            prepared.fee().value.toLong()
        } finally {
            // A preview must never leave proofs reserved for a payment the user
            // has not confirmed.
            prepared.cancel()
        }
    }

    override suspend fun checkTokenSpendable(token: String, mintUrl: String): Boolean = cdkCall {
        val tokenObj = CdkToken.decode(token)
        val tokenUnit = tokenObj.unit() ?: CdkCurrencyUnit.Sat
        ensureWalletUnlocked(mintUrl, tokenUnit)
        val wallet = walletFor(mintUrl, tokenUnit)
        // Resolve proofs with their FULL keyset ids: proofsSimple() can carry a
        // short/legacy IDv2 keyset id that checkProofsSpent rejects ("Short
        // keyset id does not match any of the provided IDv2s"), which would
        // fail every spend check for tokens minted under an IDv2 keyset.
        // Errors must propagate, not coerce to false — false means "unspent",
        // and callers treat it as a definitive claim-state answer.
        val proofs = runCatching {
            tokenObj.proofs(wallet.keysets(null).map { it.toKeySetInfo() })
        }.getOrElse { tokenObj.proofsSimple() }
        val states = wallet.checkProofsSpent(proofs)
        states.any { it }
    }

    override suspend fun listTransactions(unitsByMint: Map<String, List<String>>): List<WalletTransaction> = cdkCall {
        unitsByMint.flatMap { (mintUrl, units) ->
            units.flatMap { unit ->
                checkNotNull(database).listTransactions(CdkMintUrl(mintUrl), null, cdkUnit(unit))
                    .map { it.toDomain(it.unit.toDomainUnit()) }
            }
        }
    }

    override suspend fun payCashuPaymentRequest(encoded: String, customAmountSats: Long?, preferredMintURL: String?) = cdkCall {
        val cdkEncoded = PaymentRequestDecoder.cdkCompatibleCashuPaymentRequest(encoded) ?: encoded
        val request = decodePaymentRequest(cdkEncoded)
        when (request.unit()) {
            null, CdkCurrencyUnit.Sat -> Unit
            else -> throw CdkGatewayUnavailable("Only sat Cashu payment requests are supported.")
        }
        val amount = request.amount() ?: customAmountSats?.takeIf { it > 0 }?.toCdkAmount()
            ?: throw CdkGatewayUnavailable("Cashu payment request requires an amount.")
        val candidateMints = request.mints().map(::normalizeMintUrl)
        val preferredMint = preferredMintURL
            ?.let(::normalizeMintUrl)
            ?.takeIf { candidateMints.isEmpty() || it in candidateMints }
        val mintUrl = preferredMint
            ?: candidateMints.firstOrNull()
            ?: firstWallet().mintUrl().url
        val wallet = walletFor(mintUrl)
        val context = PendingSendRecovery(mintUrl, "sat")
        performRecoverablePaymentRequest(
            context = context,
            inspect = { sendRecoverySnapshot(wallet, context, it) },
            recover = { wallet.recoverIncompleteSagas(); Unit },
            prepare = { wallet.preparePayRequest(request, if (request.amount() == null) amount else null) },
            reclaim = { wallet.revokeSend(it); Unit },
        )
    }

    private suspend fun sendRecoverySnapshot(
        wallet: CdkWallet,
        context: PendingSendRecovery,
        operationIds: List<String> = context.operationIds,
    ): PendingSendRecovery {
        val pending = wallet.getPendingSends()
        val sagas = checkNotNull(database).getIncompleteSagas()
        val transactions = wallet.listTransactions(CdkTransactionDirection.OUTGOING)
        return com.cashu.me.Core.resolvePendingSendRecovery(
            context, wallet.mintUrl().url, sagas, pending, operationIds,
            transactions.associate { it.id.hex to it.status.toDomain() },
        )
    }

    override suspend fun checkSendRecovery(context: PendingSendRecovery): PendingSendRecovery = cdkCall {
        val wallet = walletFor(context.mintUrl, cdkUnit(context.unit))
        wallet.recoverIncompleteSagas()
        sendRecoverySnapshot(wallet, context)
    }

    override suspend fun listPendingSendOperationIds(mintUrl: String, unit: String): List<String> = cdkCall {
        runCatching { walletFor(mintUrl, cdkUnit(unit)).getPendingSends() }.getOrDefault(emptyList())
    }

    override suspend fun checkPendingSendClaimed(mintUrl: String, operationId: String, unit: String): Boolean = cdkCall {
        walletFor(mintUrl, cdkUnit(unit)).checkSendStatus(operationId)
    }

    override suspend fun revokePendingSend(mintUrl: String, operationId: String, unit: String): Long = cdkCall {
        walletFor(mintUrl, cdkUnit(unit)).revokeSend(operationId).value.toLong()
    }

    override suspend fun mintUnissuedQuotes(mintUrl: String, unit: String): Long = cdkCall {
        walletFor(mintUrl, cdkUnit(unit)).mintUnissuedQuotes().value.toLong()
    }

    override suspend fun pendingSendTokenFromSaga(operationId: String): String? = cdkCall {
        val sagaJson = runCatching { database?.getSaga(operationId) }.getOrNull() ?: return@cdkCall null
        runCatching {
            val data = sagaJsonFormat.parseToJsonElement(sagaJson).jsonObject["data"]?.jsonObject
            if (data?.get("kind")?.jsonPrimitive?.content != "send") return@cdkCall null
            data["data"]?.jsonObject?.get("token")?.jsonPrimitive?.content
        }.getOrNull()
    }

    private suspend fun <T> cdkCall(block: suspend () -> T): T =
        withContext(Dispatchers.IO) {
            operationMutex.withLock { block() }
        }

    private suspend fun ensureWalletUnlocked(
        mintUrl: String,
        unit: CdkCurrencyUnit = CdkCurrencyUnit.Sat,
    ) {
        requireRepository().createWallet(CdkMintUrl(mintUrl), unit, null)
    }

    private fun normalizeMintUrl(url: String): String = MintUrlIdentity.normalized(url)

    private fun requireRepository(): CdkWalletRepository =
        repository ?: throw CdkGatewayUnavailable("Wallet repository is not initialized.")

    private suspend fun walletFor(
        mintUrl: String,
        unit: CdkCurrencyUnit = CdkCurrencyUnit.Sat,
    ): CdkWallet = requireRepository().getWallet(CdkMintUrl(mintUrl), unit)

    private suspend fun firstWallet(): CdkWallet =
        requireRepository().getWallets().firstOrNull()
            ?: throw CdkGatewayUnavailable("No mint wallet is available.")

    private inner class CdkNwcServiceHandle(
        private val service: CdkNwcService,
        override val connectionUri: String,
    ) : NwcServiceHandle {
        override suspend fun start() = cdkCall { service.start() }

        override suspend fun stop() = cdkCall { service.stop() }

        override suspend fun isRunning(): Boolean = cdkCall { service.isRunning() }

        override suspend fun close() = cdkCall { service.close() }
    }

    private fun cdkUnit(unit: String): CdkCurrencyUnit = when (unit.lowercase()) {
        "sat" -> CdkCurrencyUnit.Sat
        "msat" -> CdkCurrencyUnit.Msat
        "usd" -> CdkCurrencyUnit.Usd
        "eur" -> CdkCurrencyUnit.Eur
        "auth" -> CdkCurrencyUnit.Auth
        else -> CdkCurrencyUnit.Custom(unit)
    }

    private fun cdkPaymentMethod(method: PaymentMethodKind): CdkPaymentMethod = when (method) {
        PaymentMethodKind.Bolt11 -> CdkPaymentMethod.Bolt11
        PaymentMethodKind.Bolt12 -> CdkPaymentMethod.Bolt12
        PaymentMethodKind.Onchain -> CdkPaymentMethod.Onchain
    }

    private fun CdkPaymentMethod.toDomain(): PaymentMethodKind = when (this) {
        CdkPaymentMethod.Bolt11 -> PaymentMethodKind.Bolt11
        CdkPaymentMethod.Bolt12 -> PaymentMethodKind.Bolt12
        CdkPaymentMethod.Onchain -> PaymentMethodKind.Onchain
        is CdkPaymentMethod.Custom -> PaymentMethodKind.fromRaw(method) ?: PaymentMethodKind.Bolt11
    }

    private fun CdkQuoteState.toMintState(): MintQuoteState = when (this) {
        CdkQuoteState.UNPAID -> MintQuoteState.Unpaid
        CdkQuoteState.PAID -> MintQuoteState.Paid
        CdkQuoteState.PENDING -> MintQuoteState.Pending
        CdkQuoteState.ISSUED -> MintQuoteState.Issued
    }

    private fun CdkQuoteState.toMeltState(): MeltQuoteState = when (this) {
        CdkQuoteState.UNPAID -> MeltQuoteState.Unpaid
        CdkQuoteState.PAID -> MeltQuoteState.Paid
        CdkQuoteState.PENDING -> MeltQuoteState.Pending
        CdkQuoteState.ISSUED -> MeltQuoteState.Paid
    }

    private fun CdkNotificationPayload.referencesMintQuote(quoteId: String): Boolean = when (this) {
        is CdkNotificationPayload.MintQuoteUpdate -> quote.quote == quoteId
        is CdkNotificationPayload.MintQuoteOnchainUpdate -> quote.quote == quoteId
        else -> false
    }

    private fun CdkMintInfo.toDomain(mintUrl: String): MintInfo {
        // NUT-04/05 rails keep their reported-empty state (an absent direction is
        // hidden, not replaced with BOLT11); see CdkMintMethodMapping.kt.
        val mintMethods = nuts.reportedMintMethods()
        val meltMethods = nuts.reportedMeltMethods()
        val units = (nuts.mintUnits + nuts.meltUnits)
            .map { it.toDomainUnit() }
            .distinct()
            .sorted()
            .ifEmpty { listOf("sat") }
        val mintUnits = nuts.mintUnits
            .map { it.toDomainUnit() }
            .distinct()
            .sorted()
            .ifEmpty { listOf("sat") }
        return MintInfo(
            url = mintUrl,
            name = name ?: "Unknown Mint",
            description = description,
            iconUrl = iconUrl,
            units = units,
            mintUnits = mintUnits,
            supportedMintMethods = mintMethods,
            supportedMeltMethods = meltMethods,
            supportsBolt12MintDescription = nuts.reportsBolt12MintDescription(),
            supportsAmountlessBolt11Melt = nuts.reportsAmountlessBolt11Melt(),
            bolt11Sat = nuts.reportedBolt11SatCapability(),
            contacts = contact.orEmpty().map { MintContact(method = it.method, info = it.info) },
            tosUrl = tosUrl,
            software = version?.let { MintSoftware(name = it.name, version = it.version) },
            descriptionLong = descriptionLong,
            motd = motd,
            nutSupport = NutSupport(
                tokenStateCheck = nuts.nut07Supported,
                lightningFeeReturn = nuts.nut08Supported,
                restoreFromSeed = nuts.nut09Supported,
                spendingConditions = nuts.nut10Supported,
                p2pk = nuts.nut11Supported,
                dleq = nuts.nut12Supported,
                htlc = nuts.nut14Supported,
                webSocket = nuts.nut20Supported,
            ),
            lastUpdatedEpochMillis = System.currentTimeMillis(),
        )
    }


    private fun CdkMintQuote.toDomain(
        fallbackAmount: Long?,
        fallbackMethod: PaymentMethodKind,
    ): MintQuoteInfo {
        val method = paymentMethod.toDomain().takeIf { it == fallbackMethod } ?: fallbackMethod
        val paid = amountPaid.value.toLong()
        val issued = amountIssued.value.toLong()
        return MintQuoteInfo(
            id = id,
            request = request,
            amount = mintQuoteAmountForDomain(amount?.value?.toLong(), fallbackAmount, paid, issued),
            isAmountless = amount == null,
            paymentMethod = method,
            state = mintQuoteStateForDomain(method, state.toMintState(), paid, issued),
            expiryEpochSeconds = mintQuoteDisplayExpiry(expiry.toLong()),
            mintUrl = mintUrl.url,
            amountPaid = paid,
            amountIssued = issued,
            updatedAtEpochSeconds = updatedAt.toLong(),
            unit = unit.toDomainUnit(),
        )
    }

    private suspend fun replaceStoredMintQuote(quote: CdkMintQuote) {
        val db = database ?: throw CdkGatewayUnavailable("Wallet database is unavailable.")
        val current = db.getMintQuote(quote.id)
        if (current != null && (quote.version < current.version || quote.usedByOperation != current.usedByOperation)) {
            throw CdkGatewayUnavailable("This receive request changed. Refresh it and try again.")
        }
        db.addMintQuote(quote)
    }

    private suspend fun persistMintQuoteLocalMetadataIfNeeded(
        quote: CdkMintQuote,
        method: PaymentMethodKind,
        fallbackAmount: Long? = null,
    ): CdkMintQuote {
        return persistMintQuoteMetadata(
            quote, method, fallbackAmount,
            save = ::replaceStoredMintQuote,
            reload = { database?.getMintQuote(quote.id) },
        )
    }

    private fun CdkMeltQuote.toDomain(fallbackMethod: PaymentMethodKind): MeltQuoteInfo = MeltQuoteInfo(
        id = id,
        mintUrl = mintUrl?.url.orEmpty(),
        amount = amount.value.toLong(),
        feeReserve = feeReserve.value.toLong(),
        paymentMethod = paymentMethod.toDomain().takeIf { it == fallbackMethod } ?: fallbackMethod,
        state = state.toMeltState(),
        expiryEpochSeconds = expiry.toLong(),
        request = request,
        paymentProof = paymentProof,
    )

    private fun CdkTransaction.toDomain(unit: String): WalletTransaction {
        val direction = if (direction == CdkTransactionDirection.INCOMING) TransactionType.Incoming else TransactionType.Outgoing
        val method = paymentMethod?.toDomain()
        return WalletTransaction(
            id = id.hex,
            amount = amount.value.toLong(),
            type = direction,
            kind = when (method) {
                PaymentMethodKind.Onchain -> TransactionKind.Onchain
                PaymentMethodKind.Bolt11, PaymentMethodKind.Bolt12 -> TransactionKind.Lightning
                null -> TransactionKind.Ecash
            },
            dateEpochMillis = timestamp.toLong() * 1000,
            memo = memo,
            // CDK 0.18 owns the lifecycle: pending while a send is unclaimed /
            // a mint or melt is in flight, failed when failed or revoked.
            status = status.toDomain(),
            mintUrl = mintUrl.url,
            preimage = paymentProof,
            invoice = paymentRequest,
            fee = fee.value.toLong(),
            unit = unit,
            sagaId = sagaId,
            quoteId = quoteId,
            paymentMethod = method,
        )
    }

    private fun CdkTransactionStatus.toDomain(): TransactionStatus = when (this) {
        CdkTransactionStatus.PENDING -> TransactionStatus.Pending
        CdkTransactionStatus.COMPLETED -> TransactionStatus.Completed
        CdkTransactionStatus.FAILED -> TransactionStatus.Failed
    }

    private fun org.cashudevkit.KeySet.toKeySetInfo(): org.cashudevkit.KeySetInfo =
        org.cashudevkit.KeySetInfo(
            id = id,
            unit = unit,
            active = active ?: false,
            inputFeePpk = inputFeePpk,
        )

    private fun requirePositiveAmount(amountSats: Long?, message: String): Long {
        require(amountSats != null && amountSats > 0) { message }
        return amountSats
    }

    private fun bitcoinNetworkFor(mintUrl: String): CdkBitcoinNetwork {
        val host = runCatching { java.net.URI.create(mintUrl).host.orEmpty().lowercase() }
            .getOrDefault("")
        return when {
            host == "onchain.cashudevkit.org" || "signet" in host || "mutinynet" in host -> CdkBitcoinNetwork.SIGNET
            "regtest" in host -> CdkBitcoinNetwork.REGTEST
            "testnet" in host -> CdkBitcoinNetwork.TESTNET
            else -> CdkBitcoinNetwork.BITCOIN
        }
    }

    private fun Long.toCdkAmount(): CdkAmount = CdkAmount(toULong())
}
