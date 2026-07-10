package org.cashu.wallet.ui.send

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import org.cashu.wallet.Core.Wallet.WalletMessage
import org.cashu.wallet.Core.Wallet.userFacingWalletMessage
import org.cashu.wallet.Core.Wallet.walletMessage
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Core.routeForCashuPaymentRequest
import org.cashu.wallet.Models.MeltPaymentResult
import org.cashu.wallet.Models.MeltQuoteInfo
import org.cashu.wallet.Models.MeltSettlement
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.ui.components.AmountEntryHero
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.CashuTextField
import org.cashu.wallet.ui.components.EmptyState
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.InspectorRow
import org.cashu.wallet.ui.components.MintPickerSheet
import org.cashu.wallet.ui.components.MintSelectorRow
import org.cashu.wallet.ui.components.NoticeSeverity
import org.cashu.wallet.ui.components.NumberPad
import org.cashu.wallet.ui.components.PaymentStatusPhase
import org.cashu.wallet.ui.components.PaymentStatusScreen
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.QrCard
import org.cashu.wallet.ui.components.SheetHeader
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
    data class Failed(val message: WalletMessage) : SendStatus
}

/**
 * The Send surface (iOS UnifiedSendView): one destination field that infers the
 * rail, a Scan · Ecash · Tap ways-to-send row, then amount → confirm → status.
 * Home's Send button lands here directly — there is no send chooser.
 * Hosted in the shell's flow bottom sheet at full height (iOS `.large`).
 */
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
    onDismissLockChanged: (Boolean) -> Unit = {},
) {
    val walletState by walletManager.state.collectAsState()
    val settings by settingsManager.state.collectAsState()
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
    var meltQuote by remember { mutableStateOf<MeltQuoteInfo?>(null) }
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
        else -> walletState.mints.firstOrNull { it.url == (meltQuote?.mintUrl ?: activeMintUrl) }
            ?: walletState.activeMint
    }

    fun reset(toInput: Boolean = true) {
        locked = null
        amount = ""
        meltQuote = null
        topUpQuote = null
        topUpLoading = false
        topUpError = null
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
        when (val resolution = resolveSendDestination(trimmed, walletState.mints)) {
            is SendDestinationResolution.Hint -> inputHint = resolution.message
            is SendDestinationResolution.Melt -> {
                locked = LockedRail.Melt(resolution.request, resolution.decoded, resolution.knownAmount)
                if (resolution.requiresAmountEntry) {
                    step = SendStep.Amount
                } else {
                    cameFromAmount = false
                    step = SendStep.Confirm
                }
            }
            is SendDestinationResolution.CashuRequest -> {
                locked = LockedRail.Creq(resolution.request, resolution.decoded, resolution.knownAmount)
                if (resolution.requiresAmountEntry) {
                    step = SendStep.Amount
                } else {
                    cameFromAmount = false
                    step = SendStep.Confirm
                }
            }
            is SendDestinationResolution.EcashToken -> onOpenReceiveToken(resolution.token)
            SendDestinationResolution.Unrecognized -> {
                inputHint =
                    "Unrecognized — try a Lightning address, invoice, Bitcoin address, or Cashu Request"
            }
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
                        val result = walletManager.meltTokens(quote.id, quote.mintUrl)
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
                                val result = walletManager.meltTokens(quote.id, quote.mintUrl)
                                status = SendStatus.Sent(result)
                                return@launch
                            }
                            is CashuPaymentRequestRoute.AddMintToPay -> {
                                val mintUrl = route.mintUrls.firstOrNull()
                                    ?: error("No compatible mint was supplied.")
                                walletManager.addMintAndPayCashuPaymentRequest(
                                    encoded = rail.raw,
                                    customAmountSats = route.amountSats,
                                    mintUrl = mintUrl,
                                )
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
                status = SendStatus.Failed(t.walletMessage)
            }
        }
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
    LaunchedEffect(step, locked, confirmAmount, activeMintUrl) {
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
            .onFailure { quoteError = it.userFacingWalletMessage }
    }

    // Block sheet dismissal while the melt is in flight — a stray swipe must
    // not tear down the coroutine mid-payment.
    LaunchedEffect(status) { onDismissLockChanged(status == SendStatus.Sending) }

    // System back mirrors the header chevron: unwind Confirm → Amount → Input;
    // swallow back entirely while sending. From Input the sheet handles it.
    BackHandler(enabled = status == SendStatus.Sending || (status == null && step != SendStep.Input)) {
        if (status == null) goBack()
    }

    Column(modifier = Modifier.fillMaxHeight()) {
        // Status terminal replaces the whole body (iOS PaymentStatusView slot).
        when (val current = status) {
            SendStatus.Sending -> Box(Modifier.weight(1f).fillMaxWidth()) {
                PaymentStatusScreen(phase = PaymentStatusPhase.Processing, title = "Sending payment…")
            }
            is SendStatus.Sent -> Box(Modifier.weight(1f).fillMaxWidth()) {
                // Amount/Fee/Mint metadata rows (iOS PaymentStatusView success
                // rows). Melt carries its own result; creq falls back to the
                // confirmed amount and active mint.
                val sentAmount = current.result?.amount ?: confirmAmount
                val sentFee = current.result?.feePaid ?: 0L
                val sentMint = current.result?.mintUrl ?: activeMintUrl
                PaymentStatusScreen(
                    phase = PaymentStatusPhase.Success,
                    title = if (current.result?.settlement == MeltSettlement.Pending) {
                        "Payment processing"
                    } else {
                        "Payment sent"
                    },
                    onDone = onClose,
                    rows = {
                        if (sentAmount > 0L) {
                            InspectorRow(
                                label = "Amount",
                                value = formatter.formatWalletSats(sentAmount, settings.useBitcoinSymbol),
                                leadingIcon = Icons.Outlined.Payments,
                            )
                        }
                        if (sentFee > 0L) {
                            CanvasDivider(leadingInset = 16.dp)
                            InspectorRow(
                                label = "Fee",
                                value = formatter.formatWalletSats(sentFee, settings.useBitcoinSymbol),
                                leadingIcon = Icons.Outlined.Receipt,
                            )
                        }
                        if (sentMint != null) {
                            CanvasDivider(leadingInset = 16.dp)
                            InspectorRow(
                                label = "Mint",
                                value = sentMint,
                                leadingIcon = Icons.Outlined.AccountBalance,
                            )
                        }
                    },
                )
            }
            is SendStatus.Failed -> Box(Modifier.weight(1f).fillMaxWidth()) {
                PaymentStatusScreen(
                    phase = PaymentStatusPhase.Failure,
                    title = "Payment failed",
                    detail = current.message.text,
                    // A terminal outcome (already paid) can't be retried — offer
                    // Done; anything else returns to the confirm step.
                    doneLabel = if (current.message.isTerminal) "Done" else "Try again",
                    onDone = {
                        if (current.message.isTerminal) onClose() else status = null
                    },
                )
            }
            null -> {
                SheetHeader(
                    title = "Send",
                    navigationIcon = if (step == SendStep.Input) {
                        Icons.Outlined.Close
                    } else {
                        Icons.AutoMirrored.Outlined.ArrowBack
                    },
                    navigationContentDescription = if (step == SendStep.Input) "Close" else "Back",
                    onNavigationClick = ::goBack,
                )
                TwoFaceScreen(
                    targetState = step,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
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
                        useBitcoinSymbol = settings.useBitcoinSymbol,
                        formatter = formatter,
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
                        onCreateTopUp = { mintUrl, requestedAmount ->
                            topUpError = null
                            topUpLoading = true
                            scope.launch {
                                runCatching {
                                    createExternalTopUpQuote(
                                        mintUrl = mintUrl,
                                        requestedAmountSats = requestedAmount,
                                    ) { targetMintUrl, amount, method, unit ->
                                        walletManager.createMintQuoteForMint(
                                            mintUrl = targetMintUrl,
                                            amount = amount,
                                            method = method,
                                            unit = unit,
                                        )
                                    }
                                }.onSuccess { topUpQuote = it }
                                    .onFailure { topUpError = it.userFacingWalletMessage }
                                topUpLoading = false
                            }
                        },
                        quote = meltQuote,
                        quoteError = quoteError,
                        onRetryQuote = {
                            quoteError = null
                            // Re-trigger the prefetch by nudging state.
                            val current = selectedMintUrl
                            selectedMintUrl = null
                            selectedMintUrl = current
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

    topUpQuote?.let { quote ->
        TopUpQuoteSheet(
            quote = quote,
            formatter = formatter,
            useBitcoinSymbol = settings.useBitcoinSymbol,
            onDismiss = { topUpQuote = null },
        )
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
                icon = Icons.Outlined.Payments,
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
            .imePadding(),
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
            // Deliberate divergence from the iOS ClipboardPaymentChip: Android
            // surfaces paste as an M3 trailing affordance. The Paste ↔ Clear
            // swap cross-fades (no hard cut) as input state changes.
            trailingIcon = if (destination.isNotBlank() || clipboardHasText) {
                {
                    AnimatedContent(
                        targetState = destination.isNotBlank(),
                        transitionSpec = {
                            fadeIn(spring(stiffness = Spring.StiffnessMedium))
                                .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
                        },
                        label = "input-trailing",
                    ) { hasInput ->
                        if (hasInput) {
                            IconButton(onClick = onClear) {
                                Icon(Icons.Outlined.Cancel, contentDescription = "Clear")
                            }
                        } else {
                            GhostButton(text = "Paste", onClick = onPaste)
                        }
                    }
                }
            } else null,
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
                icon = Icons.Outlined.Payments,
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
        Spacer(Modifier.weight(1f))
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

@Composable
private fun AmountFace(
    destination: String,
    amount: String,
    onAmountChange: (String) -> Unit,
    mint: MintInfo?,
    balanceText: String?,
    onPickMint: () -> Unit,
    onUseMax: () -> Unit,
    useBitcoinSymbol: Boolean,
    formatter: AmountFormatter,
    onContinue: () -> Unit,
) {
    val amountValue = amount.toLongOrNull() ?: 0L
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.comfortable),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ToPill(destination = destination)
        Spacer(Modifier.height(CashuTheme.spacing.section))
        AmountEntryHero(
            entryRaw = amount,
            isSat = true,
            unit = "sat",
            decimals = 0,
            useBitcoinSymbol = useBitcoinSymbol,
            formatter = formatter,
        )
        Spacer(Modifier.height(CashuTheme.spacing.default))
        if (mint != null) {
            MintSelectorRow(
                mint = mint,
                balanceText = balanceText,
                onPickMint = onPickMint,
                onUseMax = onUseMax,
            )
        }
        Spacer(Modifier.weight(1f))
        NumberPad(amount = amount, onAmountChange = onAmountChange)
        Spacer(Modifier.height(CashuTheme.spacing.page))
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
    onCreateTopUp: (mintUrl: String, requestedAmountSats: Long) -> Unit,
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
    val cashuAmountLabel = (rail as? LockedRail.Creq)?.decoded?.summary?.let(PaymentRequestDecoder::amountLabel)
    val total = quote?.totalAmount ?: amountSats
    val insufficient = isMelt && quote != null && total > mintBalance
    val canPayCashuRequest = cashuRoute == null ||
        cashuRoute is CashuPaymentRequestRoute.PayWithEcash ||
        cashuRoute is CashuPaymentRequestRoute.PayBolt11Fallback ||
        cashuRoute is CashuPaymentRequestRoute.AddMintToPay
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.comfortable),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top accessory: paying mint + recipient (mint-at-top rule).
        if (mint != null) {
            MintSelectorRow(
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
                    CanvasDivider(leadingInset = 16.dp)
                }
                // Fee/total land as a skeleton fill-in while the melt quote is
                // in flight (iOS .redacted confirm rows) — no "…" flash.
                val quoteLoading = quote == null && quoteError == null
                InspectorRow(
                    label = "Network fee",
                    value = quote?.let { "${it.feeReserve} sat" }.orEmpty(),
                    valueMonospaced = true,
                    loading = quoteLoading,
                )
                CanvasDivider(leadingInset = 16.dp)
                InspectorRow(
                    label = "Total",
                    value = quote?.let { "${it.totalAmount} sat" }.orEmpty(),
                    valueMonospaced = true,
                    loading = quoteLoading,
                )
            } else {
                InspectorRow(
                    label = "Amount",
                    value = cashuAmountLabel ?: "$amountSats sat",
                    valueMonospaced = true,
                )
                if (mint != null) {
                    CanvasDivider(leadingInset = 16.dp)
                    InspectorRow(
                        label = "Mint",
                        value = mint.name,
                        leadingIcon = Icons.Outlined.AccountBalance,
                    )
                }
                when (val route = cashuRoute) {
                    is CashuPaymentRequestRoute.PayWithEcash -> {
                        CanvasDivider(leadingInset = 16.dp)
                        InspectorRow(label = "Route", value = "Pay from ${route.mint.name}")
                    }
                    is CashuPaymentRequestRoute.PayBolt11Fallback -> {
                        CanvasDivider(leadingInset = 16.dp)
                        InspectorRow(label = "Route", value = "Use Lightning fallback")
                    }
                    is CashuPaymentRequestRoute.AddMintToPay -> {
                        CanvasDivider(leadingInset = 16.dp)
                        InspectorRow(label = "Route", value = "Add requested mint")
                    }
                    is CashuPaymentRequestRoute.NeedsExternalTopUp -> {
                        CanvasDivider(leadingInset = 16.dp)
                        InspectorRow(label = "Route", value = "Top up target mint")
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
        when (cashuRoute) {
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
                    text = "This request asks for a mint you have not added yet. It will be added before payment.",
                    severity = NoticeSeverity.Info,
                )
            }
            is CashuPaymentRequestRoute.NeedsExternalTopUp -> {
                Spacer(Modifier.height(CashuTheme.spacing.default))
                InlineNotice(
                    text = "The compatible mint does not hold enough ecash for this request.",
                    severity = NoticeSeverity.Warning,
                )
                cashuRoute.mintUrl?.let { mintUrl ->
                    GhostButton(
                        text = if (topUpLoading) "Creating top-up..." else "Create top-up QR",
                        onClick = { onCreateTopUp(mintUrl, cashuRoute.amountSats) },
                        enabled = !topUpLoading,
                    )
                }
                GhostButton(text = "Choose another mint", onClick = onPickMint)
            }
            is CashuPaymentRequestRoute.PayBolt11Fallback -> {
                Spacer(Modifier.height(CashuTheme.spacing.default))
                InlineNotice(
                    text = "The requested Cashu mint is unavailable. Android can pay this request through its Lightning fallback.",
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
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            text = "Pay ${cashuAmountLabel ?: formatter.formatWalletSats(amountSats, useBitcoinSymbol)}",
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
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState()),
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
