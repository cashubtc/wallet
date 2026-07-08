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
import androidx.compose.material.icons.outlined.Payments
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.TokenParser
import org.cashu.wallet.Core.Wallet.WalletMessage
import org.cashu.wallet.Core.Wallet.walletMessage
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Models.TokenInfo
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.CashuTextField
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.InspectorRow
import org.cashu.wallet.ui.components.PaymentStatusPhase
import org.cashu.wallet.ui.components.PaymentStatusScreen
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.SecondaryButton
import org.cashu.wallet.ui.components.SheetHeader
import org.cashu.wallet.ui.components.TwoFaceScreen
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

private sealed interface ReceiveFace {
    data object Paste : ReceiveFace
    data class Review(val token: String, val info: TokenInfo, val fee: Long, val locked: Boolean) : ReceiveFace
}

/**
 * The claim terminal (iOS ReceiveTokenDetailView phase): once Receive is
 * tapped, the sheet body swaps to the shared PaymentStatusScreen — spinner →
 * green check with Amount/Fee/Mint rows, or red X with mapped error copy.
 */
private sealed interface ClaimStatus {
    data object Claiming : ClaimStatus
    data class Claimed(val amount: Long, val fee: Long, val unit: String, val mint: String) : ClaimStatus
    data class Failed(val message: WalletMessage) : ClaimStatus
}

// iOS ReceiveTokenDetailView: floor the redeem at 500ms so the "Claiming…"
// spinner is legible on instant redeems. Not a fake delay — the redeem itself
// hits the mint; we only pad the *display* of an early result.
private const val MinClaimingBeatMillis = 500L

// Floor for the pinned claim-terminal height: enough room for the glyph +
// title + failure copy + the bottom-anchored Done button, in case the review
// face measured unusually short.
private val MinStatusHeight = 360.dp

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
    var status by remember { mutableStateOf<ClaimStatus?>(null) }
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
    LaunchedEffect(validating, status) {
        onDismissLockChanged(validating || status == ClaimStatus.Claiming)
    }

    // System back unwinds Review → Paste; swallow it entirely while claiming.
    // On a terminal (success/failure) face, back falls through to the sheet.
    BackHandler(enabled = status == ClaimStatus.Claiming || (status == null && face is ReceiveFace.Review)) {
        if (status == null) {
            face = ReceiveFace.Paste
            errorText = null
        }
    }

    fun claim(review: ReceiveFace.Review) {
        if (status != null) return
        status = ClaimStatus.Claiming
        scope.launch {
            val startedAt = System.currentTimeMillis()
            val result = try {
                Result.success(walletManager.receiveTokens(review.token))
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Result.failure(t)
            }
            // Hold the "Claiming…" beat so the spinner never flashes for a frame.
            val elapsed = System.currentTimeMillis() - startedAt
            if (elapsed < MinClaimingBeatMillis) delay(MinClaimingBeatMillis - elapsed)
            status = result.fold(
                onSuccess = { credited ->
                    ClaimStatus.Claimed(
                        // The gateway reports what was actually credited (net of the
                        // receive-swap fee); fall back to the reviewed net amount.
                        amount = if (credited > 0L) credited else review.info.amount - review.fee.coerceIn(0L, review.info.amount),
                        fee = review.fee,
                        unit = review.info.unit,
                        mint = review.info.mint,
                    )
                },
                onFailure = { ClaimStatus.Failed(it.walletMessage) },
            )
        }
    }

    // The claim terminal replaces the whole sheet body (header included) but —
    // unlike the send flow's full-height takeover — keeps the sheet at the
    // height the review face occupied. iOS runs the whole claim as a phase
    // morph inside the `.medium`-detent sheet (ReceiveTokenDetailView), so the
    // sheet must not balloon to full screen here. We measure the wrap-content
    // body continuously; when Receive is tapped the pin equals the face on
    // screen, so the review → "Claiming…" swap has zero height jump.
    val density = LocalDensity.current
    var measuredBodyHeightPx by remember { mutableIntStateOf(0) }
    val pinnedStatusHeight = with(density) { measuredBodyHeightPx.toDp() }.coerceAtLeast(MinStatusHeight)
    Column(
        modifier = if (status != null) {
            Modifier.fillMaxWidth().height(pinnedStatusHeight)
        } else {
            Modifier.fillMaxWidth().onSizeChanged { measuredBodyHeightPx = it.height }
        },
    ) {
        when (val current = status) {
            ClaimStatus.Claiming -> Box(Modifier.weight(1f).fillMaxWidth()) {
                PaymentStatusScreen(phase = PaymentStatusPhase.Processing, title = "Claiming…")
            }

            is ClaimStatus.Claimed -> Box(Modifier.weight(1f).fillMaxWidth()) {
                val isSat = current.unit.equals("sat", ignoreCase = true)
                val currency = CurrencyRegistry.currencyForMintUnit(current.unit)
                fun formatted(value: Long): String = if (isSat) {
                    formatter.formatWalletSats(value, settings.useBitcoinSymbol)
                } else {
                    CurrencyAmount(value, currency).formatted()
                }
                PaymentStatusScreen(
                    phase = PaymentStatusPhase.Success,
                    title = "Payment received",
                    onDone = onClose,
                    rows = {
                        InspectorRow(
                            label = "Amount",
                            value = formatted(current.amount),
                            leadingIcon = Icons.Outlined.Payments,
                        )
                        if (current.fee > 0L) {
                            CanvasDivider(leadingInset = 16.dp)
                            InspectorRow(
                                label = "Fee",
                                value = formatted(current.fee),
                                leadingIcon = Icons.Outlined.Receipt,
                            )
                        }
                        CanvasDivider(leadingInset = 16.dp)
                        InspectorRow(
                            label = "Mint",
                            value = current.mint,
                            leadingIcon = Icons.Outlined.AccountBalance,
                        )
                    },
                )
            }

            is ClaimStatus.Failed -> Box(Modifier.weight(1f).fillMaxWidth()) {
                PaymentStatusScreen(
                    phase = PaymentStatusPhase.Failure,
                    title = "Couldn't receive",
                    detail = current.message.text,
                    // Terminal outcomes (already redeemed) can't be retried — offer
                    // Done; anything else returns to Review for another attempt.
                    doneLabel = if (current.message.isTerminal) "Done" else "Try again",
                    onDone = {
                        if (current.message.isTerminal) onClose() else status = null
                    },
                )
            }

            null -> {
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
                // iOS pushes ReceiveTokenDetailView onto the sheet's
                // NavigationStack (ReceiveView.navigationDestination), so
                // paste ↔ review reads as a horizontal push/pop — not a fade.
                TwoFaceScreen(
                    targetState = face,
                    modifier = Modifier.fillMaxWidth(),
                    forward = { _, target -> target is ReceiveFace.Review },
                    label = "receive-ecash-face",
                ) { currentFace ->
                    when (currentFace) {
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
                            info = currentFace.info,
                            fee = currentFace.fee,
                            locked = currentFace.locked,
                            formatter = formatter,
                            useBitcoinSymbol = settings.useBitcoinSymbol,
                            onReceive = { claim(currentFace) },
                            onReceiveLater = {
                                val pending = org.cashu.wallet.Models.PendingReceiveToken(
                                    tokenId = currentFace.token.take(64),
                                    token = currentFace.token,
                                    amount = currentFace.info.amount,
                                    mintUrl = currentFace.info.mint,
                                    dateEpochMillis = System.currentTimeMillis(),
                                )
                                walletManager.savePendingReceiveToken(pending)
                                onClose()
                            },
                        )
                    }
                }
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
                isError = errorText != null,
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
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
    onReceive: () -> Unit,
    onReceiveLater: () -> Unit,
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
        // Claim outcomes no longer land here: tapping Receive swaps the sheet
        // body to the PaymentStatusScreen terminal (success check / failure X).
        Spacer(modifier = Modifier.height(CashuTheme.spacing.snug))
        PrimaryButton(
            text = "Receive",
            onClick = onReceive,
            enabled = !locked,
        )
        GhostButton(
            text = "Receive later",
            onClick = onReceiveLater,
        )
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}
