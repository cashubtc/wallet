package org.cashu.wallet.ui.receive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Receipt
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.TokenParser
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Models.TokenInfo
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.CashuTextField
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.InspectorRow
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.SecondaryButton
import org.cashu.wallet.ui.components.SheetHeader
import org.cashu.wallet.ui.components.TwoFaceCrossfade
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

private sealed interface ReceiveFace {
    data object Paste : ReceiveFace
    data class Review(val token: String, val info: TokenInfo, val fee: Long, val locked: Boolean) : ReceiveFace
}

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
    onDismissLockChanged: (Boolean) -> Unit = {},
) {
    val walletState by walletManager.state.collectAsState()
    val settings by settingsManager.state.collectAsState()
    val formatter = remember { AmountFormatter() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var face: ReceiveFace by remember { mutableStateOf(ReceiveFace.Paste) }
    var input by remember { mutableStateOf("") }
    var validating by remember { mutableStateOf(false) }
    var receiving by remember { mutableStateOf(false) }
    var errorText by remember { mutableStateOf<String?>(null) }

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
            val mints = listOfNotNull(walletState.activeMint?.url)
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
                val unlocked = if (locks.isEmpty()) {
                    true
                } else {
                    settingsManager.p2pkSigningKeysFor(locks).isNotEmpty()
                }
                face = ReceiveFace.Review(token = token, info = info, fee = fee, locked = !unlocked)
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

    // Don't let a swipe-down interrupt a redeem in flight.
    LaunchedEffect(validating, receiving) { onDismissLockChanged(validating || receiving) }

    // System back unwinds Review → Paste; from Paste the sheet handles it.
    BackHandler(enabled = face is ReceiveFace.Review) {
        face = ReceiveFace.Paste
        errorText = null
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SheetHeader(
            title = when (face) {
                ReceiveFace.Paste -> "Receive ecash"
                is ReceiveFace.Review -> "Review token"
            },
            navigationIcon = when (face) {
                ReceiveFace.Paste -> Icons.Outlined.Close
                is ReceiveFace.Review -> Icons.AutoMirrored.Outlined.ArrowBack
            },
            navigationContentDescription = when (face) {
                ReceiveFace.Paste -> "Close"
                is ReceiveFace.Review -> "Back"
            },
            onNavigationClick = {
                when (face) {
                    ReceiveFace.Paste -> onClose()
                    is ReceiveFace.Review -> face = ReceiveFace.Paste
                }
            },
            actions = {
                if (face is ReceiveFace.Paste) {
                    IconButton(onClick = onScan) {
                        Icon(Icons.Outlined.QrCodeScanner, contentDescription = "Scan")
                    }
                }
            },
        )
        TwoFaceCrossfade(
            targetState = face,
            modifier = Modifier.fillMaxWidth(),
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
                    locked = current.locked,
                    receiving = receiving,
                    formatter = formatter,
                    useBitcoinSymbol = settings.useBitcoinSymbol,
                    onReceive = {
                        receiving = true
                        errorText = null
                        scope.launch {
                            try {
                                walletManager.receiveTokens(current.token)
                                onClose()
                            } catch (t: Throwable) {
                                errorText = t.message ?: "Could not receive."
                            } finally {
                                receiving = false
                            }
                        }
                    },
                    onReceiveLater = {
                        val pending = org.cashu.wallet.Models.PendingReceiveToken(
                            tokenId = current.token.take(64),
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
 * iOS ReceiveEcashView form, at iOS `.medium`-detent proportions: a compact
 * monospace token editor with the paste/clear affordance pinned to its
 * bottom-trailing corner (not vertically centered), then Continue (primary)
 * over New Request (tonal) — the CTA hierarchy iOS renders as glass siblings.
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
            .fillMaxWidth()
            .padding(horizontal = CashuTheme.spacing.comfortable)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            CashuTextField(
                value = input,
                onValueChange = onInputChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = "cashuB…",
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                ),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
                minLines = TokenFieldMinLines,
                maxLines = TokenFieldMaxLines,
            )
            // Corner affordance (iOS bottomTrailing): paste when empty, clear when full.
            IconButton(
                onClick = if (input.isBlank()) onPaste else onClear,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(CornerAffordancePadding),
            ) {
                Icon(
                    imageVector = if (input.isBlank()) {
                        Icons.Outlined.ContentPaste
                    } else {
                        Icons.Filled.Cancel
                    },
                    contentDescription = if (input.isBlank()) "Paste" else "Clear",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (errorText != null) {
            InlineNotice(text = errorText)
        }
        PrimaryButton(
            text = if (busy) "Reading…" else "Continue",
            onClick = onContinue,
            enabled = canContinue,
            loading = busy,
        )
        SecondaryButton(
            text = "New Request",
            onClick = onNewRequest,
        )
        Spacer(Modifier.navigationBarsPadding())
    }
}

// ~6 monospace body lines: enough to show a token's head without swallowing
// the sheet; the field scrolls internally beyond TokenFieldMaxLines.
private const val TokenFieldMinLines = 6
private const val TokenFieldMaxLines = 8
private val CornerAffordancePadding = 4.dp

@Composable
private fun ReviewFace(
    info: TokenInfo,
    fee: Long,
    locked: Boolean,
    receiving: Boolean,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
    onReceive: () -> Unit,
    onReceiveLater: () -> Unit,
    errorText: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = CashuTheme.spacing.comfortable,
                vertical = CashuTheme.spacing.comfortable,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.loose),
    ) {
        // Amount and fee render in the token's own unit. The hero shows what
        // claiming will actually credit (token value minus the receive-swap
        // fee) — a 5001-sat token that redeems for 5000 must read as 5000,
        // with the fee row accounting for the difference. Mirrors iOS
        // ReceiveTokenDetailView.netReceiveAmount.
        val isSatToken = info.unit.equals("sat", ignoreCase = true)
        val tokenCurrency = CurrencyRegistry.currencyForMintUnit(info.unit)
        val netAmount = info.amount - fee.coerceIn(0L, info.amount)
        AmountText(
            text = if (isSatToken) {
                formatter.formatWalletSats(netAmount, useBitcoinSymbol)
            } else {
                CurrencyAmount(netAmount, tokenCurrency).formatted()
            },
            style = MaterialTheme.typography.displayMedium.withMonoDigits(),
        )
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
            CanvasDivider(leadingInset = 16.dp)
            InspectorRow(
                label = "Mint",
                value = info.mint,
                leadingIcon = Icons.Outlined.AccountBalance,
            )
            if (locked) {
                CanvasDivider(leadingInset = 16.dp)
                InspectorRow(
                    label = "P2PK",
                    value = "Requires your key",
                    leadingIcon = Icons.Outlined.Lock,
                )
            }
            if (info.memo != null) {
                CanvasDivider(leadingInset = 16.dp)
                InspectorRow(
                    label = "Memo",
                    value = info.memo,
                )
            }
        }
        if (errorText != null) {
            InlineNotice(text = errorText)
        }
        Spacer(modifier = Modifier.height(CashuTheme.spacing.snug))
        PrimaryButton(
            text = if (receiving) "Receiving…" else "Receive",
            onClick = onReceive,
            enabled = !locked && !receiving,
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
