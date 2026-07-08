package org.cashu.wallet.ui.receive

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
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
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
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.UnitAmountEntry
import org.cashu.wallet.Core.Wallet.userFacingWalletMessage
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.MintQuoteState
import org.cashu.wallet.Models.PaymentMethodKind
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.MintAvatar
import org.cashu.wallet.ui.components.MintPickerSheet
import org.cashu.wallet.ui.components.NumberPad
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.QrCard
import org.cashu.wallet.ui.components.SheetHeader
import org.cashu.wallet.ui.components.TwoFaceScreen
import org.cashu.wallet.ui.components.UnitPickerSheet
import org.cashu.wallet.ui.components.shareText
import org.cashu.wallet.ui.theme.CapsuleShape
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.rememberReducedMotion
import org.cashu.wallet.ui.theme.withMonoDigits

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
    var paymentJustReceived by remember { mutableStateOf(false) }
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
    BackHandler(enabled = face is ReceiveLnFace.Display) {
        face = ReceiveLnFace.Input
    }

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
                        Icon(Icons.Outlined.IosShare, contentDescription = "Share")
                    }
                } else if (current is ReceiveLnFace.Input) {
                    // Method picker rides the header (iOS parity): an icon
                    // opening a menu, shown only when >1 method exists.
                    if (supportedMethods.size > 1) {
                        var methodMenuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { methodMenuOpen = true }) {
                            Icon(
                                imageVector = method.menuIcon,
                                contentDescription = "Payment method",
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
                    unitLabel = if (isSatUnit) "sat" else effectiveUnit.uppercase(),
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
                    LaunchedEffect(liveQuote.state) {
                        if (liveQuote.state == MintQuoteState.Paid) {
                            runCatching { walletManager.mintTokens(liveQuote.id) }
                            paymentJustReceived = true
                            // Receive-flow resolution: dwell on the celebration,
                            // then the screen dismisses itself (iOS parity).
                            delay(1_200)
                            onClose()
                        }
                    }
                    DisplayFace(
                        quote = liveQuote,
                        amountLabel = liveQuote.amount?.let {
                            if (liveQuote.unit.equals("sat", ignoreCase = true)) {
                                formatter.formatWalletSats(it, settings.useBitcoinSymbol)
                            } else {
                                CurrencyAmount(
                                    it,
                                    CurrencyRegistry.currencyForMintUnit(liveQuote.unit),
                                ).formatted()
                            }
                        },
                        showCelebration = paymentJustReceived,
                        onCopy = { clipboard.setText(AnnotatedString(liveQuote.request)) },
                        onDone = onClose,
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
        Spacer(Modifier.weight(1f))
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

@Composable
private fun DisplayFace(
    quote: MintQuoteInfo,
    amountLabel: String?,
    showCelebration: Boolean,
    onCopy: () -> Unit,
    onDone: () -> Unit,
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
        QuoteStatusRow(isPaid = isPaid, showCelebration = showCelebration)
        Spacer(Modifier.height(CashuTheme.spacing.snug))
        PrimaryButton(
            text = if (copied) "Copied" else "Copy request",
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

@Composable
private fun QuoteStatusRow(isPaid: Boolean, showCelebration: Boolean) {
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
        } else {
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
        Spacer(modifier = Modifier.weight(1f))
    }
}
