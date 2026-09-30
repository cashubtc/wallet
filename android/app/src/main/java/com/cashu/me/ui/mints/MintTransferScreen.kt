package com.cashu.me.ui.mints

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.Core.MintTransferEligibility
import com.cashu.me.Core.MintTransferEntry
import com.cashu.me.Core.MintTransferRoute
import com.cashu.me.Core.MintTransferRoute.Slot
import com.cashu.me.Core.SettingsManager
import com.cashu.me.Core.PriceService
import com.cashu.me.Core.Wallet.WalletMessageSeverity
import com.cashu.me.Core.Wallet.isInsufficientBalance
import com.cashu.me.Core.Wallet.userFacingWalletMessage
import com.cashu.me.Core.Wallet.walletMessage
import com.cashu.me.Core.CDK.mintRemovalUrlsMatch
import com.cashu.me.Core.WalletManager
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.MintTransferException
import com.cashu.me.Models.MintTransferOutcome
import com.cashu.me.Models.MintTransferPlan
import com.cashu.me.ui.components.AmountFlipDisplay
import com.cashu.me.ui.components.EmptyState
import com.cashu.me.ui.components.EmptyStateSize
import com.cashu.me.ui.components.FlowSheetTitle
import com.cashu.me.ui.components.InlineNotice
import com.cashu.me.ui.components.InspectorRow
import com.cashu.me.ui.components.NoticeSeverity
import com.cashu.me.ui.components.NumberPadFooter
import com.cashu.me.ui.components.PaymentStatusPhase
import com.cashu.me.ui.components.PaymentStatusScreen
import com.cashu.me.ui.components.SecondaryButton
import com.cashu.me.ui.components.SheetHeader
import com.cashu.me.ui.components.SpinnerRing
import com.cashu.me.ui.components.TwoFaceScreen
import com.cashu.me.ui.components.numberPadFooterMinimumHeight
import com.cashu.me.ui.send.ConfirmCautionFace
import com.cashu.me.ui.send.ConfirmGlyphSize
import com.cashu.me.ui.send.ConfirmHeroMinHeight
import com.cashu.me.ui.send.ConfirmTopFraction
import com.cashu.me.ui.send.PaymentConfirmationAmount
import com.cashu.me.ui.send.UnifiedSendAmountEntry
import com.cashu.me.ui.testing.UiTestTags
import com.cashu.me.ui.theme.AmountScale
import com.cashu.me.ui.theme.CashuTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private enum class TransferStep { Entry, Review, Status }

private data class EntryNotice(val text: String, val severity: NoticeSeverity)

private data class ReviewFailure(
    val message: String,
    val detail: String?,
    /** A shortfall is fixed by a smaller amount, never by asking again. */
    val isShortfall: Boolean,
)

private sealed interface TransferStatus {
    data object Transferring : TransferStatus
    data class Done(val outcome: MintTransferOutcome) : TransferStatus
    data class Failed(val text: String, val isTerminal: Boolean) : TransferStatus
}

/**
 * Move ecash from one held mint to another: choose the two mints and an
 * amount, review the fee, transfer (iOS `MintTransferView` parity). The amount
 * is what arrives; the source pays it plus the network fee.
 */
@Composable
fun MintTransferScreen(
    walletManager: WalletManager,
    settingsManager: SettingsManager,
    priceService: PriceService,
    onClose: () -> Unit,
    openedFromMintUrl: String? = null,
    onDismissLockChanged: (Boolean) -> Unit = {},
) {
    val walletState by walletManager.state.collectAsState()
    val settings by settingsManager.state.collectAsState()
    val priceState by priceService.state.collectAsState()
    val formatter = remember { AmountFormatter() }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current

    var step by remember { mutableStateOf(TransferStep.Entry) }
    var route by remember { mutableStateOf<MintTransferRoute?>(null) }
    var amount by remember { mutableStateOf("") }
    var picking by remember { mutableStateOf<Slot?>(null) }

    // The quote pair a Max tap produced. While it is held the amount field
    // shows its amount, and review reuses it rather than quoting again.
    var maxPlan by remember { mutableStateOf<MintTransferPlan?>(null) }
    var isFindingMax by remember { mutableStateOf(false) }
    var maxJob by remember { mutableStateOf<Job?>(null) }
    // Identifies the latest Max request, so a superseded one that returns late
    // cannot clear the spinner of the one that replaced it.
    var maxRequest by remember { mutableIntStateOf(0) }
    var entryNotice by remember { mutableStateOf<EntryNotice?>(null) }

    var plan by remember { mutableStateOf<MintTransferPlan?>(null) }
    var reviewFailure by remember { mutableStateOf<ReviewFailure?>(null) }
    var quoteJob by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf<TransferStatus>(TransferStatus.Transferring) }

    val mints = walletState.mints
    fun mint(url: String): MintInfo? = mints.firstOrNull { mintRemovalUrlsMatch(it.url, url) }
    fun mintName(url: String): String = mint(url)?.name ?: url

    LaunchedEffect(mints.size) {
        if (route == null) {
            route = MintTransferRoute.initial(
                mints = mints,
                activeMintUrl = walletState.activeMint?.url,
                openedFromMintUrl = openedFromMintUrl,
            )
        }
    }

    val entryFiatPrice = priceState.btcPrice.takeIf { settings.showFiatBalance && it > 0 }
    val entryContext = UnifiedSendAmountEntry.context(
        preferredPrimary = settings.amountDisplayPrimary,
        btcPrice = entryFiatPrice ?: 0.0,
    )
    var previousEntryContext by remember { mutableStateOf(entryContext) }
    LaunchedEffect(entryContext.primary, entryContext.btcPrice) {
        if (previousEntryContext.primary != entryContext.primary) {
            amount = UnifiedSendAmountEntry.convert(amount, previousEntryContext, entryContext)
        }
        previousEntryContext = entryContext
    }

    val sourceMint = route?.let { mint(it.sourceMintUrl) }
    val destinationMint = route?.let { mint(it.destinationMintUrl) }
    // A Max amount is the quote's, exactly. Fiat entry is too coarse to carry
    // it through the keypad string.
    val amountSats = maxPlan?.amount ?: UnifiedSendAmountEntry.amountSats(amount, entryContext)
    val entryState = if (sourceMint != null && destinationMint != null) {
        MintTransferEntry.validation(amountSats, sourceMint, destinationMint)
    } else {
        MintTransferEntry.Empty
    }
    // With only two mints there is nothing to pick: the arrow is the control.
    val canChooseMint = mints.size > 2
    val canSwap = sourceMint != null && destinationMint != null &&
        MintTransferEligibility.eligible(source = destinationMint, destination = sourceMint)

    fun sats(value: Long) = formatter.formatWalletSats(value, settings.useBitcoinSymbol)

    // Quotes that were never executed exist at both mints; tell the wallet to
    // forget them. Committed plans are untouched by a discard. Runs on a scope
    // of its own because this composable's is already cancelled when it leaves.
    fun discard(abandoned: MintTransferPlan) {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            walletManager.discardMintTransferPlan(abandoned)
        }
    }

    fun dropMaxPlan() {
        val abandoned = maxPlan ?: return
        maxPlan = null
        discard(abandoned)
    }

    val latestPlan by rememberUpdatedState(plan)
    val latestMaxPlan by rememberUpdatedState(maxPlan)
    DisposableEffect(Unit) {
        onDispose {
            listOfNotNull(latestPlan, latestMaxPlan).distinctBy { it.id }.forEach(::discard)
        }
    }

    val processing = step == TransferStep.Status && status is TransferStatus.Transferring
    LaunchedEffect(processing) { onDismissLockChanged(processing) }
    // Swallow back only while money moves; otherwise back abandons to the wallet.
    BackHandler(enabled = processing) {}

    fun changeRoute(newRoute: MintTransferRoute) {
        if (newRoute == route) return
        maxJob?.cancel()
        maxRequest += 1
        isFindingMax = false
        // A Max quote was sized for the old pair; a typed amount is kept.
        if (maxPlan != null) {
            dropMaxPlan()
            amount = ""
        }
        entryNotice = null
        route = newRoute
    }

    fun swap() {
        val current = route ?: return
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
        changeRoute(current.swapped)
    }

    fun useMax() {
        val current = route ?: return
        if (isFindingMax) return
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        dropMaxPlan()
        entryNotice = null
        isFindingMax = true
        maxRequest += 1
        val request = maxRequest
        maxJob = scope.launch {
            try {
                val quoted = walletManager.prepareMaxMintTransfer(current.sourceMintUrl, current.destinationMintUrl)
                // The native quote cannot be cancelled. If the mints changed or
                // the sheet closed while it ran, the result is not wanted.
                if (route != current) {
                    discard(quoted)
                    return@launch
                }
                maxPlan = quoted
                amount = UnifiedSendAmountEntry.rawForSats(quoted.amount, entryContext)
                val balance = mint(current.sourceMintUrl)?.balance
                if (balance != null && quoted.amount < balance) {
                    entryNotice = EntryNotice("Amount adjusted for fees and mint limits.", NoticeSeverity.Info)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val message = error.walletMessage
                entryNotice = EntryNotice(message.text, message.severity.toNoticeSeverity())
            } finally {
                if (maxRequest == request) isFindingMax = false
            }
        }
    }

    fun failure(error: Throwable): ReviewFailure {
        if (error is MintTransferException.InsufficientBalance) {
            return ReviewFailure(
                message = "Not enough balance.",
                detail = "This mint holds ${sats(error.available)}; " +
                    "the transfer reserves up to ${sats(error.required)}.",
                isShortfall = true,
            )
        }
        return ReviewFailure(
            message = error.userFacingWalletMessage,
            detail = null,
            isShortfall = error.isInsufficientBalance,
        )
    }

    fun requestQuote(current: MintTransferRoute, quoteAmount: Long) {
        quoteJob?.cancel()
        plan = null
        reviewFailure = null
        quoteJob = scope.launch {
            try {
                val quoted = walletManager.prepareMintTransfer(
                    current.sourceMintUrl,
                    current.destinationMintUrl,
                    quoteAmount,
                )
                if (step != TransferStep.Review) {
                    discard(quoted)
                    return@launch
                }
                plan = quoted
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                reviewFailure = failure(error)
            }
        }
    }

    fun review() {
        val current = route ?: return
        if (entryState != MintTransferEntry.Ready) return
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        reviewFailure = null
        val held = maxPlan
        if (held != null && !held.isExpired()) {
            // Already quoted: nothing to wait for.
            plan = held
            step = TransferStep.Review
            return
        }
        // Read before the Max quote is dropped: the amount is its amount.
        val quoteAmount = amountSats
        dropMaxPlan()
        plan = null
        step = TransferStep.Review
        requestQuote(current, quoteAmount)
    }

    fun backToEntry() {
        quoteJob?.cancel()
        // A Max quote stays held so Continue is instant again; a typed one is
        // specific to this visit.
        plan?.takeIf { it.id != maxPlan?.id }?.let(::discard)
        plan = null
        reviewFailure = null
        step = TransferStep.Entry
    }

    fun transfer() {
        val current = plan ?: return
        val currentRoute = route ?: return
        if (current.isExpired()) {
            // The quotes lapsed while the review was open. Quote again and show
            // the fee that now applies instead of failing the commit.
            if (maxPlan?.id == current.id) maxPlan = null
            discard(current)
            requestQuote(currentRoute, current.amount)
            return
        }
        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
        // The plan is spent whatever happens next.
        if (maxPlan?.id == current.id) maxPlan = null
        status = TransferStatus.Transferring
        step = TransferStep.Status
        scope.launch {
            status = try {
                TransferStatus.Done(walletManager.executeMintTransfer(current))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                val message = error.walletMessage
                TransferStatus.Failed(message.text, message.isTerminal)
            }
        }
    }

    // The failed plan's quotes are gone or spoken for, so trying again means
    // quoting again. A typed amount can be re-quoted as it stands; a maximum
    // has to be found afresh.
    fun retryTransfer() {
        val failed = plan ?: return
        val currentRoute = route ?: return
        plan = null
        if (failed.mode == MintTransferPlan.Mode.Max) {
            amount = ""
            step = TransferStep.Entry
        } else {
            step = TransferStep.Review
            requestQuote(currentRoute, failed.amount)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .testTag(UiTestTags.MintTransferScreen),
    ) {
        if (step == TransferStep.Status) {
            FlowSheetTitle(title = "Transfer")
        } else if (step == TransferStep.Review) {
            SheetHeader(
                title = "Transfer",
                navigationIcon = Icons.AutoMirrored.Outlined.ArrowBack,
                navigationContentDescription = "Back",
                onNavigationClick = ::backToEntry,
            )
        } else {
            FlowSheetTitle(title = "Transfer")
        }
        TwoFaceScreen(
            targetState = step,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            forward = { initial, target -> target.ordinal >= initial.ordinal },
            label = "mint-transfer-step",
        ) { current ->
            when (current) {
                TransferStep.Entry -> when {
                    mints.size < 2 -> EmptyState(
                        title = "Add another mint",
                        supporting = "A transfer moves ecash between two of your mints.",
                        icon = Icons.Outlined.SwapHoriz,
                        size = EmptyStateSize.FullScreen,
                    )
                    mints.all { it.balance == 0L } -> EmptyState(
                        title = "Nothing to transfer yet",
                        supporting = "Receive some ecash before you can move it between mints.",
                        icon = Icons.Outlined.SwapHoriz,
                        size = EmptyStateSize.FullScreen,
                    )
                    sourceMint != null && destinationMint != null -> EntryFace(
                        source = sourceMint,
                        destination = destinationMint,
                        amount = amount,
                        onAmountChange = {
                            amount = it
                            dropMaxPlan()
                            entryNotice = null
                        },
                        amountSats = amountSats,
                        entryPrimary = entryContext.primary,
                        onFlipEntryPrimary = { settingsManager.setAmountDisplayPrimary(it.rawValue) },
                        btcPrice = entryFiatPrice,
                        fiatCurrencyCode = priceState.currencyCode,
                        useBitcoinSymbol = settings.useBitcoinSymbol,
                        entryState = entryState,
                        notice = entryNotice,
                        sourceBalanceText = sats(sourceMint.balance),
                        destinationBalanceText = sats(destinationMint.balance),
                        isFindingMax = isFindingMax,
                        // Gated on a spendable balance: an empty mint has no maximum.
                        onUseMax = ::useMax.takeIf {
                            sourceMint.balance > 0 &&
                                entryState != MintTransferEntry.Blocked(MintTransferEligibility.Blocker.SourceCannotSend)
                        },
                        onChooseSource = { picking = Slot.Source }.takeIf { canChooseMint },
                        onChooseDestination = { picking = Slot.Destination }.takeIf { canChooseMint },
                        onSwap = ::swap.takeIf { canSwap },
                        onContinue = ::review,
                    )
                }

                TransferStep.Review -> ReviewFace(
                    plan = plan,
                    failure = reviewFailure,
                    mintName = ::mintName,
                    formatter = formatter,
                    useBitcoinSymbol = settings.useBitcoinSymbol,
                    preferredPrimary = settings.amountDisplayPrimary,
                    showFiat = settings.showFiatBalance,
                    btcPrice = priceState.btcPrice,
                    currencyCode = priceState.currencyCode,
                    onChangeAmount = ::backToEntry,
                    onRetryQuote = { route?.let { requestQuote(it, amountSats) } },
                    onTransfer = ::transfer,
                )

                TransferStep.Status -> StatusFace(
                    status = status,
                    plan = plan,
                    mintName = ::mintName,
                    formatter = formatter,
                    useBitcoinSymbol = settings.useBitcoinSymbol,
                    onDone = onClose,
                    onRetry = ::retryTransfer,
                )
            }
        }
    }

    picking?.let { slot ->
        val options = mints.map { mint ->
            MintTransferPickerOption(
                mint = mint,
                balanceText = sats(mint.balance),
                unavailableReason = when (slot) {
                    Slot.Source ->
                        if (MintTransferEligibility.canSend(mint)) null else "Can't send over Lightning"
                    Slot.Destination ->
                        if (MintTransferEligibility.canReceive(mint)) null else "Can't receive over Lightning"
                },
            )
        }
        MintTransferMintPicker(
            slot = slot,
            options = options,
            selectedMintUrl = when (slot) {
                Slot.Source -> route?.sourceMintUrl
                Slot.Destination -> route?.destinationMintUrl
            }.orEmpty(),
            onSelect = { picked -> route?.let { changeRoute(it.choosing(picked.url, slot)) } },
            onDismiss = { picking = null },
        )
    }
}

private fun WalletMessageSeverity.toNoticeSeverity(): NoticeSeverity = when (this) {
    WalletMessageSeverity.Error -> NoticeSeverity.Error
    WalletMessageSeverity.Caution -> NoticeSeverity.Caution
    WalletMessageSeverity.Info -> NoticeSeverity.Info
}

@Composable
private fun EntryFace(
    source: MintInfo,
    destination: MintInfo,
    amount: String,
    onAmountChange: (String) -> Unit,
    amountSats: Long,
    entryPrimary: com.cashu.me.Core.AmountDisplayPrimary,
    onFlipEntryPrimary: (com.cashu.me.Core.AmountDisplayPrimary) -> Unit,
    btcPrice: Double?,
    fiatCurrencyCode: String,
    useBitcoinSymbol: Boolean,
    entryState: MintTransferEntry,
    notice: EntryNotice?,
    sourceBalanceText: String,
    destinationBalanceText: String,
    isFindingMax: Boolean,
    onUseMax: (() -> Unit)?,
    onChooseSource: (() -> Unit)?,
    onChooseDestination: (() -> Unit)?,
    onSwap: (() -> Unit)?,
    onContinue: () -> Unit,
) {
    val overBalance = entryState == MintTransferEntry.OverBalance
    val blockerText = (entryState as? MintTransferEntry.Blocked)?.let {
        when (it.blocker) {
            MintTransferEligibility.Blocker.SameMint -> null
            MintTransferEligibility.Blocker.SourceCannotSend -> "${source.name} can't send over Lightning."
            MintTransferEligibility.Blocker.DestinationCannotReceive ->
                "${destination.name} can't receive over Lightning."
        }
    }
    val noticeText: String?
    val noticeSeverity: NoticeSeverity
    when {
        // The source row states what is available, so the notice does not repeat it.
        overBalance -> { noticeText = "Insufficient balance"; noticeSeverity = NoticeSeverity.Caution }
        blockerText != null -> { noticeText = blockerText; noticeSeverity = NoticeSeverity.Caution }
        notice != null -> { noticeText = notice.text; noticeSeverity = notice.severity }
        else -> { noticeText = null; noticeSeverity = NoticeSeverity.Info }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // The pad and the two mints are fixed; the amount takes what is left.
        // On a short screen the destination gives up its balance line and the
        // amount steps down a rung so none of them crowd each other.
        val room = maxHeight - numberPadFooterMinimumHeight()
        val showsDestinationBalance = room >= 360.dp
        val heroRoom = room - if (showsDestinationBalance) 200.dp else 176.dp
        val heroScale = when {
            heroRoom >= 160.dp -> AmountScale.Hero
            heroRoom >= 110.dp -> AmountScale.Confirm
            else -> AmountScale.Compact
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = CashuTheme.spacing.comfortable),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentAlignment = Alignment.Center,
            ) {
                AmountFlipDisplay(
                    amountSats = amountSats,
                    primary = entryPrimary,
                    onFlip = onFlipEntryPrimary,
                    btcPrice = btcPrice,
                    currencyCode = fiatCurrencyCode,
                    useBitcoinSymbol = useBitcoinSymbol,
                    entryRaw = amount,
                    entryScale = heroScale,
                    primaryAccessibilityPrefix = "Transfer amount",
                    color = if (overBalance) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(bottom = CashuTheme.spacing.default),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AnimatedVisibility(
                        visible = noticeText != null,
                        enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)),
                        exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)),
                    ) {
                        InlineNotice(
                            text = noticeText.orEmpty(),
                            detail = null,
                            severity = noticeSeverity,
                            showsContainer = false,
                            centered = true,
                        )
                    }
                }
            }
            MintTransferRouteBlock(
                source = source,
                destination = destination,
                sourceBalanceText = sourceBalanceText,
                destinationBalanceText = destinationBalanceText,
                showsDestinationBalance = showsDestinationBalance,
                isFindingMax = isFindingMax,
                onUseMax = onUseMax,
                onChooseSource = onChooseSource,
                onChooseDestination = onChooseDestination,
                onSwap = onSwap,
            )
            Spacer(Modifier.height(CashuTheme.spacing.snug))
            NumberPadFooter(
                amount = amount,
                onAmountChange = onAmountChange,
                buttonText = "Continue",
                onButtonClick = onContinue,
                decimals = if (entryPrimary == com.cashu.me.Core.AmountDisplayPrimary.Fiat) 2 else 0,
                buttonEnabled = entryState == MintTransferEntry.Ready && !isFindingMax,
                buttonModifier = Modifier.testTag(UiTestTags.MintTransferContinue),
            )
        }
    }
}

@Composable
private fun ReviewFace(
    plan: MintTransferPlan?,
    failure: ReviewFailure?,
    mintName: (String) -> String,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
    preferredPrimary: String,
    showFiat: Boolean,
    btcPrice: Double,
    currencyCode: String,
    onChangeAmount: () -> Unit,
    onRetryQuote: () -> Unit,
    onTransfer: () -> Unit,
) {
    val quotePending = plan == null && failure == null
    // Same skeleton as the status terminal: fixed top fraction, a hero band
    // that swaps amount / spinner / caution face in place, rows beneath, and
    // the CTA pinned at the bottom.
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val scaffoldHeight = maxHeight
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = CashuTheme.spacing.comfortable),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(scaffoldHeight * ConfirmTopFraction))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = ConfirmHeroMinHeight),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    when {
                        failure != null -> ConfirmCautionFace(message = failure.message, detail = failure.detail)
                        plan != null -> PaymentConfirmationAmount(
                            amount = plan.amount,
                            unit = "sat",
                            preferredPrimary = preferredPrimary,
                            showFiat = showFiat,
                            btcPrice = btcPrice,
                            currencyCode = currencyCode,
                            useBitcoinSymbol = useBitcoinSymbol,
                            formatter = formatter,
                        )
                        else -> SpinnerRing(
                            size = ConfirmGlyphSize,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (plan != null && failure == null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = CashuTheme.spacing.comfortable),
                    ) {
                        InspectorRow(label = "From", value = mintName(plan.sourceMintUrl))
                        InspectorRow(label = "To", value = mintName(plan.destinationMintUrl))
                        InspectorRow(
                            label = "Network fee",
                            value = formatter.formatWalletSats(plan.feeUpperBound, useBitcoinSymbol),
                            valueMonospaced = true,
                        )
                        InspectorRow(
                            label = "Total",
                            value = formatter.formatWalletSats(plan.total, useBitcoinSymbol),
                            valueMonospaced = true,
                        )
                    }
                }
            }
            when {
                // The hero spinner owns the wait; the button's footprint is
                // reserved so nothing moves when the quote lands.
                quotePending -> SecondaryButton(
                    text = " ",
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.graphicsLayer { alpha = 0f }.clearAndSetSemantics {},
                )
                failure != null && failure.isShortfall -> SecondaryButton(
                    text = "Change Amount",
                    onClick = onChangeAmount,
                )
                failure != null -> SecondaryButton(text = "Retry Quote", onClick = onRetryQuote)
                else -> SecondaryButton(
                    text = "Transfer",
                    onClick = onTransfer,
                    modifier = Modifier.testTag(UiTestTags.MintTransferSubmit),
                )
            }
            Spacer(Modifier.height(CashuTheme.spacing.comfortable))
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}

@Composable
private fun StatusFace(
    status: TransferStatus,
    plan: MintTransferPlan?,
    mintName: (String) -> String,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
    onDone: () -> Unit,
    onRetry: () -> Unit,
) {
    val outcome = (status as? TransferStatus.Done)?.outcome
    val settling = outcome as? MintTransferOutcome.Settling
    val failed = status as? TransferStatus.Failed
    val settlingDetail = when (settling?.leg) {
        MintTransferOutcome.Leg.Issuance -> {
            val name = plan?.let { mintName(it.destinationMintUrl) } ?: "The mint"
            "Payment sent. $name is still issuing your ecash; it will arrive automatically."
        }
        MintTransferOutcome.Leg.Payment -> "The payment is still settling. Your funds are safe."
        null -> null
    }
    val successAmount = if (outcome is MintTransferOutcome.Completed) {
        formatter.formatWalletSats(outcome.amount, useBitcoinSymbol)
    } else {
        null
    }
    PaymentStatusScreen(
        phase = when (status) {
            TransferStatus.Transferring -> PaymentStatusPhase.Processing
            is TransferStatus.Done -> PaymentStatusPhase.Success
            is TransferStatus.Failed -> PaymentStatusPhase.Failure
        },
        title = when (status) {
            TransferStatus.Transferring -> "Transferring…"
            is TransferStatus.Done -> if (settling != null) "Transfer Processing" else "Transfer Complete"
            is TransferStatus.Failed -> "Transfer Failed"
        },
        detail = failed?.text ?: settlingDetail,
        settlementPending = settling != null,
        successAmount = successAmount,
        // A terminal outcome can't be retried; anything else re-quotes.
        doneLabel = if (failed != null && !failed.isTerminal) "Try Again" else "Done",
        onDone = when (status) {
            TransferStatus.Transferring -> null
            is TransferStatus.Done -> onDone
            is TransferStatus.Failed -> {
                { if (status.isTerminal) onDone() else onRetry() }
            }
        },
        showRowsDuringProcessing = true,
        rows = plan?.let { shown ->
            {
                if (successAmount == null) {
                    InspectorRow(
                        label = "Amount",
                        value = formatter.formatWalletSats(shown.amount, useBitcoinSymbol),
                        valueMonospaced = true,
                    )
                }
                InspectorRow(label = "From", value = mintName(shown.sourceMintUrl))
                InspectorRow(label = "To", value = mintName(shown.destinationMintUrl))
                if (outcome is MintTransferOutcome.Completed) {
                    // A receipt records what happened; a zero fee is omitted.
                    if (outcome.feePaid > 0) {
                        InspectorRow(
                            label = "Network fee",
                            value = formatter.formatWalletSats(outcome.feePaid, useBitcoinSymbol),
                            valueMonospaced = true,
                        )
                    }
                } else {
                    InspectorRow(
                        label = "Network fee",
                        value = formatter.formatWalletSats(shown.feeUpperBound, useBitcoinSymbol),
                        valueMonospaced = true,
                    )
                }
            }
        },
    )
}
