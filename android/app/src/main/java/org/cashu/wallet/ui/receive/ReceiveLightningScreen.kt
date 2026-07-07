package org.cashu.wallet.ui.receive

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.CashuRequestStore
import org.cashu.wallet.Core.initialMintQuotePollIntervalMillis
import org.cashu.wallet.Core.nextMintQuotePollIntervalMillis
import org.cashu.wallet.Core.OnchainExplorer
import org.cashu.wallet.Core.OnchainPaymentObservation
import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.UnitAmountEntry
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Core.shouldPollMintQuote
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.MintQuoteState
import org.cashu.wallet.Models.PaymentMethodKind
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.InspectorRow
import org.cashu.wallet.ui.components.MintAvatar
import org.cashu.wallet.ui.components.MintPickerSheet
import org.cashu.wallet.ui.components.NumberPad
import org.cashu.wallet.ui.components.PaymentStatusPhase
import org.cashu.wallet.ui.components.PaymentStatusScreen
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.QrCard
import org.cashu.wallet.ui.components.TwoFaceScreen
import org.cashu.wallet.ui.components.UnitPickerSheet
import org.cashu.wallet.ui.components.copyTextWithToast
import org.cashu.wallet.ui.components.shareText
import org.cashu.wallet.ui.navigation.ReceiveLightningBackAction
import org.cashu.wallet.ui.navigation.receiveLightningBackAction
import org.cashu.wallet.ui.theme.CapsuleShape
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

private sealed interface ReceiveLnFace {
    data object Input : ReceiveLnFace
    data class Display(val quote: MintQuoteInfo) : ReceiveLnFace
}

enum class ReceiveMethodOption(
    val method: PaymentMethodKind,
    val title: String,
    val descriptor: String,
    val autoCreates: Boolean,
) {
    Lightning(PaymentMethodKind.Bolt11, "Lightning invoice", "One-time, instant", false),
    ReusableAny(PaymentMethodKind.Bolt12, "Reusable invoice", "Any amount, paid many times", true),
    Onchain(PaymentMethodKind.Onchain, "On-chain address", "Slower, for larger amounts", true);

    companion object {
        fun optionsFor(methods: List<PaymentMethodKind>): List<ReceiveMethodOption> =
            methods.flatMap { method ->
                when (method) {
                    PaymentMethodKind.Bolt11 -> listOf(Lightning)
                    PaymentMethodKind.Bolt12 -> listOf(ReusableAny)
                    PaymentMethodKind.Onchain -> listOf(Onchain)
                }
            }

        fun current(method: PaymentMethodKind): ReceiveMethodOption = when (method) {
            PaymentMethodKind.Bolt11 -> Lightning
            PaymentMethodKind.Bolt12 -> ReusableAny
            PaymentMethodKind.Onchain -> Onchain
        }
    }
}

private sealed interface ReceiveLnStatus {
    data object Processing : ReceiveLnStatus
    data class Success(val amountLabel: String?, val mintLabel: String?) : ReceiveLnStatus
    data class Failure(val reason: String) : ReceiveLnStatus
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveLightningScreen(
    walletManager: WalletManager,
    settingsManager: SettingsManager,
    cashuRequestStore: CashuRequestStore,
    onClose: () -> Unit,
) {
    val walletState by walletManager.state.collectAsStateWithLifecycle()
    val settings by settingsManager.state.collectAsStateWithLifecycle()
    val formatter = remember { AmountFormatter() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var face: ReceiveLnFace by remember { mutableStateOf(ReceiveLnFace.Input) }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(PaymentMethodKind.Bolt11) }
    var creating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var paymentJustReceived by remember { mutableStateOf(false) }
    var selectedReceiveUnit by remember { mutableStateOf<String?>(null) }
    var unitPickerOpen by remember { mutableStateOf(false) }
    var mintPickerOpen by remember { mutableStateOf(false) }
    var methodPickerOpen by remember { mutableStateOf(false) }
    var reusableAmountEditorOpen by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<ReceiveLnStatus?>(null) }

    val activeMint = walletState.activeMint
    val supportedMethods = activeMint?.supportedMintMethods?.ifEmpty { listOf(PaymentMethodKind.Bolt11) }
        ?: listOf(PaymentMethodKind.Bolt11)
    val methodOptions = ReceiveMethodOption.optionsFor(supportedMethods)
    val selectedOption = ReceiveMethodOption.current(method)

    LaunchedEffect(activeMint) {
        if (method !in supportedMethods) method = supportedMethods.first()
        selectedReceiveUnit = null
    }

    // Mint unit: NUT-04 mintable units only; on-chain always mints sat.
    val effectiveUnit = if (method == PaymentMethodKind.Onchain) {
        "sat"
    } else {
        activeMint?.resolvedMintUnit(selectedReceiveUnit) ?: "sat"
    }
    val currency = CurrencyRegistry.currencyForMintUnit(effectiveUnit)
    val isSatUnit = effectiveUnit.equals("sat", ignoreCase = true)
    val showsUnitSelector = activeMint?.supportsMultipleMintUnits == true &&
        method != PaymentMethodKind.Onchain

    BackHandler(enabled = status != null || face is ReceiveLnFace.Display || methodPickerOpen || unitPickerOpen || mintPickerOpen) {
        when (
            receiveLightningBackAction(
                successStatus = status is ReceiveLnStatus.Success,
                hasStatus = status != null,
                methodPickerOpen = methodPickerOpen,
                unitPickerOpen = unitPickerOpen,
                mintPickerOpen = mintPickerOpen,
                displayingQuote = face is ReceiveLnFace.Display,
            )
        ) {
            ReceiveLightningBackAction.Close -> onClose()
            ReceiveLightningBackAction.ClearStatus -> status = null
            ReceiveLightningBackAction.CloseMethodPicker -> methodPickerOpen = false
            ReceiveLightningBackAction.CloseUnitPicker -> unitPickerOpen = false
            ReceiveLightningBackAction.CloseMintPicker -> mintPickerOpen = false
            ReceiveLightningBackAction.ReturnToInput -> face = ReceiveLnFace.Input
        }
    }

    fun persistQuoteIntent(quote: MintQuoteInfo) {
        persistReceiveLightningQuoteIntent(
            cashuRequestStore = cashuRequestStore,
            quote = quote,
            fallbackMintUrl = activeMint?.url,
        )
    }

    fun showQuote(quote: MintQuoteInfo) {
        paymentJustReceived = false
        status = null
        persistQuoteIntent(quote)
        face = ReceiveLnFace.Display(quote)
    }

    fun createQuote(
        requestMethod: PaymentMethodKind,
        requestAmount: Long?,
        forceNew: Boolean = false,
    ) {
        if (activeMint == null) {
            errorText = "Add a mint first."
            return
        }
        creating = true
        errorText = null
        scope.launch {
            try {
                val quote = chooseReceiveLightningQuote(
                    requestMethod = requestMethod,
                    requestAmount = requestAmount,
                    effectiveUnit = effectiveUnit,
                    forceNew = forceNew,
                    existingAmountlessOffer = { walletManager.existingAmountlessOffer() },
                    existingOnchainMintQuote = { walletManager.existingOnchainMintQuote() },
                    createMintQuote = { quoteAmount, quoteMethod, quoteUnit ->
                        walletManager.createMintQuote(
                            amount = quoteAmount,
                            method = quoteMethod,
                            unit = quoteUnit,
                        )
                    },
                )
                showQuote(quote)
            } catch (t: Throwable) {
                errorText = t.message ?: "Could not create request."
            } finally {
                creating = false
            }
        }
    }

    fun applyMethodOption(option: ReceiveMethodOption) {
        methodPickerOpen = false
        method = option.method
        amount = ""
        errorText = null
        when (option) {
            ReceiveMethodOption.Lightning -> Unit
            ReceiveMethodOption.ReusableAny -> createQuote(PaymentMethodKind.Bolt12, requestAmount = null)
            ReceiveMethodOption.Onchain -> createQuote(PaymentMethodKind.Onchain, requestAmount = null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val current = face
                    val title = when (current) {
                        ReceiveLnFace.Input -> "Receive"
                        is ReceiveLnFace.Display -> when (current.quote.paymentMethod) {
                            PaymentMethodKind.Bolt11 -> "Lightning Invoice"
                            PaymentMethodKind.Bolt12 -> "Reusable Invoice"
                            PaymentMethodKind.Onchain -> "Bitcoin Address"
                        }
                    }
                    Text(title, style = MaterialTheme.typography.titleMedium)
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when (face) {
                            ReceiveLnFace.Input -> onClose()
                            is ReceiveLnFace.Display -> face = ReceiveLnFace.Input
                        }
                    }) {
                        Icon(
                            imageVector = when (face) {
                                ReceiveLnFace.Input -> Icons.Outlined.Close
                                is ReceiveLnFace.Display -> Icons.AutoMirrored.Outlined.ArrowBack
                            },
                            contentDescription = "Close",
                        )
                    }
                },
                actions = {
                    val current = face
                    if (current is ReceiveLnFace.Display) {
                        IconButton(onClick = {
                            context.shareText(current.quote.request, subject = "Payment request")
                        }) {
                            Icon(Icons.Outlined.IosShare, contentDescription = "Share")
                        }
                    } else if (current is ReceiveLnFace.Input) {
                        if (methodOptions.size > 1) {
                            IconButton(onClick = { methodPickerOpen = true }) {
                                Icon(
                                    imageVector = selectedOption.method.menuIcon,
                                    contentDescription = "Payment method",
                                )
                            }
                        }
                        if (showsUnitSelector) {
                            androidx.compose.material3.TextButton(onClick = { unitPickerOpen = true }) {
                                Text(
                                    text = effectiveUnit.uppercase(),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        when (val currentStatus = status) {
            ReceiveLnStatus.Processing -> {
                PaymentStatusScreen(
                    phase = PaymentStatusPhase.Processing,
                    title = "Minting payment…",
                    modifier = Modifier.padding(padding),
                )
                return@Scaffold
            }
            is ReceiveLnStatus.Success -> {
                val detail = listOfNotNull(currentStatus.amountLabel, currentStatus.mintLabel)
                    .joinToString(" · ")
                    .takeIf { it.isNotBlank() }
                PaymentStatusScreen(
                    phase = PaymentStatusPhase.Success,
                    title = "Payment received",
                    detail = detail,
                    modifier = Modifier.padding(padding),
                    onDone = onClose,
                )
                return@Scaffold
            }
            is ReceiveLnStatus.Failure -> {
                PaymentStatusScreen(
                    phase = PaymentStatusPhase.Failure,
                    title = "Could not mint",
                    detail = currentStatus.reason,
                    doneLabel = "Try again",
                    modifier = Modifier.padding(padding),
                    onDone = { status = null },
                )
                return@Scaffold
            }
            null -> Unit
        }
        if (creating && (method == PaymentMethodKind.Onchain || (method == PaymentMethodKind.Bolt12 && amount.isBlank()))) {
            CreatingOverlay(
                text = if (method == PaymentMethodKind.Onchain) {
                    "Generating address"
                } else {
                    "Creating reusable invoice"
                },
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            )
            return@Scaffold
        }
        TwoFaceScreen(
            targetState = face,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            forward = { initial, target ->
                initial is ReceiveLnFace.Input && target is ReceiveLnFace.Display
            },
            label = "receive-lightning-face",
        ) { current ->
            when (current) {
                ReceiveLnFace.Input -> InputFace(
                    amount = amount,
                    onAmountChange = { amount = it; errorText = null },
                    selectedMethod = method,
                    creating = creating,
                    mint = activeMint,
                    mintBalanceText = activeMint?.let {
                        formatter.formatWalletSats(it.balance, settings.useBitcoinSymbol)
                    },
                    onPickMint = { mintPickerOpen = true },
                    unitLabel = if (isSatUnit) "sat" else effectiveUnit.uppercase(),
                    decimals = currency.decimals,
                    errorText = errorText,
                    onCreate = {
                        val explicit = UnitAmountEntry.baseUnits(amount, currency.decimals)
                            .takeIf { it > 0 }
                        val needsAmount = method == PaymentMethodKind.Bolt11
                        if (needsAmount && explicit == null) {
                            errorText = "Enter an amount."
                            return@InputFace
                        }
                        createQuote(
                            requestMethod = method,
                            requestAmount = if (method == PaymentMethodKind.Onchain) null else explicit,
                        )
                    },
                )

                is ReceiveLnFace.Display -> {
                    var liveQuote by remember(current.quote.id) { mutableStateOf(current.quote) }
                    var onchainObservation by remember(current.quote.id) { mutableStateOf<OnchainPaymentObservation?>(null) }
                    var nowEpochSeconds by remember(current.quote.id) {
                        mutableStateOf(System.currentTimeMillis() / 1000)
                    }
                    val expiryRemaining = liveQuote.expiryEpochSeconds
                        ?.let { it - nowEpochSeconds }
                        ?.takeIf { it > 0 }
                    val isExpired = liveQuote.expiryEpochSeconds
                        ?.takeIf { it > 0 }
                        ?.let { nowEpochSeconds >= it } == true
                    LaunchedEffect(current.quote.id, current.quote.paymentMethod, settings.useWebsockets) {
                        suspend fun pollUntilTerminal() {
                            var intervalMillis = initialMintQuotePollIntervalMillis(current.quote.paymentMethod)
                            while (shouldPollMintQuote(liveQuote.state, liveQuote.expiryEpochSeconds, System.currentTimeMillis() / 1000)) {
                                delay(intervalMillis)
                                runCatching { walletManager.pollMintQuote(current.quote.id) }
                                    .getOrNull()
                                    ?.let { liveQuote = it }
                                intervalMillis = nextMintQuotePollIntervalMillis(
                                    currentIntervalMillis = intervalMillis,
                                    method = current.quote.paymentMethod,
                                )
                            }
                        }
                        if (settings.useWebsockets) {
                            try {
                                walletManager.subscribeToMintQuote(current.quote.id)
                                    .collectLatest { liveQuote = it }
                            } catch (error: Throwable) {
                                if (error is CancellationException) throw error
                                pollUntilTerminal()
                            }
                        } else {
                            pollUntilTerminal()
                        }
                        // Some gateways complete their quote stream without a
                        // terminal quote; continue with manual polling in the
                        // same watcher job so navigation still cancels cleanly.
                        while (shouldPollMintQuote(liveQuote.state, liveQuote.expiryEpochSeconds, System.currentTimeMillis() / 1000)) {
                            pollUntilTerminal()
                        }
                    }
                    LaunchedEffect(liveQuote.expiryEpochSeconds) {
                        val expiry = liveQuote.expiryEpochSeconds?.takeIf { it > 0 } ?: return@LaunchedEffect
                        while (true) {
                            nowEpochSeconds = System.currentTimeMillis() / 1000
                            if (nowEpochSeconds >= expiry) break
                            delay(1_000)
                        }
                    }
                    LaunchedEffect(liveQuote.id, liveQuote.state, liveQuote.amount) {
                        val expectedAmount = liveQuote.amount
                        if (liveQuote.paymentMethod != PaymentMethodKind.Onchain || expectedAmount == null) {
                            onchainObservation = null
                            return@LaunchedEffect
                        }
                        while (shouldPollMintQuote(liveQuote.state, liveQuote.expiryEpochSeconds, System.currentTimeMillis() / 1000)) {
                            onchainObservation = OnchainExplorer.observePayment(
                                address = liveQuote.request,
                                mintUrl = liveQuote.mintUrl,
                                expectedAmount = expectedAmount,
                                createdAfterEpochMillis = 0L,
                            )
                            delay(30_000)
                        }
                    }
                    LaunchedEffect(liveQuote.state, liveQuote.amountIssued, liveQuote.amountPaid) {
                        val terminal = liveQuote.state == MintQuoteState.Paid || liveQuote.state == MintQuoteState.Issued
                        if (!terminal || status != null) return@LaunchedEffect
                        status = ReceiveLnStatus.Processing
                        val result = settleReceiveLightningQuote(
                            quote = liveQuote,
                            settlementGateway = object : ReceiveLightningSettlementGateway {
                                override suspend fun refreshBalance() {
                                    walletManager.refreshBalance()
                                }

                                override suspend fun loadTransactions() {
                                    walletManager.loadTransactions()
                                }

                                override suspend fun mintTokens(quoteId: String): Long =
                                    walletManager.mintTokens(quoteId)
                            },
                            cashuRequestStore = cashuRequestStore,
                        )
                        result
                                .onSuccess { amount ->
                                    paymentJustReceived = true
                                    status = ReceiveLnStatus.Success(
                                        amountLabel = liveQuote.amount?.let {
                                            formatQuoteAmount(it, liveQuote.unit, formatter, settings.useBitcoinSymbol)
                                        },
                                        mintLabel = activeMint?.name,
                                    )
                                }
                                .onFailure { error ->
                                    status = ReceiveLnStatus.Failure(error.message ?: "Could not mint this payment.")
                                }
                    }
                    DisplayFace(
                        quote = liveQuote,
                        amountLabel = liveQuote.amount?.let {
                            formatQuoteAmount(it, liveQuote.unit, formatter, settings.useBitcoinSymbol)
                        },
                        mintLabel = activeMint?.name,
                        expiryText = expiryRemaining?.let(::formatTimeRemaining),
                        isExpired = isExpired,
                        onchainObservation = onchainObservation,
                        showCelebration = paymentJustReceived,
                        onCopy = { clipboard.copyTextWithToast(context, liveQuote.request) },
                        onDone = onClose,
                        onEditReusableAmount = if (liveQuote.paymentMethod == PaymentMethodKind.Bolt12) {
                            { reusableAmountEditorOpen = true }
                        } else null,
                        onUseNewAddress = if (liveQuote.paymentMethod == PaymentMethodKind.Onchain) {
                            { createQuote(PaymentMethodKind.Onchain, requestAmount = null, forceNew = true) }
                        } else null,
                        onOpenExplorer = { url -> context.openInBrowser(url) },
                    )
                }
            }
        }
    }

    if (mintPickerOpen) {
        MintPickerSheet(
            mints = walletState.mints,
            activeMintUrl = activeMint?.url,
            onSelect = { mint ->
                scope.launch { walletManager.setActiveMint(mint) }
                amount = ""
                errorText = null
                mintPickerOpen = false
            },
            onDismiss = { mintPickerOpen = false },
        )
    }

    if (unitPickerOpen) {
        UnitPickerSheet(
            units = activeMint?.effectiveMintUnits ?: listOf("sat"),
            selectedUnit = effectiveUnit,
            onSelect = {
                selectedReceiveUnit = it
                amount = ""
                errorText = null
                unitPickerOpen = false
            },
            onDismiss = { unitPickerOpen = false },
        )
    }

    if (methodPickerOpen) {
        ReceiveMethodPickerSheet(
            options = methodOptions,
            selectedOption = selectedOption,
            onSelect = ::applyMethodOption,
            onDismiss = { methodPickerOpen = false },
        )
    }

    val displayQuote = (face as? ReceiveLnFace.Display)?.quote
    if (reusableAmountEditorOpen && displayQuote != null) {
        ReusableAmountDialog(
            initialAmount = displayQuote.amount,
            unit = displayQuote.unit,
            onSave = { nextAmount ->
                reusableAmountEditorOpen = false
                createQuote(
                    requestMethod = PaymentMethodKind.Bolt12,
                    requestAmount = nextAmount?.takeIf { it > 0 },
                    forceNew = nextAmount != null && nextAmount > 0,
                )
            },
            onDismiss = { reusableAmountEditorOpen = false },
        )
    }
}

/**
 * First face, in the iOS element order: mint selector row (top) → amount hero
 * (with an ON-CHAIN badge for on-chain) → error → number pad → create CTA. The
 * method picker lives in the top bar, not on the canvas.
 */
@Composable
private fun InputFace(
    amount: String,
    onAmountChange: (String) -> Unit,
    selectedMethod: PaymentMethodKind,
    creating: Boolean,
    mint: MintInfo?,
    mintBalanceText: String?,
    onPickMint: () -> Unit,
    unitLabel: String,
    decimals: Int,
    errorText: String?,
    onCreate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.comfortable)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(CashuTheme.spacing.default))
        if (mint != null) {
            MintSelectorRow(
                mint = mint,
                balanceText = mintBalanceText,
                onClick = onPickMint,
            )
        }
        Spacer(Modifier.height(CashuTheme.spacing.loose))
        if (selectedMethod == PaymentMethodKind.Onchain) {
            Text(
                text = "ON-CHAIN",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .background(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = CapsuleShape,
                    )
                    .padding(
                        horizontal = CashuTheme.spacing.default,
                        vertical = CashuTheme.spacing.micro,
                    ),
            )
            Spacer(Modifier.height(CashuTheme.spacing.snug))
        }
        AmountText(
            text = when {
                amount.isNotEmpty() -> amount
                decimals > 0 -> "0." + "0".repeat(decimals)
                else -> "0"
            },
            style = MaterialTheme.typography.displayMedium.withMonoDigits(),
        )
        Text(
            text = unitLabel,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (errorText != null) {
            Spacer(Modifier.height(CashuTheme.spacing.default))
            InlineNotice(text = errorText)
        }
        Spacer(Modifier.height(CashuTheme.spacing.default))
        NumberPad(amount = amount, onAmountChange = onAmountChange, decimals = decimals)
        Spacer(Modifier.height(CashuTheme.spacing.micro))
        PrimaryButton(
            text = if (creating) "Creating…" else selectedMethod.createActionTitle,
            onClick = onCreate,
            enabled = !creating,
            loading = creating,
        )
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** Mint row: avatar + name + balance + change affordance (iOS mintSelector). */
@Composable
private fun MintSelectorRow(
    mint: MintInfo,
    balanceText: String?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = CashuTheme.spacing.snug),
    ) {
        MintAvatar(mint = mint)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = mint.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (balanceText != null) {
                Text(
                    text = "Balance $balanceText",
                    style = MaterialTheme.typography.bodySmall.withMonoDigits(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Icon(
            imageVector = Icons.Outlined.UnfoldMore,
            contentDescription = "Change mint",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(CashuTheme.spacing.loose),
        )
    }
}

@Composable
private fun CreatingOverlay(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = CashuTheme.spacing.comfortable),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            strokeWidth = 3.dp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(CashuTheme.spacing.comfortable))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReceiveMethodPickerSheet(
    options: List<ReceiveMethodOption>,
    selectedOption: ReceiveMethodOption,
    onSelect: (ReceiveMethodOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        ReceiveMethodPickerContent(
            options = options,
            selectedOption = selectedOption,
            onSelect = onSelect,
            modifier = Modifier
                .padding(horizontal = CashuTheme.spacing.comfortable)
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
        )
    }
}

@Composable
fun ReceiveMethodPickerContent(
    options: List<ReceiveMethodOption>,
    selectedOption: ReceiveMethodOption,
    onSelect: (ReceiveMethodOption) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = "Receive with",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(
                horizontal = CashuTheme.spacing.snug,
                vertical = CashuTheme.spacing.default,
            ),
        )
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSelect(option) }
                    .padding(
                        horizontal = CashuTheme.spacing.snug,
                        vertical = CashuTheme.spacing.default,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = option.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = option.descriptor,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = option.method.menuIcon,
                    contentDescription = if (option == selectedOption) "Selected" else null,
                    tint = if (option == selectedOption) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(CashuTheme.spacing.loose),
                )
            }
        }
        Spacer(Modifier.height(CashuTheme.spacing.snug))
    }
}

@Composable
private fun ReusableAmountDialog(
    initialAmount: Long?,
    unit: String,
    onSave: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var rawAmount by remember(initialAmount) { mutableStateOf(initialAmount?.toString().orEmpty()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    androidx.compose.material3.AlertDialog(
        modifier = Modifier.imePadding(),
        onDismissRequest = onDismiss,
        title = { Text("Edit amount") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default)) {
                org.cashu.wallet.ui.components.CashuTextField(
                    value = rawAmount,
                    onValueChange = {
                        rawAmount = it.filter { ch -> ch.isDigit() }
                        errorText = null
                    },
                    label = "Amount",
                    supportingText = "Leave blank for any amount. Values are in ${unit.uppercase()}.",
                    isError = errorText != null,
                    singleLine = true,
                )
                if (errorText != null) InlineNotice(text = errorText!!)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = rawAmount.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
                if (rawAmount.isNotBlank() && (parsed == null || parsed <= 0)) {
                    errorText = "Enter a positive whole number or leave it blank."
                    return@TextButton
                }
                onSave(parsed)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private val PaymentMethodKind.menuIcon
    get() = when (this) {
        PaymentMethodKind.Bolt11 -> Icons.Outlined.Bolt
        PaymentMethodKind.Bolt12 -> Icons.Outlined.Repeat
        PaymentMethodKind.Onchain -> Icons.Outlined.CurrencyBitcoin
    }

private val PaymentMethodKind.createActionTitle: String
    get() = when (this) {
        PaymentMethodKind.Bolt11 -> "Create invoice"
        PaymentMethodKind.Bolt12 -> "Create invoice"
        PaymentMethodKind.Onchain -> "Create address"
    }

private val PaymentMethodKind.receiveRequestDisplayName: String
    get() = when (this) {
        PaymentMethodKind.Bolt11 -> "invoice"
        PaymentMethodKind.Bolt12 -> "invoice"
        PaymentMethodKind.Onchain -> "address"
    }

@Composable
private fun DisplayFace(
    quote: MintQuoteInfo,
    amountLabel: String?,
    mintLabel: String?,
    expiryText: String?,
    isExpired: Boolean,
    onchainObservation: OnchainPaymentObservation?,
    showCelebration: Boolean,
    onCopy: () -> Unit,
    onDone: () -> Unit,
    onEditReusableAmount: (() -> Unit)?,
    onUseNewAddress: (() -> Unit)?,
    onOpenExplorer: (String) -> Unit,
) {
    val isPaid = quote.state == MintQuoteState.Paid ||
        quote.state == MintQuoteState.Issued ||
        quote.amountIssued > 0L
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = CashuTheme.spacing.comfortable,
                vertical = CashuTheme.spacing.comfortable,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.comfortable),
    ) {
        QrCard(content = quote.request, shareSubject = "Payment request", staticOnly = true)
        if (amountLabel != null) {
            AmountText(
                text = amountLabel,
                style = MaterialTheme.typography.headlineSmall.withMonoDigits(),
            )
        }
        QuoteStatusRow(
            isPaid = isPaid,
            showCelebration = showCelebration,
            isExpired = isExpired,
            onchainObservation = onchainObservation,
            paymentMethod = quote.paymentMethod,
        )
        if (!isPaid && !isExpired && expiryText != null) {
            Text(
                text = "Expires in $expiryText",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            InspectorRow(
                label = "Mint",
                value = mintLabel ?: quote.mintUrl.orEmpty().ifBlank { "Unknown mint" },
                leadingIcon = Icons.Outlined.Bolt,
            )
            if (quote.paymentMethod == PaymentMethodKind.Bolt12) {
                CanvasDivider(leadingInset = 16)
                InspectorRow(
                    label = "Amount",
                    value = amountLabel ?: "Any",
                    leadingIcon = Icons.Outlined.Repeat,
                    editable = onEditReusableAmount != null,
                    onClick = onEditReusableAmount,
                    valueMonospaced = amountLabel != null,
                )
            }
        }
        quote.onchainExplorerUrl(onchainObservation)?.let { explorerUrl ->
            TextButton(onClick = { onOpenExplorer(explorerUrl) }) {
                Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null)
                Text(
                    text = if (onchainObservation == null) {
                        "View address in block explorer"
                    } else {
                        "View transaction in block explorer"
                    },
                )
            }
        }
        if (quote.paymentMethod == PaymentMethodKind.Onchain && onUseNewAddress != null) {
            GhostButton(
                text = "Use new address",
                onClick = onUseNewAddress,
            )
        }
        Spacer(Modifier.height(CashuTheme.spacing.snug))
        PrimaryButton(
            text = if (copied) "Copied" else "Copy ${quote.paymentMethod.receiveRequestDisplayName}",
            onClick = {
                onCopy()
                copied = true
            },
        )
        GhostButton(
            text = "Done",
            onClick = onDone,
        )
        Spacer(Modifier.navigationBarsPadding())
    }
}

private fun formatQuoteAmount(
    amount: Long,
    unit: String,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
): String =
    if (unit.equals("sat", ignoreCase = true)) {
        formatter.formatWalletSats(amount, useBitcoinSymbol)
    } else {
        CurrencyAmount(amount, CurrencyRegistry.currencyForMintUnit(unit)).formatted()
    }

private fun formatTimeRemaining(seconds: Long): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val remainingSeconds = seconds % 60
    return when {
        hours > 0 -> if (minutes > 0) "${hours}h ${minutes}m" else "${hours}h"
        minutes > 0 -> "${minutes}m ${remainingSeconds}s"
        else -> "${remainingSeconds}s"
    }
}

private fun MintQuoteInfo.onchainExplorerUrl(observation: OnchainPaymentObservation?): String? {
    if (paymentMethod != PaymentMethodKind.Onchain) return null
    return observation?.txid?.let {
        OnchainExplorer.transactionWebUrl(txid = it, address = request, mintUrl = mintUrl)
    } ?: OnchainExplorer.addressWebUrl(address = request, mintUrl = mintUrl)
}

private fun Context.openInBrowser(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
}

@Composable
private fun QuoteStatusRow(
    isPaid: Boolean,
    showCelebration: Boolean,
    isExpired: Boolean,
    onchainObservation: OnchainPaymentObservation?,
    paymentMethod: PaymentMethodKind,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Spacer(modifier = Modifier.weight(1f))
        if (isPaid) {
            // Single celebration beat: one green check grows in gently (0.9 → 1);
            // the label carries the moment, no doubled glyphs.
            AnimatedVisibility(
                visible = true,
                enter = scaleIn(
                    animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                    initialScale = 0.9f,
                ) + fadeIn(),
                exit = fadeOut(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = CashuTheme.colors.received,
                    modifier = Modifier.size(CashuTheme.spacing.loose),
                )
            }
            Text(
                text = if (showCelebration) "Payment received!" else "Paid",
                style = MaterialTheme.typography.titleMedium,
                color = CashuTheme.colors.received,
            )
        } else if (isExpired) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(CashuTheme.spacing.loose),
            )
            Text(
                text = "Expired",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            val transition = rememberInfiniteTransition(label = "waiting-pulse")
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1100),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "waiting-pulse-alpha",
            )
            Box(modifier = Modifier.alpha(alpha)) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = CashuTheme.colors.pending,
                    modifier = Modifier.size(CashuTheme.spacing.loose),
                )
            }
            Text(
                text = when {
                    paymentMethod == PaymentMethodKind.Onchain && onchainObservation != null ->
                        "${onchainObservation.statusText}. Trying to mint…"
                    paymentMethod == PaymentMethodKind.Onchain -> "Waiting for on-chain payment…"
                    else -> "Waiting for payment…"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
    }
}
