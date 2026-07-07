package org.cashu.wallet.ui.receive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.TokenParser
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Core.stablePendingReceiveTokenId
import org.cashu.wallet.Models.TokenInfo
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.CashuTextField
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.InspectorRow
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.PaymentStatusPhase
import org.cashu.wallet.ui.components.PaymentStatusScreen
import org.cashu.wallet.ui.components.TwoFaceCrossfade
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

private sealed interface ReceiveFace {
    data object Paste : ReceiveFace
    data class Review(
        val token: String,
        val info: TokenInfo,
        val fee: Long,
        val lockState: TokenLockState,
        val unknownMint: Boolean,
    ) : ReceiveFace
}

private sealed interface ReceiveStatus {
    data object Processing : ReceiveStatus
    data class Success(val amountLabel: String) : ReceiveStatus
    data class Failure(val reason: String) : ReceiveStatus
}

private const val MIN_RECEIVE_PROCESSING_MS = 650L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveEcashScreen(
    walletManager: WalletManager,
    settingsManager: SettingsManager,
    nostrService: org.cashu.wallet.Core.NostrService,
    cashuRequestStore: org.cashu.wallet.Core.CashuRequestStore,
    onOpenRequest: (String) -> Unit,
    onClose: () -> Unit,
    onScan: () -> Unit,
    prefilledPayload: String? = null,
    onPrefilledConsumed: () -> Unit = {},
) {
    val walletState by walletManager.state.collectAsStateWithLifecycle()
    val settings by settingsManager.state.collectAsStateWithLifecycle()
    val formatter = remember { AmountFormatter() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var face: ReceiveFace by remember { mutableStateOf(ReceiveFace.Paste) }
    var input by remember { mutableStateOf("") }
    var validating by remember { mutableStateOf(false) }
    var receiving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<ReceiveStatus?>(null) }

    BackHandler(enabled = status != null || face is ReceiveFace.Review) {
        when {
            status != null -> {
                status = null
                receiving = false
            }
            face is ReceiveFace.Review -> face = ReceiveFace.Paste
            else -> onClose()
        }
    }

    // Auto-paste a clipboard token on open (iOS autoPasteEcashReceive).
    LaunchedEffect(Unit) {
        if (settings.autoPasteEcashReceive && input.isBlank() && prefilledPayload.isNullOrBlank()) {
            clipboard.getText()?.text?.let { clip ->
                TokenParser.extractToken(clip)?.let { input = it }
            }
        }
    }

    // iOS "New Request": publish a fresh any-amount NUT-18 request over the
    // wallet's Nostr identity and open its inspector.
    fun createNewRequest() {
        val nostr = nostrService.state.value
        val relays = settings.nostrRelays
        if (nostr.publicKeyHex.isBlank() || relays.isEmpty()) {
            errorText = "Nostr isn't ready — check your relays in Settings."
            return
        }
        errorText = null
        runCatching {
            val id = org.cashu.wallet.Models.CashuRequest.newId()
            val mints = emptyList<String>()
            val encoded = org.cashu.wallet.Core.PaymentRequestBuilder.build(
                id = id,
                amount = null,
                unit = "sat",
                mints = mints,
                description = null,
                nostrPubkeyHex = nostr.publicKeyHex,
                relays = relays,
            )
            cashuRequestStore.createNew(id = id, mints = mints, encoded = encoded)
        }.onSuccess { request ->
            onOpenRequest(request.id)
        }.onFailure {
            errorText = it.message ?: "Couldn't create a request."
        }
    }

    fun validateAndReview(raw: String) {
        errorText = null
        val token = TokenParser.extractToken(raw)
        if (token == null) {
            errorText = TokenParser.malformedTokenMessage(raw) ?: "Couldn't read token."
            return
        }
        val info = TokenInfo.parse(token)
        if (info == null) {
            errorText = "Couldn't decode token."
            return
        }
        validating = true
        scope.launch {
            try {
                val fee = runCatching { walletManager.calculateReceiveFee(token) }.getOrDefault(0L)
                val locks = TokenParser.p2pkPubkeys(token)
                val reviewFlags = receiveEcashReviewFlags(
                    info = info,
                    tokenLockPubkeys = locks,
                    knownP2PKPubkeys = locks
                        .filter(settingsManager::isKnownP2PKPublicKey)
                        .toSet(),
                    walletMintUrls = walletState.mints.map { it.url },
                )
                face = ReceiveFace.Review(
                    token = token,
                    info = info,
                    fee = fee,
                    lockState = reviewFlags.lockState,
                    unknownMint = reviewFlags.unknownMint,
                )
            } catch (t: Throwable) {
                errorText = t.message ?: "Validation failed."
            } finally {
                validating = false
            }
        }
    }

    LaunchedEffect(prefilledPayload) {
        val pre = prefilledPayload?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        input = pre
        validateAndReview(pre)
        onPrefilledConsumed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val currentStatus = status
                    Text(
                        when (currentStatus) {
                            ReceiveStatus.Processing -> "Receiving token"
                            is ReceiveStatus.Success -> "Payment received"
                            is ReceiveStatus.Failure -> "Could not receive"
                            null -> when (face) {
                                ReceiveFace.Paste -> "Receive ecash"
                                is ReceiveFace.Review -> "Review token"
                            }
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            status is ReceiveStatus.Success -> onClose()
                            status != null -> {
                                status = null
                                receiving = false
                            }
                            face is ReceiveFace.Paste -> onClose()
                            face is ReceiveFace.Review -> face = ReceiveFace.Paste
                        }
                    }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    if (face is ReceiveFace.Paste) {
                        IconButton(onClick = onScan) {
                            Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Scan")
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
            ReceiveStatus.Processing -> {
                PaymentStatusScreen(
                    phase = PaymentStatusPhase.Processing,
                    title = "Receiving token…",
                    modifier = Modifier.padding(padding),
                )
                return@Scaffold
            }
            is ReceiveStatus.Success -> {
                PaymentStatusScreen(
                    phase = PaymentStatusPhase.Success,
                    title = "Payment received",
                    detail = currentStatus.amountLabel,
                    modifier = Modifier.padding(padding),
                    onDone = onClose,
                )
                return@Scaffold
            }
            is ReceiveStatus.Failure -> {
                PaymentStatusScreen(
                    phase = PaymentStatusPhase.Failure,
                    title = "Could not receive",
                    detail = currentStatus.reason,
                    doneLabel = "Try again",
                    modifier = Modifier.padding(padding),
                    onDone = { status = null },
                )
                return@Scaffold
            }
            null -> Unit
        }
        TwoFaceCrossfade(
            targetState = face,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            label = "receive-ecash-face",
        ) { current ->
            when (current) {
                is ReceiveFace.Paste -> PasteFace(
                    input = input,
                    onInputChange = { input = it; errorText = null },
                    onPaste = {
                        val clip = clipboard.getText()?.text
                        if (!clip.isNullOrBlank()) input = clip
                    },
                    onClear = { input = ""; errorText = null },
                    onContinue = { validateAndReview(input) },
                    onNewRequest = ::createNewRequest,
                    busy = validating,
                    errorText = errorText,
                    canContinue = input.isNotBlank() && !validating,
                )

                is ReceiveFace.Review -> ReviewFace(
                    info = current.info,
                    fee = current.fee,
                    lockState = current.lockState,
                    unknownMint = current.unknownMint,
                    receiving = receiving,
                    formatter = formatter,
                    useBitcoinSymbol = settings.useBitcoinSymbol,
                    onReceive = {
                        receiving = true
                        status = ReceiveStatus.Processing
                        errorText = null
                        scope.launch {
                            try {
                                val started = System.currentTimeMillis()
                                walletManager.receiveTokens(current.token)
                                val elapsed = System.currentTimeMillis() - started
                                if (elapsed < MIN_RECEIVE_PROCESSING_MS) {
                                    delay(MIN_RECEIVE_PROCESSING_MS - elapsed)
                                }
                                status = ReceiveStatus.Success(
                                    amountLabel = formatTokenAmount(
                                        current.info,
                                        formatter,
                                        settings.useBitcoinSymbol,
                                    ),
                                )
                            } catch (t: Throwable) {
                                status = ReceiveStatus.Failure(t.message ?: "Could not receive.")
                            } finally {
                                receiving = false
                            }
                        }
                    },
                    onReceiveLater = {
                        val pending = org.cashu.wallet.Models.PendingReceiveToken(
                            tokenId = stablePendingReceiveTokenId(current.token),
                            token = current.token,
                            amount = current.info.amount,
                            mintUrl = current.info.mint,
                            dateEpochMillis = System.currentTimeMillis(),
                        )
                        walletManager.savePendingReceiveToken(pending)
                        onClose()
                    },
                    errorText = errorText,
                )
            }
        }
    }
}

/**
 * iOS ReceiveEcashView form: a tall monospace token editor with a corner
 * paste/clear affordance, then Continue + New Request as sibling CTAs.
 */
@Composable
private fun PasteFace(
    input: String,
    onInputChange: (String) -> Unit,
    onPaste: () -> Unit,
    onClear: () -> Unit,
    onContinue: () -> Unit,
    onNewRequest: () -> Unit,
    busy: Boolean,
    errorText: String?,
    canContinue: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.comfortable)
            .imePadding()
            .navigationBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Spacer(Modifier.height(CashuTheme.spacing.micro))
        CashuTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            placeholder = "cashuB…",
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            ),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
            trailingIcon = if (input.isBlank()) {
                {
                    IconButton(onClick = onPaste) {
                        Icon(
                            imageVector = Icons.Outlined.ContentPaste,
                            contentDescription = "Paste",
                        )
                    }
                }
            } else {
                {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Filled.Cancel,
                            contentDescription = "Clear",
                        )
                    }
                }
            },
        )
        if (errorText != null) {
            InlineNotice(text = errorText)
        }
        PrimaryButton(
            text = if (busy) "Reading…" else "Continue",
            onClick = onContinue,
            enabled = canContinue,
            loading = busy,
        )
        PrimaryButton(
            text = "New Request",
            onClick = onNewRequest,
        )
    }
}

@Composable
private fun ReviewFace(
    info: TokenInfo,
    fee: Long,
    lockState: TokenLockState,
    unknownMint: Boolean,
    receiving: Boolean,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
    onReceive: () -> Unit,
    onReceiveLater: () -> Unit,
    errorText: String?,
) {
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
        AmountText(
            text = formatTokenAmount(info, formatter, useBitcoinSymbol),
            style = MaterialTheme.typography.displayMedium.withMonoDigits(),
        )
        val isSatToken = info.unit.equals("sat", ignoreCase = true)
        val tokenCurrency = CurrencyRegistry.currencyForMintUnit(info.unit)
        Column(modifier = Modifier.fillMaxWidth()) {
            InspectorRow(
                label = "Fee",
                value = when {
                    fee == 0L -> "Free"
                    isSatToken -> "$fee sat"
                    else -> CurrencyAmount(fee, tokenCurrency).formatted()
                },
                leadingIcon = Icons.Outlined.Receipt,
            )
            CanvasDivider(leadingInset = 16)
            InspectorRow(
                label = "Mint",
                value = info.mint,
                leadingIcon = Icons.Outlined.AccountBalance,
            )
            if (unknownMint) {
                CanvasDivider(leadingInset = 16)
                InspectorRow(
                    label = "Mint status",
                    value = "Unknown mint",
                    leadingIcon = Icons.Outlined.AccountBalance,
                )
            }
            if (lockState != TokenLockState.None) {
                CanvasDivider(leadingInset = 16)
                InspectorRow(
                    label = "P2PK",
                    value = if (lockState == TokenLockState.YourKey) "Your key" else "Unknown key",
                    leadingIcon = Icons.Outlined.Lock,
                )
            }
            if (info.memo != null) {
                CanvasDivider(leadingInset = 16)
                InspectorRow(
                    label = "Memo",
                    value = info.memo,
                )
            }
        }
        if (errorText != null) {
            InlineNotice(text = errorText)
        }
        if (unknownMint) {
            InlineNotice(text = "This token comes from a mint that is not in your wallet yet.")
        }
        if (lockState == TokenLockState.UnknownKey) {
            InlineNotice(text = "This token is locked to a P2PK key that is not stored on this device.")
        }
        Spacer(modifier = Modifier.height(CashuTheme.spacing.snug))
        PrimaryButton(
            text = if (receiving) "Receiving…" else "Receive",
            onClick = onReceive,
            enabled = lockState != TokenLockState.UnknownKey && !receiving,
            loading = receiving,
        )
        GhostButton(
            text = "Receive later",
            onClick = onReceiveLater,
            enabled = !receiving,
        )
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}

private fun Modifier.heightFor(height: Int): Modifier =
    this.then(Modifier.height(height.dp))
