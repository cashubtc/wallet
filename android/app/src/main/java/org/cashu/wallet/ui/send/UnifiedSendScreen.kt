package org.cashu.wallet.ui.send

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Money
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.CashuPaymentRequestRoute
import org.cashu.wallet.Core.PaymentRequestDecodeResult
import org.cashu.wallet.Core.PaymentRequestDecoder
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.TokenParser
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Core.compatibleMintsForCashuPaymentRequest
import org.cashu.wallet.Core.routeForCashuPaymentRequest
import org.cashu.wallet.Models.MeltPaymentResult
import org.cashu.wallet.Models.MeltQuoteInfo
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.PaymentMethodKind
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.CashuTextField
import org.cashu.wallet.ui.components.EmptyState
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.InspectorRow
import org.cashu.wallet.ui.components.MintAvatar
import org.cashu.wallet.ui.components.MintPickerSheet
import org.cashu.wallet.ui.components.NoticeSeverity
import org.cashu.wallet.ui.components.NumberPad
import org.cashu.wallet.ui.components.PaymentStatusPhase
import org.cashu.wallet.ui.components.PaymentStatusScreen
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.QrCard
import org.cashu.wallet.ui.components.TwoFaceScreen
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

// iOS UnifiedSendView metrics: 60pt round method buttons spaced 28pt.
private val MethodButtonSize = 60.dp
private val MethodRowSpacing = 28.dp
private const val TYPE_DEBOUNCE_MS = 400L

private enum class SendStep { Input, Amount, Confirm }

/** The rail the destination locked onto. */
private sealed interface LockedRail {
    val raw: String

    data class Melt(
        override val raw: String,
        val decoded: PaymentRequestDecodeResult,
        val knownAmount: Long?,
    ) : LockedRail

    data class Creq(
        override val raw: String,
        val decoded: PaymentRequestDecodeResult.CashuPaymentRequest,
        val knownAmount: Long?,
    ) : LockedRail
}

private sealed interface SendStatus {
    data object Sending : SendStatus
    data class Sent(val result: MeltPaymentResult?) : SendStatus
    data class Failed(val reason: String) : SendStatus
}

/**
 * The Send surface (iOS UnifiedSendView): one destination field that infers the
 * rail, a Scan · Ecash · Tap ways-to-send row, then amount → confirm → status.
 * Home's Send button lands here directly — there is no send chooser.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedSendScreen(
    walletManager: WalletManager,
    settingsManager: SettingsManager,
    onClose: () -> Unit,
    onScan: () -> Unit,
    onContactless: () -> Unit,
    onSendEcash: () -> Unit,
    onOpenReceiveToken: (String) -> Unit,
    onOpenMints: () -> Unit,
    onReceive: () -> Unit,
    prefilledPayload: String? = null,
    onPrefilledConsumed: () -> Unit = {},
) {
    val walletState by walletManager.state.collectAsStateWithLifecycle()
    val settings by settingsManager.state.collectAsStateWithLifecycle()
    val formatter = remember { AmountFormatter() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasNfc = remember(context) {
        context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_NFC) &&
            android.nfc.NfcAdapter.getDefaultAdapter(context) != null
    }

    var step by remember { mutableStateOf(SendStep.Input) }
    var status by remember { mutableStateOf<SendStatus?>(null) }
    var destination by remember { mutableStateOf("") }
    var locked by remember { mutableStateOf<LockedRail?>(null) }
    var inputHint by remember { mutableStateOf<String?>(null) }
    // A recipient the user backed out of: still valid, must not auto-advance.
    var suppressedValue by remember { mutableStateOf<String?>(null) }
    var amount by remember { mutableStateOf("") }
    var cameFromAmount by remember { mutableStateOf(false) }
    var selectedMintUrl by remember { mutableStateOf<String?>(null) }
    var mintPickerOpen by remember { mutableStateOf(false) }
    var addMintToPayOpen by remember { mutableStateOf(false) }
    var meltQuote by remember { mutableStateOf<MeltQuoteInfo?>(null) }
    var quoteRetryNonce by remember { mutableIntStateOf(0) }
    var topUpQuote by remember { mutableStateOf<MintQuoteInfo?>(null) }
    var topUpLoading by remember { mutableStateOf(false) }
    var topUpError by remember { mutableStateOf<String?>(null) }
    var quoteError by remember { mutableStateOf<String?>(null) }
    var confirmError by remember { mutableStateOf<String?>(null) }

    val activeMintUrl = selectedMintUrl ?: walletState.activeMint?.url
    val enteredAmount = amount.toLongOrNull() ?: 0L
    val confirmAmount = locked?.let { rail ->
        when (rail) {
            is LockedRail.Melt -> rail.knownAmount ?: enteredAmount
            is LockedRail.Creq -> rail.knownAmount ?: enteredAmount
        }
    } ?: 0L
    val cashuRoute = (locked as? LockedRail.Creq)?.let { rail ->
        routeForCashuPaymentRequest(
            rawRequest = rail.raw,
            request = rail.decoded.summary,
            mints = walletState.mints,
            selectedMintUrl = selectedMintUrl,
            activeMintUrl = walletState.activeMint?.url,
            amountSats = confirmAmount,
        )
    }
    val activeMint = when (val route = cashuRoute) {
        is CashuPaymentRequestRoute.PayWithEcash -> route.mint
        else -> walletState.mints.firstOrNull { it.url == activeMintUrl } ?: walletState.activeMint
    }

    fun reset(toInput: Boolean = true) {
        locked = null
        amount = ""
        meltQuote = null
        topUpQuote = null
        topUpError = null
        topUpLoading = false
        quoteError = null
        confirmError = null
        cameFromAmount = false
        if (toInput) step = SendStep.Input
    }

    /** Rail inference (iOS handleDestinationChange → advance). */
    fun advance(raw: String) {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed == suppressedValue) return
        inputHint = null
        var decoded = PaymentRequestDecoder.decode(
            trimmed,
            includeCashuPaymentRequests = true,
            preferCashuPaymentRequests = true,
        )
        var request = trimmed
        if (decoded is PaymentRequestDecodeResult.CashuPaymentRequest &&
            compatibleMintsForCashuPaymentRequest(decoded.summary, walletState.mints).isEmpty()
        ) {
            // BIP-321 payloads can carry a Lightning leg alongside the creq;
            // fall back to it when none of the requested mints are held.
            val fallback = PaymentRequestDecoder.decode(trimmed)
            if (fallback !is PaymentRequestDecodeResult.Unrecognized) {
                decoded = fallback
                request = PaymentRequestDecoder.encodedLightningRequest(trimmed) ?: trimmed
            }
        }
        when (decoded) {
            is PaymentRequestDecodeResult.Bolt11 -> {
                val known = decoded.amountSats
                if (known == null || known <= 0L) {
                    inputHint = "This BOLT11 invoice doesn't include an amount. Ask for an amount-specific invoice before paying."
                } else {
                    locked = LockedRail.Melt(request, decoded, known)
                    cameFromAmount = false
                    step = SendStep.Confirm
                }
            }
            is PaymentRequestDecodeResult.Bolt12 -> {
                val known = decoded.amountSats
                if (known == null || known <= 0L) {
                    inputHint = "This BOLT12 offer doesn't include an amount. Amountless offers are not payable here yet."
                } else {
                    locked = LockedRail.Melt(request, decoded, known)
                    cameFromAmount = false
                    step = SendStep.Confirm
                }
            }
            is PaymentRequestDecodeResult.LightningAddress,
            is PaymentRequestDecodeResult.Onchain -> {
                locked = LockedRail.Melt(request, decoded, knownAmount = null)
                step = SendStep.Amount
            }
            is PaymentRequestDecodeResult.CashuPaymentRequest -> {
                val known = decoded.summary.amount?.takeIf { it > 0 }
                locked = LockedRail.Creq(request, decoded, known)
                if (!decoded.summary.isSatUnit || known != null) {
                    cameFromAmount = false
                    step = SendStep.Confirm
                } else {
                    step = SendStep.Amount
                }
            }
            PaymentRequestDecodeResult.Unrecognized -> {
                val token = TokenParser.extractToken(trimmed)
                if (token != null) {
                    onOpenReceiveToken(token)
                } else {
                    inputHint =
                        "Unrecognized — try a Lightning address, invoice, Bitcoin address, or Cashu Request"
                }
            }
        }
    }

    // Typing debounces; paste/scan advance immediately.
    LaunchedEffect(destination) {
        if (step != SendStep.Input || status != null) return@LaunchedEffect
        val trimmed = destination.trim()
        if (trimmed != suppressedValue) suppressedValue = null
        if (trimmed.isEmpty()) {
            inputHint = null
            return@LaunchedEffect
        }
        delay(TYPE_DEBOUNCE_MS)
        advance(destination)
    }

    LaunchedEffect(prefilledPayload) {
        val pre = prefilledPayload?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        destination = pre
        advance(pre)
        onPrefilledConsumed()
    }

    // Confirm entry prefetches the melt quote (iOS shows fee/total skeleton).
    LaunchedEffect(step, locked, confirmAmount, activeMintUrl, quoteRetryNonce) {
        if (step != SendStep.Confirm) return@LaunchedEffect
        val rail = locked as? LockedRail.Melt ?: return@LaunchedEffect
        meltQuote = null
        quoteError = null
        runCatching {
            walletManager.createMeltQuote(
                request = rail.raw,
                // Invoices/offers carry their own amount; address rails pass the entry.
                amountSats = if (rail.knownAmount != null) null else confirmAmount,
                preferredMintURL = activeMintUrl,
            )
        }.onSuccess { meltQuote = it }
            .onFailure { quoteError = it.message ?: "Couldn't fetch a quote." }
    }

    fun goBack() {
        when {
            status != null -> Unit
            step == SendStep.Confirm && cameFromAmount -> {
                step = SendStep.Amount
                meltQuote = null
                quoteError = null
                confirmError = null
            }
            step != SendStep.Input -> {
                suppressedValue = destination.trim()
                reset()
            }
            else -> onClose()
        }
    }

    fun pay() {
        val rail = locked ?: return
        confirmError = null
        status = SendStatus.Sending
        scope.launch {
            try {
                when (rail) {
                    is LockedRail.Melt -> {
                        val quote = meltQuote ?: error("No quote.")
                        val result = walletManager.meltTokens(quote.id, activeMintUrl)
                        status = SendStatus.Sent(result)
                    }
                    is LockedRail.Creq -> {
                        when (val route = cashuRoute) {
                            is CashuPaymentRequestRoute.PayWithEcash -> {
                                walletManager.payCashuPaymentRequest(rail.raw, route.amountSats, route.mint.url)
                            }
                            is CashuPaymentRequestRoute.PayBolt11Fallback -> {
                                val quote = walletManager.createMeltQuote(
                                    request = route.lightningRequest,
                                    amountSats = null,
                                    preferredMintURL = activeMintUrl,
                                )
                                val result = walletManager.meltTokens(quote.id, activeMintUrl)
                                status = SendStatus.Sent(result)
                                return@launch
                            }
                            is CashuPaymentRequestRoute.AddMintToPay -> {
                                error("Add a compatible mint before paying this Cashu Request.")
                            }
                            is CashuPaymentRequestRoute.NeedsExternalTopUp -> {
                                error("Top up the target mint before paying this Cashu Request.")
                            }
                            CashuPaymentRequestRoute.MissingAmount -> {
                                error("Enter an amount before paying this Cashu Request.")
                            }
                            is CashuPaymentRequestRoute.UnsupportedUnit -> {
                                error("Only sat Cashu Requests are supported on Android right now.")
                            }
                            null -> {
                                walletManager.payCashuPaymentRequest(rail.raw, confirmAmount, activeMintUrl)
                            }
                        }
                        status = SendStatus.Sent(null)
                    }
                }
            } catch (t: Throwable) {
                status = SendStatus.Failed(t.message ?: "Payment failed.")
            }
        }
    }

    BackHandler(enabled = true) {
        when (val current = status) {
            SendStatus.Sending -> Unit
            is SendStatus.Sent -> onClose()
            is SendStatus.Failed -> status = null
            null -> goBack()
        }
    }

    // Status terminal replaces the whole body (iOS PaymentStatusView slot).
    when (val current = status) {
        SendStatus.Sending -> {
            PaymentStatusScreen(phase = PaymentStatusPhase.Processing, title = "Sending payment…")
            return
        }
        is SendStatus.Sent -> {
            PaymentStatusScreen(
                phase = PaymentStatusPhase.Success,
                title = "Payment sent",
                detail = current.result?.let { r ->
                    buildString {
                        append("${r.amount} sat")
                        if (r.feePaid > 0L) append(" · fee ${r.feePaid} sat")
                    }
                },
                onDone = onClose,
            )
            return
        }
        is SendStatus.Failed -> {
            PaymentStatusScreen(
                phase = PaymentStatusPhase.Failure,
                title = "Payment failed",
                detail = current.reason,
                doneLabel = "Try again",
                onDone = { status = null },
            )
            return
        }
        null -> Unit
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Send", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = ::goBack) {
                        Icon(
                            imageVector = if (step == SendStep.Input) {
                                Icons.Outlined.Close
                            } else {
                                Icons.AutoMirrored.Outlined.ArrowBack
                            },
                            contentDescription = if (step == SendStep.Input) "Close" else "Back",
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        TwoFaceScreen(
            targetState = step,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            forward = { initial, target -> target.ordinal >= initial.ordinal },
            label = "unified-send-step",
        ) { current ->
            when (current) {
                SendStep.Input -> InputFace(
                    hasMints = walletState.mints.isNotEmpty(),
                    hasBalance = walletState.hasAnyBalance,
                    destination = destination,
                    onDestinationChange = {
                        destination = it
                        inputHint = null
                    },
                    onPaste = {
                        val clip = clipboard.getText()?.text?.trim().orEmpty()
                        if (clip.isNotEmpty()) {
                            destination = clip
                            advance(clip)
                        }
                    },
                    onClear = {
                        destination = ""
                        inputHint = null
                    },
                    clipboardHasText = clipboard.hasText(),
                    inputHint = inputHint,
                    hasNfc = hasNfc,
                    onScan = onScan,
                    onSendEcash = onSendEcash,
                    onContactless = onContactless,
                    onOpenMints = onOpenMints,
                    onReceive = onReceive,
                )

                SendStep.Amount -> AmountFace(
                    destination = locked?.raw ?: destination,
                    amount = amount,
                    onAmountChange = { amount = it },
                    mint = activeMint,
                    balanceText = activeMint?.let {
                        formatter.formatWalletSats(it.balance, settings.useBitcoinSymbol)
                    },
                    onPickMint = { mintPickerOpen = true },
                    onUseMax = {
                        activeMint?.balance?.takeIf { it > 0 }?.let { amount = it.toString() }
                    },
                    onContinue = {
                        cameFromAmount = true
                        step = SendStep.Confirm
                    },
                )

                SendStep.Confirm -> ConfirmFace(
                    rail = locked,
                    cashuRoute = cashuRoute,
                    amountSats = confirmAmount,
                    mint = activeMint,
                    onPickMint = { mintPickerOpen = true },
                    onAddMintToPay = { addMintToPayOpen = true },
                    onCreateTopUp = { mintUrl, requestedAmount ->
                        topUpError = null
                        topUpLoading = true
                        scope.launch {
                            runCatching {
                                walletManager.createMintQuoteForMint(
                                    mintUrl = mintUrl,
                                    amount = requestedAmount,
                                    method = PaymentMethodKind.Bolt11,
                                    unit = "sat",
                                )
                            }.onSuccess {
                                topUpQuote = it
                            }.onFailure {
                                topUpError = it.message ?: "Could not create a top-up request."
                            }
                            topUpLoading = false
                        }
                    },
                    quote = meltQuote,
                    quoteError = quoteError,
                    onRetryQuote = {
                        meltQuote = null
                        quoteError = null
                        quoteRetryNonce++
                    },
                    confirmError = confirmError,
                    mintBalance = activeMint?.balance ?: 0L,
                    formatter = formatter,
                    useBitcoinSymbol = settings.useBitcoinSymbol,
                    topUpLoading = topUpLoading,
                    topUpError = topUpError,
                    onPay = ::pay,
                )
            }
        }
    }

    if (mintPickerOpen) {
        MintPickerSheet(
            mints = walletState.mints,
            activeMintUrl = activeMintUrl,
            onSelect = {
                selectedMintUrl = it.url
                mintPickerOpen = false
            },
            onDismiss = { mintPickerOpen = false },
        )
    }

    if (addMintToPayOpen) {
        val route = cashuRoute as? CashuPaymentRequestRoute.AddMintToPay
        AddMintToPaySheet(
            walletManager = walletManager,
            mintUrls = route?.mintUrls.orEmpty(),
            onSelect = { mintUrl ->
                scope.launch {
                    runCatching {
                        walletManager.addMint(mintUrl)
                    }.onSuccess {
                        selectedMintUrl = mintUrl.trim().trimEnd('/')
                        addMintToPayOpen = false
                    }.onFailure {
                        confirmError = it.message ?: "Could not add mint."
                    }
                }
            },
            onDismiss = { addMintToPayOpen = false },
        )
    }

    topUpQuote?.let { quote ->
        TopUpQuoteSheet(
            quote = quote,
            formatter = formatter,
            useBitcoinSymbol = settings.useBitcoinSymbol,
            onDismiss = { topUpQuote = null },
        )
    }
}

private data class AddMintPreviewState(
    val mintInfo: MintInfo? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMintToPaySheet(
    walletManager: WalletManager,
    mintUrls: List<String>,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val previewStates = remember { mutableStateMapOf<String, AddMintPreviewState>() }

    LaunchedEffect(mintUrls) {
        mintUrls.forEach { url ->
            if (url !in previewStates) {
                previewStates[url] = AddMintPreviewState(isLoading = true)
                val result = runCatching { walletManager.previewMint(url) }
                previewStates[url] = result.fold(
                    onSuccess = { AddMintPreviewState(mintInfo = it) },
                    onFailure = { AddMintPreviewState(error = it.message ?: "Preview unavailable") },
                )
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CashuTheme.spacing.comfortable)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        ) {
            Text(
                text = "Add mint to pay",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = "This Cashu Request accepts one of these mints.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            mintUrls.forEach { url ->
                val state = previewStates[url] ?: AddMintPreviewState()
                val fallback = remember(url) { MintInfo(url = url, name = url.removePrefix("https://").trimEnd('/')) }
                val mint = state.mintInfo ?: fallback
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(url) }
                        .padding(vertical = CashuTheme.spacing.snug),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
                ) {
                    MintAvatar(mint = mint)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mint.name,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = when {
                                state.isLoading -> "Fetching mint info…"
                                state.error != null -> "${state.error} · ${fallback.name}"
                                else -> fallback.name
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopUpQuoteSheet(
    quote: MintQuoteInfo,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CashuTheme.spacing.comfortable)
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        ) {
            Text(
                text = "Top up mint",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            quote.amount?.let { amount ->
                AmountText(
                    text = formatter.formatWalletSats(amount, useBitcoinSymbol),
                    style = MaterialTheme.typography.headlineSmall.withMonoDigits(),
                )
            }
            QrCard(content = quote.request, shareSubject = "Top-up request", staticOnly = true)
            Text(
                text = "Pay this invoice, then try the Cashu Request again after the mint settles.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            PrimaryButton(text = "Done", onClick = onDismiss)
        }
    }
}

@Composable
private fun InputFace(
    hasMints: Boolean,
    hasBalance: Boolean,
    destination: String,
    onDestinationChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    clipboardHasText: Boolean,
    inputHint: String?,
    hasNfc: Boolean,
    onScan: () -> Unit,
    onSendEcash: () -> Unit,
    onContactless: () -> Unit,
    onOpenMints: () -> Unit,
    onReceive: () -> Unit,
) {
    when {
        !hasMints -> {
            NoMintsFace(onOpenMints = onOpenMints)
            return
        }
        !hasBalance -> {
            EmptyState(
                icon = Icons.Outlined.Money,
                title = "Nothing to send yet",
                supporting = "Receive some ecash before you can send.",
                actionLabel = "Receive",
                onAction = onReceive,
            )
            return
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.comfortable)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(CashuTheme.spacing.default))
        CashuTextField(
            value = destination,
            onValueChange = onDestinationChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = "Address, invoice, or Cashu Request",
            singleLine = false,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            trailingIcon = when {
                destination.isNotBlank() -> {
                    {
                        IconButton(onClick = onClear) {
                            Icon(Icons.Outlined.Cancel, contentDescription = "Clear")
                        }
                    }
                }
                clipboardHasText -> {
                    {
                        GhostButton(text = "Paste", onClick = onPaste)
                    }
                }
                else -> null
            },
        )
        if (inputHint != null) {
            Spacer(Modifier.height(CashuTheme.spacing.default))
            InlineNotice(text = inputHint, severity = NoticeSeverity.Warning)
        }
        Spacer(Modifier.height(CashuTheme.spacing.page + CashuTheme.spacing.micro))
        // Ways to send: Scan · Ecash · Tap (NFC-gated), round 60dp buttons.
        Row(
            horizontalArrangement = Arrangement.spacedBy(MethodRowSpacing),
            verticalAlignment = Alignment.Top,
        ) {
            SendMethodButton(
                icon = Icons.Outlined.QrCodeScanner,
                label = "Scan",
                onClick = onScan,
            )
            SendMethodButton(
                icon = Icons.Outlined.Money,
                label = "Ecash",
                onClick = onSendEcash,
            )
            if (hasNfc) {
                SendMethodButton(
                    icon = Icons.Outlined.Nfc,
                    label = "Tap",
                    onClick = onContactless,
                )
            }
        }
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun SendMethodButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(MethodButtonSize),
        ) {
            Icon(imageVector = icon, contentDescription = label)
        }
        Spacer(Modifier.height(CashuTheme.spacing.tight))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun NoMintsFace(onOpenMints: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Connect a mint first",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(CashuTheme.spacing.snug))
        Text(
            text = "Mints issue the ecash you send and receive. Add one to get started.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(CashuTheme.spacing.section))
        GhostButton(text = "Add custom mint URL", onClick = onOpenMints)
    }
}

/** "TO" pill: caption label + middle-truncated recipient. */
@Composable
private fun ToPill(destination: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CashuTheme.spacing.comfortable),
    ) {
        Text(
            text = "TO",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = destination,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
    }
}

/** Mint row: avatar + name + balance, tap to change; optional Use Max. */
@Composable
private fun MintAmountRow(
    mint: MintInfo,
    balanceText: String?,
    onPickMint: () -> Unit,
    onUseMax: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPickMint)
            .padding(
                horizontal = CashuTheme.spacing.comfortable,
                vertical = CashuTheme.spacing.snug,
            ),
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
        if (onUseMax != null) {
            GhostButton(text = "Use Max", onClick = onUseMax)
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
private fun AmountFace(
    destination: String,
    amount: String,
    onAmountChange: (String) -> Unit,
    mint: MintInfo?,
    balanceText: String?,
    onPickMint: () -> Unit,
    onUseMax: () -> Unit,
    onContinue: () -> Unit,
) {
    val amountValue = amount.toLongOrNull() ?: 0L
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.comfortable)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ToPill(destination = destination)
        Spacer(Modifier.height(CashuTheme.spacing.section))
        AmountText(
            text = amount.ifEmpty { "0" },
            style = MaterialTheme.typography.displayMedium.withMonoDigits(),
        )
        Text(
            text = "sat",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(CashuTheme.spacing.default))
        if (mint != null) {
            MintAmountRow(
                mint = mint,
                balanceText = balanceText,
                onPickMint = onPickMint,
                onUseMax = onUseMax,
            )
        }
        Spacer(Modifier.height(CashuTheme.spacing.default))
        NumberPad(amount = amount, onAmountChange = onAmountChange)
        Spacer(Modifier.height(CashuTheme.spacing.micro))
        PrimaryButton(
            text = "Continue",
            onClick = onContinue,
            enabled = amountValue > 0,
        )
        Spacer(Modifier.navigationBarsPadding())
    }
}

@Composable
private fun ConfirmFace(
    rail: LockedRail?,
    cashuRoute: CashuPaymentRequestRoute?,
    amountSats: Long,
    mint: MintInfo?,
    onPickMint: () -> Unit,
    onAddMintToPay: () -> Unit,
    onCreateTopUp: (mintUrl: String, amountSats: Long) -> Unit,
    quote: MeltQuoteInfo?,
    quoteError: String?,
    onRetryQuote: () -> Unit,
    confirmError: String?,
    mintBalance: Long,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
    topUpLoading: Boolean,
    topUpError: String?,
    onPay: () -> Unit,
) {
    val isMelt = rail is LockedRail.Melt
    val isOnchain = (rail as? LockedRail.Melt)?.decoded is PaymentRequestDecodeResult.Onchain
    val cashuSummary = (rail as? LockedRail.Creq)?.decoded?.summary
    val cashuAmountLabel = cashuSummary?.let(PaymentRequestDecoder::amountLabel)
    val total = quote?.totalAmount ?: amountSats
    val insufficient = isMelt && quote != null && total > mintBalance
    val canPayCashuRequest = cashuRoute is CashuPaymentRequestRoute.PayWithEcash ||
        cashuRoute is CashuPaymentRequestRoute.PayBolt11Fallback
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.comfortable)
            .imePadding()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top accessory: paying mint + recipient (mint-at-top rule).
        if (mint != null) {
            MintAmountRow(
                mint = mint,
                balanceText = formatter.formatWalletSats(mintBalance, useBitcoinSymbol),
                onPickMint = onPickMint,
            )
        }
        rail?.let { ToPill(destination = it.raw) }
        Spacer(Modifier.height(CashuTheme.spacing.section))
        AmountText(
            text = cashuAmountLabel ?: formatter.formatWalletSats(amountSats, useBitcoinSymbol),
            style = MaterialTheme.typography.displayMedium.withMonoDigits(),
        )
        Spacer(Modifier.height(CashuTheme.spacing.section))
        Column(modifier = Modifier.fillMaxWidth()) {
            if (isMelt) {
                if (isOnchain && rail != null) {
                    InspectorRow(
                        label = "To",
                        value = PaymentRequestDecoder.shortRepresentation(
                            "",
                            (rail as LockedRail.Melt).decoded,
                        ),
                        valueMonospaced = true,
                    )
                    CanvasDivider(leadingInset = 16)
                }
                InspectorRow(
                    label = "Network fee",
                    value = quote?.let { "${it.feeReserve} sat" } ?: "…",
                    valueMonospaced = true,
                )
                CanvasDivider(leadingInset = 16)
                InspectorRow(
                    label = "Total",
                    value = quote?.let { "${it.totalAmount} sat" } ?: "…",
                    valueMonospaced = true,
                )
            } else {
                InspectorRow(
                    label = "Amount",
                    value = cashuAmountLabel ?: "$amountSats sat",
                    valueMonospaced = true,
                )
                if (mint != null) {
                    CanvasDivider(leadingInset = 16)
                    InspectorRow(
                        label = "Mint",
                        value = mint.name,
                        leadingIcon = Icons.Outlined.AccountBalance,
                    )
                }
                when (val route = cashuRoute) {
                    is CashuPaymentRequestRoute.PayWithEcash -> {
                        CanvasDivider(leadingInset = 16)
                        InspectorRow(
                            label = "Route",
                            value = "Pay from ${route.mint.name}",
                        )
                    }
                    is CashuPaymentRequestRoute.PayBolt11Fallback -> {
                        CanvasDivider(leadingInset = 16)
                        InspectorRow(
                            label = "Route",
                            value = "Use BOLT11 fallback",
                        )
                    }
                    is CashuPaymentRequestRoute.AddMintToPay -> {
                        CanvasDivider(leadingInset = 16)
                        InspectorRow(
                            label = "Route",
                            value = "Add requested mint",
                        )
                    }
                    is CashuPaymentRequestRoute.NeedsExternalTopUp -> {
                        CanvasDivider(leadingInset = 16)
                        InspectorRow(
                            label = "Route",
                            value = "Top up target mint",
                        )
                    }
                    CashuPaymentRequestRoute.MissingAmount,
                    is CashuPaymentRequestRoute.UnsupportedUnit,
                    null -> Unit
                }
            }
        }
        if (insufficient) {
            Spacer(Modifier.height(CashuTheme.spacing.default))
            InlineNotice(
                text = "This mint doesn't hold enough to cover the total.",
                severity = NoticeSeverity.Warning,
            )
        }
        if (quoteError != null) {
            Spacer(Modifier.height(CashuTheme.spacing.default))
            InlineNotice(text = quoteError)
            GhostButton(text = "Try again", onClick = onRetryQuote)
        }
        when (val route = cashuRoute) {
            is CashuPaymentRequestRoute.UnsupportedUnit -> {
                Spacer(Modifier.height(CashuTheme.spacing.default))
                InlineNotice(
                    text = "Only sat Cashu Requests are supported on Android right now.",
                    severity = NoticeSeverity.Warning,
                )
            }
            CashuPaymentRequestRoute.MissingAmount -> {
                Spacer(Modifier.height(CashuTheme.spacing.default))
                InlineNotice(
                    text = "This Cashu Request does not include an amount. Enter an amount before paying.",
                    severity = NoticeSeverity.Warning,
                )
            }
            is CashuPaymentRequestRoute.AddMintToPay -> {
                Spacer(Modifier.height(CashuTheme.spacing.default))
                InlineNotice(
                    text = "This request asks for a mint you have not added yet.",
                    severity = NoticeSeverity.Warning,
                )
                GhostButton(text = "Add mint to pay", onClick = onAddMintToPay)
            }
            is CashuPaymentRequestRoute.NeedsExternalTopUp -> {
                Spacer(Modifier.height(CashuTheme.spacing.default))
                InlineNotice(
                    text = "The compatible mint does not hold enough ecash for this request.",
                    severity = NoticeSeverity.Warning,
                )
                route.mintUrl?.let { mintUrl ->
                    GhostButton(
                        text = if (topUpLoading) "Creating top-up…" else "Create top-up QR",
                        onClick = { onCreateTopUp(mintUrl, route.amountSats) },
                        enabled = !topUpLoading,
                    )
                }
                GhostButton(text = "Choose another mint", onClick = onPickMint)
            }
            is CashuPaymentRequestRoute.PayBolt11Fallback -> {
                Spacer(Modifier.height(CashuTheme.spacing.default))
                InlineNotice(
                    text = "The requested Cashu mint is unavailable. Android can pay this request through its BOLT11 fallback.",
                    severity = NoticeSeverity.Info,
                )
            }
            is CashuPaymentRequestRoute.PayWithEcash,
            null -> Unit
        }
        if (topUpError != null) {
            Spacer(Modifier.height(CashuTheme.spacing.default))
            InlineNotice(text = topUpError)
        }
        if (confirmError != null) {
            Spacer(Modifier.height(CashuTheme.spacing.default))
            InlineNotice(text = confirmError)
        }
        Spacer(Modifier.height(CashuTheme.spacing.default))
        PrimaryButton(
            text = "Pay ${formatter.formatWalletSats(amountSats, useBitcoinSymbol)}",
            onClick = onPay,
            enabled = if (isMelt) {
                quote != null && !insufficient && quoteError == null
            } else {
                canPayCashuRequest && quoteError == null
            },
            loading = isMelt && quote == null && quoteError == null,
        )
        Spacer(Modifier.navigationBarsPadding())
    }
}
