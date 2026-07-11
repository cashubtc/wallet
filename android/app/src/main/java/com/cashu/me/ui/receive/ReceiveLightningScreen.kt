package com.cashu.me.ui.receive

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.Core.Protocols.CurrencyAmount
import com.cashu.me.Core.Protocols.CurrencyRegistry
import com.cashu.me.Core.SettingsManager
import com.cashu.me.Core.UnitAmountEntry
import com.cashu.me.Core.Wallet.userFacingWalletMessage
import com.cashu.me.Core.WalletManager
import com.cashu.me.Core.mintQuoteDisplayExpiry
import com.cashu.me.Core.quoteExpiryText
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Models.MintQuoteState
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.ui.components.AmountEntryHero
import com.cashu.me.ui.components.AmountText
import com.cashu.me.ui.components.CanvasDivider
import com.cashu.me.ui.components.IconSwap
import com.cashu.me.ui.components.InlineNotice
import com.cashu.me.ui.components.InspectorRow
import com.cashu.me.ui.components.MintAvatar
import com.cashu.me.ui.components.MintPickerSheet
import com.cashu.me.ui.components.NumberPadFooter
import com.cashu.me.ui.components.PaymentStatusPhase
import com.cashu.me.ui.components.PaymentStatusScreen
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.components.QrCard
import com.cashu.me.ui.components.SheetHeader
import com.cashu.me.ui.components.TwoFaceScreen
import com.cashu.me.ui.components.UnitPickerSheet
import com.cashu.me.ui.components.shareText
import com.cashu.me.ui.components.ToolbarIcon
import com.cashu.me.ui.theme.CapsuleShape
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.rememberReducedMotion
import com.cashu.me.ui.theme.withMonoDigits

private sealed interface ReceiveLnFace {
    data object Input : ReceiveLnFace
    data class Display(val quote: MintQuoteInfo) : ReceiveLnFace
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveLightningScreen(
    walletManager: WalletManager,
    settingsManager: SettingsManager,
    onClose: () -> Unit,
) {
    val walletState by walletManager.state.collectAsState()
    val settings by settingsManager.state.collectAsState()
    val formatter = remember { AmountFormatter() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var face: ReceiveLnFace by remember { mutableStateOf(ReceiveLnFace.Input) }
    var amount by remember { mutableStateOf("") }
    var method by remember { mutableStateOf(PaymentMethodKind.Bolt11) }
    var creating by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    // When a payment lands the whole screen crossfades to the shared full-screen
    // success terminal (iOS parity — no inline "Paid" row, no Done button).
    var successInfo by remember { mutableStateOf<ReceiveSuccessInfo?>(null) }
    var selectedReceiveUnit by remember { mutableStateOf<String?>(null) }
    var unitPickerOpen by remember { mutableStateOf(false) }
    var mintPickerOpen by remember { mutableStateOf(false) }

    val activeMint = walletState.activeMint
    val supportedMethods = activeMint?.supportedMintMethods?.ifEmpty { listOf(PaymentMethodKind.Bolt11) }
        ?: listOf(PaymentMethodKind.Bolt11)

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

    // System back unwinds Display → Input; from Input the sheet handles it.
    // Suppressed once the success terminal is showing (it auto-dismisses).
    BackHandler(enabled = face is ReceiveLnFace.Display && successInfo == null) {
        face = ReceiveLnFace.Input
    }

    // The paid terminal replaces the whole sheet body (header + faces), fading
    // in over the QR the way iOS swaps `body` to the success view.
    Crossfade(targetState = successInfo, label = "receive-ln-terminal") { terminal ->
      if (terminal != null) {
        ReceiveSuccessTerminal(info = terminal, onDone = onClose)
      } else {
        Column(modifier = Modifier.fillMaxHeight()) {
        SheetHeader(
            title = when (val current = face) {
                ReceiveLnFace.Input -> "Receive"
                is ReceiveLnFace.Display -> when (current.quote.paymentMethod) {
                    PaymentMethodKind.Bolt11 -> "Lightning Invoice"
                    PaymentMethodKind.Bolt12 -> "Reusable Invoice"
                    PaymentMethodKind.Onchain -> "Bitcoin Address"
                }
            },
            navigationIcon = when (face) {
                ReceiveLnFace.Input -> Icons.Outlined.Close
                is ReceiveLnFace.Display -> Icons.AutoMirrored.Outlined.ArrowBack
            },
            navigationContentDescription = when (face) {
                ReceiveLnFace.Input -> "Close"
                is ReceiveLnFace.Display -> "Back"
            },
            onNavigationClick = {
                when (face) {
                    ReceiveLnFace.Input -> onClose()
                    is ReceiveLnFace.Display -> face = ReceiveLnFace.Input
                }
            },
            actions = {
                val current = face
                if (current is ReceiveLnFace.Display) {
                    IconButton(onClick = {
                        context.shareText(current.quote.request, subject = "Payment request")
                    }) {
                        ToolbarIcon(Icons.Outlined.IosShare, contentDescription = "Share")
                    }
                } else if (current is ReceiveLnFace.Input) {
                    // Method picker rides the header (iOS parity): an icon
                    // opening a menu, shown only when >1 method exists.
                    if (supportedMethods.size > 1) {
                        var methodMenuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { methodMenuOpen = true }) {
                            // Animated glyph replacement on method switch
                            // (iOS .contentTransition(.symbolEffect(.replace))).
                            IconSwap(
                                icon = method.menuIcon,
                                contentDescription = "Payment method",
                                iconSize = CashuTheme.iconSizes.toolbar,
                            )
                        }
                        DropdownMenu(
                            expanded = methodMenuOpen,
                            onDismissRequest = { methodMenuOpen = false },
                            shape = MaterialTheme.shapes.large,
                        ) {
                            supportedMethods.forEach { kind ->
                                DropdownMenuItem(
                                    text = { Text(kind.displayName) },
                                    leadingIcon = { Icon(kind.menuIcon, contentDescription = null) },
                                    trailingIcon = if (kind == method) {
                                        { Icon(Icons.Filled.Check, contentDescription = "Selected") }
                                    } else null,
                                    onClick = {
                                        methodMenuOpen = false
                                        method = kind
                                        amount = ""
                                        errorText = null
                                    },
                                )
                            }
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
        )
        TwoFaceScreen(
            targetState = face,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
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
                    isSat = isSatUnit,
                    unit = effectiveUnit,
                    useBitcoinSymbol = settings.useBitcoinSymbol,
                    formatter = formatter,
                    decimals = currency.decimals,
                    errorText = errorText,
                    onCreate = {
                        val explicit = UnitAmountEntry.baseUnits(amount, currency.decimals)
                            .takeIf { it > 0 }
                        val needsAmount = method != PaymentMethodKind.Bolt12
                        if (needsAmount && explicit == null) {
                            errorText = "Enter an amount."
                            return@InputFace
                        }
                        if (activeMint == null) {
                            errorText = "Add a mint first."
                            return@InputFace
                        }
                        creating = true
                        scope.launch {
                            try {
                                val quote = walletManager.createMintQuote(
                                    amount = explicit,
                                    method = method,
                                    unit = effectiveUnit,
                                )
                                face = ReceiveLnFace.Display(quote)
                            } catch (t: Throwable) {
                                errorText = t.userFacingWalletMessage
                            } finally {
                                creating = false
                            }
                        }
                    },
                )

                is ReceiveLnFace.Display -> {
                    var liveQuote by remember(current.quote.id) { mutableStateOf(current.quote) }
                    LaunchedEffect(current.quote.id) {
                        walletManager.subscribeToMintQuote(current.quote.id)
                            .catch { /* swallow; we'll fall back to manual polling */ }
                            .collectLatest { liveQuote = it }
                    }
                    LaunchedEffect(current.quote.id) {
                        while (true) {
                            delay(15_000)
                            runCatching { walletManager.pollMintQuote(current.quote.id) }
                                .getOrNull()
                                ?.let { liveQuote = it }
                        }
                    }
                    val amountLabel = liveQuote.amount?.let {
                        if (liveQuote.unit.equals("sat", ignoreCase = true)) {
                            formatter.formatWalletSats(it, settings.useBitcoinSymbol)
                        } else {
                            CurrencyAmount(
                                it,
                                CurrencyRegistry.currencyForMintUnit(liveQuote.unit),
                            ).formatted()
                        }
                    }
                    LaunchedEffect(liveQuote.state) {
                        if (liveQuote.state == MintQuoteState.Paid ||
                            liveQuote.state == MintQuoteState.Issued
                        ) {
                            // Finish the UX immediately and mint on the wallet's
                            // app-lifetime scope so the dismiss never cancels it
                            // (iOS: unstructured task that outlives the sheet).
                            walletManager.launch { runCatching { walletManager.mintTokens(liveQuote.id) } }
                            successInfo = ReceiveSuccessInfo(
                                amountLabel = amountLabel,
                                mintName = activeMint?.name,
                            )
                        }
                    }
                    DisplayFace(
                        quote = liveQuote,
                        amountLabel = amountLabel,
                        mintName = activeMint?.name,
                        onCopy = { clipboard.setText(AnnotatedString(liveQuote.request)) },
                    )
                }
            }
        }
      }
    }
    }

    if (mintPickerOpen) {
        MintPickerSheet(
            mints = walletState.mints,
            activeMintUrl = activeMint?.url,
            onSelect = { mint ->
                mint?.let { scope.launch { walletManager.setActiveMint(it) } }
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
    isSat: Boolean,
    unit: String,
    useBitcoinSymbol: Boolean,
    formatter: AmountFormatter,
    decimals: Int,
    errorText: String?,
    onCreate: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.comfortable)
            .imePadding(),
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
        Spacer(Modifier.weight(1f))
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
        AmountEntryHero(
            entryRaw = amount,
            isSat = isSat,
            unit = unit,
            decimals = decimals,
            useBitcoinSymbol = useBitcoinSymbol,
            formatter = formatter,
        )
        if (errorText != null) {
            Spacer(Modifier.height(CashuTheme.spacing.default))
            InlineNotice(text = errorText)
        }
        Spacer(Modifier.weight(1f))
        NumberPadFooter(
            amount = amount,
            onAmountChange = onAmountChange,
            decimals = decimals,
            buttonText = if (creating) "Creating…" else selectedMethod.createActionTitle,
            onButtonClick = onCreate,
            buttonEnabled = !creating,
            buttonLoading = creating,
        )
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

private val PaymentMethodKind.menuIcon
    get() = when (this) {
        PaymentMethodKind.Bolt11 -> Icons.Outlined.Bolt
        PaymentMethodKind.Bolt12 -> Icons.Outlined.Repeat
        PaymentMethodKind.Onchain -> Icons.Outlined.CurrencyBitcoin
    }

private val PaymentMethodKind.createActionTitle: String
    get() = when (this) {
        PaymentMethodKind.Bolt11 -> "Create Invoice"
        PaymentMethodKind.Bolt12 -> "Create Offer"
        PaymentMethodKind.Onchain -> "Get Address"
    }

private val PaymentMethodKind.copyActionTitle: String
    get() = when (this) {
        PaymentMethodKind.Bolt11 -> "Copy invoice"
        PaymentMethodKind.Bolt12 -> "Copy offer"
        PaymentMethodKind.Onchain -> "Copy address"
    }

@Composable
private fun DisplayFace(
    quote: MintQuoteInfo,
    amountLabel: String?,
    mintName: String?,
    onCopy: () -> Unit,
) {
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        // Scrolling content region; the copy CTA is pinned to the bottom (iOS
        // parity — the QR is the focal element, actions sit below the fold).
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.comfortable),
        ) {
            Spacer(Modifier.height(CashuTheme.spacing.comfortable))
            QrCard(content = quote.request, shareSubject = "Payment request", staticOnly = true)
            if (amountLabel != null) {
                // SemiBold headlineMedium (~28sp) — heavier than the old thin
                // headlineSmall, but still secondary to the QR (iOS 32pt intent).
                AmountText(
                    text = amountLabel,
                    style = MaterialTheme.typography.headlineMedium
                        .copy(fontWeight = FontWeight.SemiBold)
                        .withMonoDigits(),
                )
            }
            WaitingForPaymentRow()
            ExpiryCaption(expirySeconds = quote.expiryEpochSeconds)
            if (mintName != null) {
                InspectorRow(
                    label = "Mint",
                    value = mintName,
                    leadingIcon = Icons.Outlined.AccountBalance,
                )
            }
        }
        PrimaryButton(
            text = if (copied) "Copied" else quote.paymentMethod.copyActionTitle,
            onClick = {
                onCopy()
                copied = true
            },
            modifier = Modifier.padding(horizontal = CashuTheme.spacing.comfortable),
        )
        Spacer(Modifier.navigationBarsPadding())
    }
}

/** Pulsing clock + "Waiting for payment…" — the only pending status now that a
 *  paid quote crossfades to the full-screen success terminal. */
@Composable
private fun WaitingForPaymentRow() {
    val reducedMotion = rememberReducedMotion()
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
    ) {
        Box(modifier = Modifier.alpha(if (reducedMotion) 1f else alpha)) {
            Icon(
                imageVector = Icons.Outlined.Schedule,
                contentDescription = null,
                tint = CashuTheme.colors.pending,
                modifier = Modifier.size(CashuTheme.spacing.loose),
            )
        }
        Text(
            text = "Waiting for payment…",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Plain "Expires in 12m 30s" caption, ticking every second and turning red
 *  under a minute. Reuses the shared [quoteExpiryText] formatter; hidden for
 *  never-expiring reusable offers (BOLT12 amountless). */
@Composable
private fun ExpiryCaption(expirySeconds: Long?) {
    val displayExpiry = mintQuoteDisplayExpiry(expirySeconds) ?: return
    var nowSeconds by remember(displayExpiry) { mutableStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(displayExpiry) {
        while (nowSeconds < displayExpiry) {
            delay(1000)
            nowSeconds = System.currentTimeMillis() / 1000
        }
    }
    val text = quoteExpiryText(expirySeconds, nowSeconds) ?: return
    val remaining = displayExpiry - nowSeconds
    val urgent = remaining < 60
    val color = if (urgent) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.micro),
    ) {
        Icon(
            imageVector = Icons.Outlined.Timer,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = if (remaining <= 0) "Expired" else "Expires in $text",
            style = MaterialTheme.typography.labelMedium.withMonoDigits(),
            color = color,
        )
    }
}

/** Success-row data lifted out of the paid quote so the terminal renders even
 *  after the sheet body crossfades away. */
private data class ReceiveSuccessInfo(
    val amountLabel: String?,
    val mintName: String?,
)

/** Full-screen shared success terminal for a paid receive (iOS
 *  `receiveSuccessView`). Auto-dismisses after a brief dwell — no Done button
 *  (Android carve-out; the mint runs on the wallet's app-lifetime scope). */
@Composable
private fun ReceiveSuccessTerminal(info: ReceiveSuccessInfo, onDone: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(1800)
        onDone()
    }
    PaymentStatusScreen(
        phase = PaymentStatusPhase.Success,
        title = "Payment Received!",
        onDone = null,
        rows = {
            if (info.amountLabel != null) {
                InspectorRow(
                    label = "Amount",
                    value = info.amountLabel,
                    leadingIcon = Icons.Outlined.Payments,
                    valueMonospaced = true,
                )
            }
            if (info.mintName != null) {
                if (info.amountLabel != null) CanvasDivider(leadingInset = 16.dp)
                InspectorRow(
                    label = "Mint",
                    value = info.mintName,
                    leadingIcon = Icons.Outlined.AccountBalance,
                )
            }
        },
    )
}
