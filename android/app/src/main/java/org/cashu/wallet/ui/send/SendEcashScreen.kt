package org.cashu.wallet.ui.send

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
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
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.UnitAmountEntry
import org.cashu.wallet.Core.Wallet.userFacingWalletMessage
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Models.SendTokenResult
import org.cashu.wallet.ui.components.AmountEntryHero
import org.cashu.wallet.ui.components.CashuTextField
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.MintPickerSheet
import org.cashu.wallet.ui.components.MintSelectorRow
import org.cashu.wallet.ui.components.NumberPad
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.QrCard
import org.cashu.wallet.ui.components.SheetHeader
import org.cashu.wallet.ui.components.TwoFaceScreen
import org.cashu.wallet.ui.components.UnitPickerSheet
import org.cashu.wallet.ui.components.shareText
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.rememberReducedMotion
import org.cashu.wallet.ui.theme.withMonoDigits

// Inline status icons inside dense rows — smaller than the standard 20dp body icon.
private val STATUS_ICON_SMALL = 18.dp
private val CHECKING_PROGRESS_SIZE = 14.dp

private sealed interface SendFace {
    data object Input : SendFace

    // Unit and amount are captured at generation time so the token face keeps
    // rendering correctly after the entry state resets.
    data class Generated(
        val result: SendTokenResult,
        val mintUrl: String,
        val unit: String,
        val amount: Long,
    ) : SendFace
}

@Composable
fun SendEcashScreen(
    walletManager: WalletManager,
    settingsManager: SettingsManager,
    priceService: org.cashu.wallet.Core.PriceService,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onDismissLockChanged: (Boolean) -> Unit = {},
) {
    val walletState by walletManager.state.collectAsState()
    val settings by settingsManager.state.collectAsState()
    val priceState by priceService.state.collectAsState()
    val formatter = remember { AmountFormatter() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var face: SendFace by remember { mutableStateOf(SendFace.Input) }
    var amount by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var pickerOpen by remember { mutableStateOf(false) }
    var selectedMintUrl by remember { mutableStateOf<String?>(null) }
    var unitPickerOpen by remember { mutableStateOf(false) }
    var selectedUnit by remember { mutableStateOf<String?>(null) }
    var nonSatBalance by remember { mutableStateOf<Long?>(null) }
    var p2pkOn by remember { mutableStateOf(false) }
    var p2pkInput by remember { mutableStateOf("") }
    var p2pkInputError by remember { mutableStateOf<String?>(null) }

    val activeMintUrl = selectedMintUrl ?: walletState.activeMint?.url
    val activeMint = walletState.mints.firstOrNull { it.url == activeMintUrl } ?: walletState.activeMint

    // Effective send unit: explicit pick when the mint offers it, else the
    // default unit that actually holds balance (a USD-only wallet opens on USD).
    val effectiveUnit = run {
        val units = activeMint?.units ?: listOf("sat")
        val explicit = selectedUnit?.takeIf { units.contains(it) }
        explicit ?: run {
            fun holdsBalance(unit: String): Boolean = if (unit.equals("sat", ignoreCase = true)) {
                (activeMint?.balance ?: 0L) > 0L
            } else {
                (walletState.balancesByUnit[unit] ?: 0L) > 0L
            }
            val fallback = activeMint?.defaultUnit ?: "sat"
            if (holdsBalance(fallback)) fallback
            else units.firstOrNull(::holdsBalance) ?: fallback
        }
    }
    val currency = CurrencyRegistry.currencyForMintUnit(effectiveUnit)
    val isSatUnit = effectiveUnit.equals("sat", ignoreCase = true)
    val amountValue = UnitAmountEntry.baseUnits(amount, currency.decimals)

    // Per-(mint, unit) spendable balance. Sat answers from cache; non-sat loads
    // through the CDK unit wallet on demand.
    LaunchedEffect(activeMintUrl, effectiveUnit) {
        nonSatBalance = null
        if (!isSatUnit && activeMintUrl != null) {
            nonSatBalance = walletManager.unitBalance(activeMintUrl, effectiveUnit)
        }
    }
    val mintBalance = if (isSatUnit) activeMint?.balance ?: 0L else nonSatBalance ?: 0L
    val balanceLoading = !isSatUnit && nonSatBalance == null

    // Normalize and validate the P2PK input only when the lock is on.
    val validatedP2pkPubkey: String? = remember(p2pkOn, p2pkInput) {
        if (!p2pkOn) null
        else runCatching {
            org.cashu.wallet.Core.SettingsManager.normalizeP2PKPublicKeyForSend(p2pkInput)
        }.getOrNull()
    }
    LaunchedEffect(p2pkOn, p2pkInput) {
        if (!p2pkOn) {
            p2pkInputError = null
            return@LaunchedEffect
        }
        val trimmed = p2pkInput.trim()
        if (trimmed.isEmpty()) {
            p2pkInputError = null
            return@LaunchedEffect
        }
        p2pkInputError = runCatching {
            org.cashu.wallet.Core.SettingsManager.normalizeP2PKPublicKeyForSend(trimmed)
        }.exceptionOrNull()?.message
    }

    // Generation counts as money-in-motion: block sheet dismissal.
    LaunchedEffect(sending) { onDismissLockChanged(sending) }

    // System back mirrors the header chevron: Generated → Input, Input → the
    // Send surface. Swallow back while a token is being generated.
    BackHandler(enabled = true) {
        when {
            sending -> Unit
            face is SendFace.Generated -> face = SendFace.Input
            else -> onBack()
        }
    }

    Column(modifier = Modifier.fillMaxHeight()) {
        SheetHeader(
            title = when (face) {
                SendFace.Input -> "Send Ecash"
                is SendFace.Generated -> "Pending Ecash"
            },
            navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
            navigationContentDescription = "Back",
            onNavigationClick = {
                when (face) {
                    SendFace.Input -> onBack()
                    is SendFace.Generated -> face = SendFace.Input
                }
            },
            actions = {
                val current = face
                if (current is SendFace.Generated) {
                    IconButton(onClick = {
                        context.shareText(current.result.token, subject = "Cashu token")
                    }) {
                        Icon(Icons.Outlined.IosShare, contentDescription = "Share")
                    }
                } else if (current is SendFace.Input) {
                    if (activeMint?.supportsMultipleUnits == true) {
                        androidx.compose.material3.TextButton(onClick = { unitPickerOpen = true }) {
                            Text(
                                text = effectiveUnit.uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    IconButton(onClick = { p2pkOn = !p2pkOn }) {
                        Icon(
                            imageVector = if (p2pkOn) Icons.Filled.Lock
                            else Icons.Outlined.LockOpen,
                            contentDescription = if (p2pkOn) "P2PK locked" else "P2PK off",
                            tint = if (p2pkOn) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
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
                initial is SendFace.Input && target is SendFace.Generated
            },
            label = "send-ecash-face",
        ) { current ->
            when (current) {
                is SendFace.Input -> InputFace(
                    amount = amount,
                    onAmountChange = {
                        amount = it
                        errorText = null
                    },
                    activeMint = activeMint,
                    onPickMint = { pickerOpen = true },
                    onUseMax = {
                        if (mintBalance > 0L) {
                            amount = UnitAmountEntry.entryString(mintBalance, currency.decimals)
                        }
                    },
                    canUseMax = mintBalance > 0L,
                    amountValue = amountValue,
                    mintBalance = mintBalance,
                    balanceLoading = balanceLoading,
                    // Per-mint spendable balance, shown under the mint name
                    // inside the selector card (iOS MintAmountSelectorRow).
                    balanceText = when {
                        balanceLoading -> "…"
                        isSatUnit -> formatter.formatWalletSats(mintBalance, settings.useBitcoinSymbol)
                        else -> CurrencyAmount(mintBalance, currency).formatted()
                    },
                    isSat = isSatUnit,
                    unit = effectiveUnit,
                    useBitcoinSymbol = settings.useBitcoinSymbol,
                    formatter = formatter,
                    decimals = currency.decimals,
                    sending = sending,
                    errorText = errorText,
                    p2pkOn = p2pkOn,
                    p2pkInput = p2pkInput,
                    onP2pkInputChange = { p2pkInput = it },
                    p2pkInputError = p2pkInputError,
                    // iOS "Lock to my key" shortcut: opt-in via the Locked Ecash
                    // toggle, and it targets the seed-derived primary key.
                    p2pkMyKeyHex = if (settings.showP2PKButtonInDrawer) {
                        settingsManager.primaryP2PKKeyInfo()?.publicKey
                    } else null,
                    onUseMyP2pkKey = {
                        settingsManager.primaryP2PKKeyInfo()?.let { p2pkInput = it.publicKey }
                    },
                    canSendWithP2pk = !p2pkOn || validatedP2pkPubkey != null,
                    onSend = {
                        val mintUrl = activeMintUrl ?: walletState.activeMint?.url
                        if (mintUrl == null) {
                            errorText = "Add a mint first."
                            return@InputFace
                        }
                        if (amountValue <= 0L) {
                            errorText = "Enter an amount."
                            return@InputFace
                        }
                        if (p2pkOn && validatedP2pkPubkey == null) {
                            errorText = p2pkInputError ?: "Enter a valid P2PK pubkey."
                            return@InputFace
                        }
                        sending = true
                        scope.launch {
                            try {
                                val result = walletManager.sendTokens(
                                    amount = amountValue,
                                    // iOS Send Ecash has no memo field — always nil.
                                    memo = null,
                                    p2pkPubkey = validatedP2pkPubkey,
                                    mintUrl = mintUrl,
                                    unit = effectiveUnit,
                                )
                                face = SendFace.Generated(result, mintUrl, effectiveUnit, amountValue)
                                amount = ""
                            } catch (t: Throwable) {
                                errorText = t.userFacingWalletMessage
                            } finally {
                                sending = false
                            }
                        }
                    },
                )

                is SendFace.Generated -> GeneratedFace(
                    walletManager = walletManager,
                    result = current.result,
                    mintUrl = current.mintUrl,
                    unit = current.unit,
                    amountSats = current.amount,
                    pollingEnabled = settings.checkSentTokens,
                    amountLabel = if (current.unit.equals("sat", ignoreCase = true)) {
                        formatter.formatWalletSats(current.amount, settings.useBitcoinSymbol)
                    } else {
                        CurrencyAmount(
                            current.amount,
                            CurrencyRegistry.currencyForMintUnit(current.unit),
                        ).formatted()
                    },
                    fiatLabel = if (current.unit.equals("sat", ignoreCase = true) &&
                        settings.showFiatBalance && priceState.btcPrice > 0
                    ) {
                        formatter.formatFiat(
                            current.amount,
                            priceState.btcPrice,
                            settings.bitcoinPriceCurrency,
                        )
                    } else {
                        null
                    },
                    onDone = onClose,
                )
            }
        }
    }

    if (pickerOpen) {
        MintPickerSheet(
            mints = walletState.mints,
            activeMintUrl = activeMintUrl,
            onSelect = {
                selectedMintUrl = it.url
                selectedUnit = null
                amount = ""
                nonSatBalance = null
                errorText = null
                pickerOpen = false
            },
            onDismiss = { pickerOpen = false },
        )
    }

    if (unitPickerOpen) {
        UnitPickerSheet(
            units = activeMint?.units ?: listOf("sat"),
            selectedUnit = effectiveUnit,
            onSelect = {
                selectedUnit = it
                amount = ""
                nonSatBalance = null
                errorText = null
                unitPickerOpen = false
            },
            onDismiss = { unitPickerOpen = false },
        )
    }
}

@Composable
private fun InputFace(
    amount: String,
    onAmountChange: (String) -> Unit,
    activeMint: org.cashu.wallet.Models.MintInfo?,
    onPickMint: () -> Unit,
    onUseMax: () -> Unit,
    canUseMax: Boolean,
    amountValue: Long,
    mintBalance: Long,
    balanceLoading: Boolean,
    balanceText: String,
    isSat: Boolean,
    unit: String,
    useBitcoinSymbol: Boolean,
    formatter: AmountFormatter,
    decimals: Int,
    sending: Boolean,
    errorText: String?,
    p2pkOn: Boolean,
    p2pkInput: String,
    onP2pkInputChange: (String) -> Unit,
    p2pkInputError: String?,
    p2pkMyKeyHex: String?,
    onUseMyP2pkKey: () -> Unit,
    canSendWithP2pk: Boolean,
    onSend: () -> Unit,
) {
    val canSend = amountValue in 1..mintBalance && !sending && !balanceLoading && canSendWithP2pk
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.comfortable)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(CashuTheme.spacing.micro))
        // One card: avatar + name + balance + Use Max pill + chevron
        // (iOS MintAmountSelectorRow parity).
        if (activeMint != null) {
            MintSelectorRow(
                mint = activeMint,
                balanceText = balanceText,
                onPickMint = onPickMint,
                onUseMax = if (canUseMax) onUseMax else null,
            )
        }

        Spacer(Modifier.height(CashuTheme.spacing.snug))
        // iOS AmountEntryView: the amount dims primary → secondary while the
        // requested amount exceeds the spendable balance.
        val insufficient = !balanceLoading && amountValue > 0 && amountValue > mintBalance
        val amountColor by animateColorAsState(
            targetValue = if (insufficient) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            animationSpec = spring(stiffness = Spring.StiffnessMedium),
            label = "amount-color",
        )
        AmountEntryHero(
            entryRaw = amount,
            isSat = isSat,
            unit = unit,
            decimals = decimals,
            useBitcoinSymbol = useBitcoinSymbol,
            formatter = formatter,
            color = amountColor,
        )
        // Fade+scale warning (iOS .transition(.opacity.combined(with: .scale))),
        // reduce-motion collapses to a plain fade.
        val reduceMotion = rememberReducedMotion()
        AnimatedVisibility(
            visible = insufficient,
            enter = if (reduceMotion) {
                fadeIn(spring(stiffness = Spring.StiffnessMedium))
            } else {
                fadeIn(spring(stiffness = Spring.StiffnessMedium)) + scaleIn(
                    animationSpec = spring(stiffness = Spring.StiffnessMedium),
                    initialScale = 0.95f,
                )
            },
            exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)),
        ) {
            Text(
                text = "Insufficient balance",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        AnimatedVisibility(visible = p2pkOn) {
            P2pkLockSection(
                input = p2pkInput,
                onInputChange = onP2pkInputChange,
                inputError = p2pkInputError,
                myKeyHex = p2pkMyKeyHex,
                onUseMyKey = onUseMyP2pkKey,
            )
        }

        if (errorText != null) {
            InlineNotice(text = errorText)
        }

        Spacer(modifier = Modifier.weight(1f, fill = true))

        NumberPad(amount = amount, onAmountChange = onAmountChange, decimals = decimals)

        Spacer(Modifier.height(CashuTheme.spacing.page))
        PrimaryButton(
            text = if (sending) "Sending…" else "Send",
            onClick = onSend,
            enabled = canSend,
            loading = sending,
        )
        // Idiomatic inset spacer: exactly the navigation-bar height at the bottom.
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

@Composable
private fun P2pkLockSection(
    input: String,
    onInputChange: (String) -> Unit,
    inputError: String?,
    myKeyHex: String?,
    onUseMyKey: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight),
    ) {
        CashuTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier.fillMaxWidth(),
            label = "Recipient P2PK pubkey",
            placeholder = "02… or 64-char hex",
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.None,
            ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            isError = inputError != null && input.isNotBlank(),
        )
        if (inputError != null && input.isNotBlank()) {
            Text(
                text = inputError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (myKeyHex != null) {
            org.cashu.wallet.ui.components.GhostButton(
                text = "Lock to my key",
                onClick = onUseMyKey,
            )
        }
    }
}

@Composable
private fun GeneratedFace(
    walletManager: org.cashu.wallet.Core.WalletManager,
    result: SendTokenResult,
    mintUrl: String,
    unit: String,
    amountSats: Long,
    pollingEnabled: Boolean,
    amountLabel: String,
    fiatLabel: String?,
    onDone: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    var claimState: ClaimState by remember(result.token) { mutableStateOf(ClaimState.Pending) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    // Poll the mint every 4s to detect when the recipient redeems the token.
    LaunchedEffect(result.token, mintUrl, pollingEnabled) {
        if (!pollingEnabled) return@LaunchedEffect
        while (claimState != ClaimState.Claimed) {
            delay(4_000)
            claimState = ClaimState.Checking
            // checkTokenSpent returns true once any proof is spent (redeemed);
            // null means the check failed — stay Pending, never fake a claim.
            val spent = runCatching {
                walletManager.checkTokenSpent(result.token, mintUrl)
            }.getOrNull()
            claimState = when {
                spent == true -> ClaimState.Claimed
                else -> ClaimState.Pending
            }
        }
    }

    // Claimed resolves to the shared full-screen terminal (iOS parity), with
    // Amount/Mint metadata rows under the check.
    if (claimState == ClaimState.Claimed) {
        org.cashu.wallet.ui.components.PaymentStatusScreen(
            phase = org.cashu.wallet.ui.components.PaymentStatusPhase.Success,
            title = "Claimed",
            onDone = onDone,
            rows = {
                org.cashu.wallet.ui.components.InspectorRow(
                    label = "Amount",
                    value = amountLabel,
                    leadingIcon = Icons.Outlined.Payments,
                )
                org.cashu.wallet.ui.components.CanvasDivider(leadingInset = 16.dp)
                org.cashu.wallet.ui.components.InspectorRow(
                    label = "Mint",
                    value = org.cashu.wallet.Core.shortenMintUrl(mintUrl),
                    leadingIcon = Icons.Outlined.AccountBalance,
                )
            },
        )
        return
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
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.loose),
    ) {
        QrCard(
            content = result.token,
            shareSubject = "Cashu token",
        )
        Text(
            text = amountLabel,
            style = MaterialTheme.typography.headlineMedium.withMonoDigits(),
            color = MaterialTheme.colorScheme.onSurface,
        )
        ClaimStatusRow(claimState = claimState)
        // Detail rows: Fee -> Unit -> Fiat (sat-only) -> Mint (iOS order).
        Column(modifier = Modifier.fillMaxWidth()) {
            if (result.fee > 0L) {
                org.cashu.wallet.ui.components.InspectorRow(
                    label = "Fee",
                    value = if (unit.equals("sat", ignoreCase = true)) {
                        "${result.fee} sat"
                    } else {
                        CurrencyAmount(result.fee, CurrencyRegistry.currencyForMintUnit(unit)).formatted()
                    },
                    valueMonospaced = true,
                )
                org.cashu.wallet.ui.components.CanvasDivider(leadingInset = 16.dp)
            }
            org.cashu.wallet.ui.components.InspectorRow(
                label = "Unit",
                value = unit.uppercase(),
            )
            if (fiatLabel != null) {
                org.cashu.wallet.ui.components.CanvasDivider(leadingInset = 16.dp)
                org.cashu.wallet.ui.components.InspectorRow(
                    label = "Fiat",
                    value = fiatLabel,
                    valueMonospaced = true,
                )
            }
            org.cashu.wallet.ui.components.CanvasDivider(leadingInset = 16.dp)
            org.cashu.wallet.ui.components.InspectorRow(
                label = "Mint",
                value = org.cashu.wallet.Core.shortenMintUrl(mintUrl),
            )
        }
        Spacer(modifier = Modifier.height(CashuTheme.spacing.micro))
        PrimaryButton(
            text = if (copied) "Copied" else "Copy",
            onClick = {
                clipboard.setText(AnnotatedString(result.token))
                copied = true
            },
        )
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
    }
}

private enum class ClaimState { Pending, Checking, Claimed }

@Composable
private fun ClaimStatusRow(claimState: ClaimState) {
    AnimatedContent(
        targetState = claimState,
        transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(220)) },
        label = "claim-state",
    ) { state ->
        when (state) {
            ClaimState.Pending -> {
                val reducedMotion = rememberReducedMotion()
                val transition = rememberInfiniteTransition(label = "pending-pulse")
                val pulseAlpha by transition.animateFloat(
                    initialValue = 1f,
                    targetValue = 0.4f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1100),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "pending-alpha",
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight),
                    modifier = Modifier.alpha(if (reducedMotion) 1f else pulseAlpha),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Schedule,
                        contentDescription = null,
                        tint = org.cashu.wallet.ui.theme.CashuTheme.colors.pending,
                        modifier = Modifier.size(STATUS_ICON_SMALL),
                    )
                    Text(
                        text = "Pending",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
            ClaimState.Checking -> {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight),
                ) {
                    LoadingIndicator(
                        modifier = Modifier.size(CHECKING_PROGRESS_SIZE),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Checking…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            ClaimState.Claimed -> Unit
        }
    }
}
