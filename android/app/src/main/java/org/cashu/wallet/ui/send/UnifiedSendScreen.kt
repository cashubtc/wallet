package org.cashu.wallet.ui.send

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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import org.cashu.wallet.Core.PaymentRequestDecodeResult
import org.cashu.wallet.Core.PaymentRequestDecoder
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.TokenParser
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Core.compatibleMintsForCashuPaymentRequest
import org.cashu.wallet.Models.MeltPaymentResult
import org.cashu.wallet.Models.MeltQuoteInfo
import org.cashu.wallet.Models.MintInfo
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
    var quoteError by remember { mutableStateOf<String?>(null) }
    var confirmError by remember { mutableStateOf<String?>(null) }

    val activeMintUrl = selectedMintUrl ?: walletState.activeMint?.url
    val activeMint = walletState.mints.firstOrNull { it.url == activeMintUrl } ?: walletState.activeMint
    val enteredAmount = amount.toLongOrNull() ?: 0L
    val confirmAmount = locked?.let { rail ->
        when (rail) {
            is LockedRail.Melt -> rail.knownAmount ?: enteredAmount
            is LockedRail.Creq -> rail.knownAmount ?: enteredAmount
        }
    } ?: 0L

    fun reset(toInput: Boolean = true) {
        locked = null
        amount = ""
        meltQuote = null
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
            val summary = decoded.summary
            val fallback = PaymentRequestDecoder.decode(trimmed)
            if (fallback is PaymentRequestDecodeResult.Unrecognized) {
                val required = summary.mints.joinToString()
                inputHint = if (required.isEmpty()) {
                    "Add a mint before paying this Cashu request."
                } else {
                    "This Cashu request requires a mint you don't have: $required"
                }
                return
            }
            decoded = fallback
            if (fallback is PaymentRequestDecodeResult.Bolt11 || fallback is PaymentRequestDecodeResult.Bolt12) {
                request = PaymentRequestDecoder.encodedLightningRequest(trimmed) ?: trimmed
            }
        }
        when (decoded) {
            is PaymentRequestDecodeResult.Bolt11 -> {
                val known = decoded.amountSats
                if (known == null || known <= 0L) {
                    inputHint = "This invoice doesn't specify an amount."
                } else {
                    locked = LockedRail.Melt(request, decoded, known)
                    cameFromAmount = false
                    step = SendStep.Confirm
                }
            }
            is PaymentRequestDecodeResult.Bolt12 -> {
                val known = decoded.amountSats
                if (known == null || known <= 0L) {
                    inputHint = "This offer doesn't specify an amount."
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
                val known = decoded.summary.amount?.takeIf { it > 0 && decoded.summary.isSatUnit }
                locked = LockedRail.Creq(request, decoded, known)
                if (known != null) {
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
                        walletManager.payCashuPaymentRequest(rail.raw, confirmAmount, activeMintUrl)
                        status = SendStatus.Sent(null)
                    }
                }
            } catch (t: Throwable) {
                status = SendStatus.Failed(t.message ?: "Payment failed.")
            }
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
                    amountSats = confirmAmount,
                    mint = activeMint,
                    onPickMint = { mintPickerOpen = true },
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
            .padding(horizontal = CashuTheme.spacing.comfortable),
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
        Spacer(Modifier.weight(1f))
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
    amountSats: Long,
    mint: MintInfo?,
    onPickMint: () -> Unit,
    quote: MeltQuoteInfo?,
    quoteError: String?,
    onRetryQuote: () -> Unit,
    confirmError: String?,
    mintBalance: Long,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
    onPay: () -> Unit,
) {
    val isMelt = rail is LockedRail.Melt
    val isOnchain = (rail as? LockedRail.Melt)?.decoded is PaymentRequestDecodeResult.Onchain
    val total = quote?.totalAmount ?: amountSats
    val insufficient = isMelt && quote != null && total > mintBalance
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.comfortable),
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
            text = formatter.formatWalletSats(amountSats, useBitcoinSymbol),
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
                InspectorRow(
                    label = "Network fee",
                    value = quote?.let { "${it.feeReserve} sat" } ?: "…",
                    valueMonospaced = true,
                )
                CanvasDivider(leadingInset = 16.dp)
                InspectorRow(
                    label = "Total",
                    value = quote?.let { "${it.totalAmount} sat" } ?: "…",
                    valueMonospaced = true,
                )
            } else {
                InspectorRow(
                    label = "Amount",
                    value = "$amountSats sat",
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
        if (confirmError != null) {
            Spacer(Modifier.height(CashuTheme.spacing.default))
            InlineNotice(text = confirmError)
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton(
            text = "Pay ${formatter.formatWalletSats(amountSats, useBitcoinSymbol)}",
            onClick = onPay,
            enabled = (!isMelt || (quote != null && !insufficient)) && quoteError == null,
            loading = isMelt && quote == null && quoteError == null,
        )
        Spacer(Modifier.navigationBarsPadding())
    }
}
