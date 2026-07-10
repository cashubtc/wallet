package org.cashu.wallet.ui.receive

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.QrCodeScanner
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
import kotlinx.coroutines.launch
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.TokenParser
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.CashuTextField
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.NoticeSeverity
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.SecondaryButton
import org.cashu.wallet.ui.components.SheetHeader
import org.cashu.wallet.ui.components.TwoFaceScreen
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

private sealed interface ReceiveFace {
    data object Paste : ReceiveFace
    data class Review(val review: TokenReview) : ReceiveFace
}

// Floor for the pinned stage height (review face and claim terminal): enough
// room for the glyph + title + failure copy + the bottom-anchored Done button,
// in case the paste face measured unusually short.
private val MinStatusHeight = 360.dp

@OptIn(ExperimentalLayoutApi::class)
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
    var status by remember { mutableStateOf<TokenClaimStatus?>(null) }
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
        val parsed = when (val outcome = parseToken(raw)) {
            is TokenParseOutcome.Invalid -> {
                errorText = outcome.message
                return
            }
            is TokenParseOutcome.Ok -> outcome
        }
        validating = true
        scope.launch {
            try {
                val review = tokenReviewDetails(
                    token = parsed.token,
                    info = parsed.info,
                    walletManager = walletManager,
                    settingsManager = settingsManager,
                )
                face = ReceiveFace.Review(review)
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
        onDismissLockChanged(validating || status == TokenClaimStatus.Claiming)
    }

    // System back unwinds Review → Paste; swallow it entirely while claiming.
    // On a terminal (success/failure) face, back falls through to the sheet.
    BackHandler(enabled = status == TokenClaimStatus.Claiming || (status == null && face is ReceiveFace.Review)) {
        if (status == null) {
            face = ReceiveFace.Paste
            errorText = null
        }
    }

    fun claim(review: TokenReview) {
        if (status != null) return
        status = TokenClaimStatus.Claiming
        scope.launch {
            status = claimToken(review, walletManager)
        }
    }

    // Stable stage: the sheet settles at the paste face's height and stays
    // there for the whole flow (review → claiming → terminal), so face swaps
    // read as content changes rather than the sheet morphing. iOS runs the
    // whole claim as a phase morph inside the `.medium`-detent sheet
    // (ReceiveTokenDetailView). We measure the wrap-content body only while
    // the paste face is up and the keyboard is closed — IME-inflated heights
    // must not become the pin.
    val density = LocalDensity.current
    var pasteBodyHeightPx by remember { mutableIntStateOf(0) }
    val imeVisible = WindowInsets.isImeVisible
    val pinnedBodyHeight = with(density) { pasteBodyHeightPx.toDp() }.coerceAtLeast(MinStatusHeight)
    val pinBody = status != null || face is ReceiveFace.Review
    Column(
        modifier = if (pinBody) {
            Modifier.fillMaxWidth().height(pinnedBodyHeight)
        } else {
            Modifier.fillMaxWidth().onSizeChanged {
                if (!imeVisible) pasteBodyHeightPx = it.height
            }
        },
    ) {
        when (val current = status) {
            // Claiming / success / failure: the shared terminal, pinned to the
            // height the paste face occupied (see comment above).
            is TokenClaimStatus -> Box(Modifier.weight(1f).fillMaxWidth()) {
                TokenClaimTerminal(
                    status = current,
                    formatter = formatter,
                    useBitcoinSymbol = settings.useBitcoinSymbol,
                    onDone = onClose,
                    onRetry = { status = null },
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
                // paste ↔ review reads as a lateral push/pop — not a fade.
                // The review face fills the pinned stage (weight is only safe
                // once the body height is fixed; in a wrap-content column it
                // would balloon the sheet to full height).
                TwoFaceScreen(
                    targetState = face,
                    modifier = if (pinBody) {
                        Modifier.fillMaxWidth().weight(1f)
                    } else {
                        Modifier.fillMaxWidth()
                    },
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
                            review = currentFace.review,
                            formatter = formatter,
                            useBitcoinSymbol = settings.useBitcoinSymbol,
                            onReceive = { claim(currentFace.review) },
                            onReceiveLater = {
                                walletManager.savePendingReceiveToken(
                                    pendingReceiveTokenFrom(currentFace.review),
                                )
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
    review: TokenReview,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
    onReceive: () -> Unit,
    onReceiveLater: () -> Unit,
) {
    val info = review.info
    // The face fills the pinned stage (paste-face height): details scroll in
    // the top region, CTAs anchor to the bottom — standard sheet ergonomics.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                horizontal = CashuTheme.spacing.comfortable,
                vertical = CashuTheme.spacing.comfortable,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
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
            val netAmount = info.amount - review.fee.coerceIn(0L, info.amount)
            AmountText(
                text = if (isSatToken) {
                    formatter.formatWalletSats(netAmount, useBitcoinSymbol)
                } else {
                    CurrencyAmount(netAmount, tokenCurrency).formatted()
                },
                style = MaterialTheme.typography.displayMedium.withMonoDigits(),
            )
            TokenInspectorRows(
                info = info,
                fee = review.fee,
                locked = review.locked,
            )
            if (!review.mintKnown) {
                InlineNotice(
                    text = "You haven’t used ${shortMintHost(info.mint)} before. Receiving adds it to your wallet — only continue if you trust it.",
                    severity = NoticeSeverity.Warning,
                )
            }
        }
        // Claim outcomes no longer land here: tapping Receive swaps the sheet
        // body to the PaymentStatusScreen terminal (success check / failure X).
        Spacer(modifier = Modifier.height(CashuTheme.spacing.snug))
        PrimaryButton(
            text = "Receive",
            onClick = onReceive,
            enabled = !review.locked,
        )
        GhostButton(
            text = "Receive later",
            onClick = onReceiveLater,
        )
        Spacer(modifier = Modifier.navigationBarsPadding())
    }
}
