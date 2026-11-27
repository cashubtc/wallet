package com.cashu.me.ui.send

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cashu.me.Core.PendingSend
import com.cashu.me.Core.PendingSendRecovery
import com.cashu.me.Core.Protocols.CurrencyAmount
import com.cashu.me.Core.Protocols.CurrencyRegistry
import com.cashu.me.Core.SendRecoveryOutcome
import com.cashu.me.Core.TransactionDisplay
import com.cashu.me.Core.Wallet.walletMessage
import com.cashu.me.Core.WalletManager
import com.cashu.me.Core.mintDisplayName
import com.cashu.me.ui.components.ActionConfirmationSheet
import com.cashu.me.ui.components.InlineNotice
import com.cashu.me.ui.components.InspectorRow
import com.cashu.me.ui.components.LocalConfirmationToastController
import com.cashu.me.ui.components.NoticeSeverity
import com.cashu.me.ui.components.PaymentStatusPhase
import com.cashu.me.ui.components.PaymentStatusScreen
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.components.QrCard
import com.cashu.me.ui.components.SecondaryButton
import com.cashu.me.ui.components.paymentDetailWidth
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.fadeThrough
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class SendRecoveryActivity { Idle, Checking, Reclaiming }

internal data class SendRecoveryNotice(
    val message: String,
    val severity: NoticeSeverity,
    val title: String? = null,
) {
    /** Good news is the app's confirmation toast; only a failure stays on screen. */
    val isToast: Boolean get() = severity != NoticeSeverity.Error
}

/** What a reclaim brought back, for the settled screen's amount and fee. */
internal data class ReclaimedEcash(val amount: Long, val fee: Long)

/** Which of the four layouts is up. Working and settled share one status screen. */
private enum class SendRecoveryFace { Tokens, Status, Unconfirmed }

// Exact geometry shared with PaymentStatusScreen, so the caution sits where
// the spinner and check do.
private const val StatusTopFraction = 0.16f
private val StatusHeroMinHeight = 220.dp

private val TokensTopPadding = 8.dp
private val TokensBottomPadding = 16.dp
private val TokensBlockSpacing = 20.dp
private val QrCardPadding = 16.dp
private val PageDotSize = 7.dp

/**
 * Ecash that left the wallet and has not been claimed. Shown when it holds
 * back a new send or Cashu Request payment from the same mint, and from
 * History's "Manage pending ecash".
 *
 * It is built from the screens on either side of it rather than its own
 * vocabulary. While a token is outstanding it is Send's Pending Ecash screen:
 * the code on its white card, the amount, the facts, and quiet actions pinned
 * underneath. Checking, reclaiming and every settled outcome are the shared
 * payment status screen.
 */
@Composable
internal fun PendingSendRecoveryContent(
    initial: PendingSendRecovery,
    walletManager: WalletManager,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onWorkingChanged: (Boolean) -> Unit = {},
) {
    val walletState by walletManager.state.collectAsState()
    var recovery by remember(initial) { mutableStateOf(initial) }
    // An unverified snapshot is checked on arrival, so it opens on the spinner
    // instead of flashing the caution it may never need.
    var activity by remember(initial) {
        mutableStateOf(
            if (initial.outcome == SendRecoveryOutcome.Unverified) SendRecoveryActivity.Checking
            else SendRecoveryActivity.Idle,
        )
    }
    var notice by remember(initial) { mutableStateOf<SendRecoveryNotice?>(null) }
    var reclaimed by remember(initial) { mutableStateOf<ReclaimedEcash?>(null) }
    var reclaimTarget by remember { mutableStateOf<PendingSend?>(null) }
    val working = activity != SendRecoveryActivity.Idle
    LaunchedEffect(working) { onWorkingChanged(working) }
    DisposableEffect(Unit) { onDispose { onWorkingChanged(false) } }
    BackHandler(enabled = working) {}
    val scope = rememberCoroutineScope()
    val toast = LocalConfirmationToastController.current

    fun resolve(send: PendingSend? = null) {
        activity = if (send == null) SendRecoveryActivity.Checking else SendRecoveryActivity.Reclaiming
        notice = null
        scope.launch {
            var snapshot = recovery
            var returned: ReclaimedEcash? = null
            var reclaimFailure: String? = null
            var checkFailed = false
            try {
                if (send != null) {
                    try {
                        val amount = walletManager.reclaimPendingSend(send.id, snapshot.mintUrl, snapshot.unit)
                        snapshot = PendingSendRecovery(
                            snapshot.mintUrl, snapshot.unit, snapshot.operationIds,
                            snapshot.sends.filterNot { it.id == send.id }, SendRecoveryOutcome.Unverified,
                        )
                        returned = ReclaimedEcash(amount, (send.amount - amount).coerceAtLeast(0))
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        reclaimFailure = error.walletMessage.text
                    }
                }
                try {
                    snapshot = walletManager.checkSendRecovery(snapshot)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    checkFailed = true
                }
                recovery = snapshot
                if (snapshot.outcome == SendRecoveryOutcome.Returned && returned != null) reclaimed = returned
                val result = sendRecoveryNotice(
                    snapshot = snapshot,
                    checkedOnly = send == null,
                    reclaimFailure = reclaimFailure,
                    checkFailed = checkFailed,
                    returned = returned,
                )
                if (result?.isToast == true) toast?.show(result.message) else notice = result
            } finally {
                activity = SendRecoveryActivity.Idle
            }
        }
    }
    LaunchedEffect(initial) { if (initial.outcome == SendRecoveryOutcome.Unverified) resolve() }

    PendingSendRecoveryLayout(
        recovery = recovery,
        activity = activity,
        notice = notice,
        reclaimed = reclaimed,
        mintName = mintDisplayName(recovery.mintUrl, walletState.mints),
        // History holds the send under its operation ID, which is all that
        // says when this ecash left.
        sentLabel = { id ->
            walletState.transactions.firstOrNull { it.sagaId == id }?.let {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it.dateEpochMillis))
            }
        },
        onCheck = { resolve() },
        onReclaim = { reclaimTarget = it },
        onDone = onDone,
        modifier = modifier,
    )

    reclaimTarget?.let { send ->
        ActionConfirmationSheet(
            title = "Reclaim this ecash?",
            message = "If it hasn't been claimed, it returns to your wallet and the recipient can no longer use this token. Mint fees may apply.",
            actionLabel = "Reclaim",
            onConfirm = {
                reclaimTarget = null
                resolve(send)
            },
            onDismiss = { reclaimTarget = null },
        )
    }
}

/** What to say after a check or reclaim that left ecash outstanding. */
internal fun sendRecoveryNotice(
    snapshot: PendingSendRecovery,
    checkedOnly: Boolean,
    reclaimFailure: String?,
    checkFailed: Boolean,
    returned: ReclaimedEcash?,
): SendRecoveryNotice? {
    val pending = snapshot.outcome == SendRecoveryOutcome.Pending
    return when {
        snapshot.outcome == SendRecoveryOutcome.Completed || snapshot.outcome == SendRecoveryOutcome.Returned -> null
        reclaimFailure != null -> SendRecoveryNotice(
            title = "Couldn't reclaim",
            message = reclaimFailure,
            severity = NoticeSeverity.Error,
        )
        checkFailed -> SendRecoveryNotice(
            title = "Couldn't check status",
            message = "Check your connection and try again.",
            severity = NoticeSeverity.Error,
        )
        returned != null && pending -> SendRecoveryNotice(
            message = "Reclaimed ${sendRecoveryAmount(returned.amount, snapshot.unit)}",
            severity = NoticeSeverity.Success,
        )
        checkedOnly && pending -> SendRecoveryNotice(
            message = "Not claimed yet",
            severity = NoticeSeverity.Info,
        )
        else -> null
    }
}

private fun sendRecoveryAmount(amount: Long, unit: String): String =
    CurrencyAmount(amount, CurrencyRegistry.currencyForMintUnit(unit)).formatted()

/** The screen without its wallet, so each face can be rendered from fixed state. */
@Composable
internal fun PendingSendRecoveryLayout(
    recovery: PendingSendRecovery,
    activity: SendRecoveryActivity,
    notice: SendRecoveryNotice?,
    reclaimed: ReclaimedEcash?,
    mintName: String,
    sentLabel: (String) -> String?,
    onCheck: () -> Unit,
    onReclaim: (PendingSend) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val working = activity != SendRecoveryActivity.Idle
    val settled = recovery.outcome == SendRecoveryOutcome.Completed || recovery.outcome == SendRecoveryOutcome.Returned
    val face = when {
        activity == SendRecoveryActivity.Reclaiming -> SendRecoveryFace.Status
        recovery.outcome == SendRecoveryOutcome.Pending && recovery.sends.isNotEmpty() -> SendRecoveryFace.Tokens
        settled || activity == SendRecoveryActivity.Checking -> SendRecoveryFace.Status
        else -> SendRecoveryFace.Unconfirmed
    }
    // The faces fade through each other. One key for the whole status face
    // keeps a single PaymentStatusScreen mounted from spinner to check.
    AnimatedContent(
        targetState = face,
        modifier = modifier.fillMaxSize(),
        transitionSpec = { fadeThrough() },
        label = "send-recovery-face",
    ) { current ->
        when (current) {
            SendRecoveryFace.Tokens -> TokensFace(
                recovery = recovery,
                checking = activity == SendRecoveryActivity.Checking,
                working = working,
                notice = notice,
                mintName = mintName,
                sentLabel = sentLabel,
                onCheck = onCheck,
                onReclaim = onReclaim,
            )
            SendRecoveryFace.Status -> PaymentStatusScreen(
                phase = if (settled && !working) PaymentStatusPhase.Success else PaymentStatusPhase.Processing,
                title = when {
                    activity == SendRecoveryActivity.Reclaiming -> "Reclaiming…"
                    working || !settled -> "Checking…"
                    recovery.outcome == SendRecoveryOutcome.Completed -> "Payment complete"
                    reclaimed != null -> "Ecash reclaimed"
                    else -> "No pending ecash"
                },
                successAmount = reclaimed
                    ?.takeIf { recovery.outcome == SendRecoveryOutcome.Returned }
                    ?.let { sendRecoveryAmount(it.amount, recovery.unit) },
                onDone = onDone,
                rows = {
                    reclaimed
                        ?.takeIf { recovery.outcome == SendRecoveryOutcome.Returned && it.fee > 0 }
                        ?.let {
                            InspectorRow(
                                label = "Fee",
                                value = sendRecoveryAmount(it.fee, recovery.unit),
                                valueMonospaced = true,
                            )
                        }
                    InspectorRow(label = "Mint", value = mintName)
                },
            )
            SendRecoveryFace.Unconfirmed -> UnconfirmedFace(
                detail = notice?.message
                    ?: "The wallet couldn't confirm what happened to this ecash. Check again before sending from this mint.",
                mintName = mintName,
                onCheck = onCheck,
            )
        }
    }
}

@Composable
private fun TokensFace(
    recovery: PendingSendRecovery,
    checking: Boolean,
    working: Boolean,
    notice: SendRecoveryNotice?,
    mintName: String,
    sentLabel: (String) -> String?,
    onCheck: () -> Unit,
    onReclaim: (PendingSend) -> Unit,
) {
    val several = recovery.sends.size > 1
    val density = LocalDensity.current
    val scrollState = rememberScrollState()
    // Several tokens out are shown one at a time, swiped sideways. The
    // actions below act on the token on show.
    val pagerState = rememberPagerState { recovery.sends.size }
    val shown = recovery.sends.getOrNull(pagerState.currentPage) ?: recovery.sends.firstOrNull()
    // Measured heights of what surrounds the code on show, so the code can
    // give way to them on a short window.
    var headerHeight by remember { mutableIntStateOf(0) }
    var factsHeight by remember { mutableIntStateOf(0) }
    // A failed check or reclaim lands under the facts. Where the code can't give
    // way for it (several tokens, a short window), it is brought into view
    // once it is laid out.
    LaunchedEffect(notice) {
        if (notice != null) {
            withFrameNanos { }
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }
    // Scroll region + pinned actions, as on Send's Pending Ecash screen.
    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // 280dp of code inside the white card, as on Send and Receive. It
            // gives up width before it meets the window edge, and height so
            // the amount and facts stay on screen, the way
            // PaymentDetailContent fits a receipt's code to its rows.
            val widest = minOf(280.dp, maxWidth - 64.dp)
            val dots = if (several) PageDotSize + TokensBlockSpacing else 0.dp
            val around = with(density) { (headerHeight + factsHeight).toDp() } + dots +
                TokensTopPadding + TokensBottomPadding + TokensBlockSpacing * 2 + QrCardPadding * 2
            val fitted = minOf(widest, maxHeight - around).coerceAtLeast(120.dp)
            // The first measurement places the code. A later one is a check
            // failure notice arriving, and the code makes room in step.
            var placed by remember { mutableStateOf(false) }
            LaunchedEffect(factsHeight) {
                if (factsHeight > 0 && !placed) {
                    withFrameNanos { }
                    placed = true
                }
            }
            val qrSize by animateDpAsState(
                targetValue = fitted,
                animationSpec = if (placed) tween(durationMillis = 220, easing = FastOutSlowInEasing) else snap(),
                label = "pending-ecash-code",
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(top = TokensTopPadding, bottom = TokensBottomPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(TokensBlockSpacing),
            ) {
                Column(
                    modifier = Modifier
                        .padding(horizontal = CashuTheme.spacing.comfortable + CashuTheme.spacing.default)
                        .onSizeChanged { headerHeight = it.height },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
                ) {
                    Text(
                        text = "Pending ecash",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = if (several) "You can send from this mint again once these are claimed or reclaimed."
                        else "You can send from this mint again once this ecash is claimed or reclaimed.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                if (several) {
                    // The dots sit still just under the code, the part that
                    // visibly changes, in the room each page leaves for them.
                    Box {
                        HorizontalPager(
                            state = pagerState,
                            verticalAlignment = Alignment.Top,
                            key = { recovery.sends[it].id },
                        ) { index ->
                            val send = recovery.sends[index]
                            TokenBlock(
                                send = send,
                                unit = recovery.unit,
                                qrSize = qrSize,
                                sent = sentLabel(send.id),
                                mintName = mintName,
                                notice = notice.takeIf { send.id == shown?.id },
                                onFactsMeasured = { if (send.id == shown?.id) factsHeight = it },
                                dotsRoom = true,
                                modifier = Modifier
                                    .padding(horizontal = CashuTheme.spacing.comfortable)
                                    .semantics { contentDescription = "Token ${index + 1} of ${recovery.sends.size}" },
                            )
                        }
                        PageDots(
                            count = recovery.sends.size,
                            current = pagerState.currentPage,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(
                                    top = if (shown?.token == null) 0.dp
                                    else qrSize + QrCardPadding * 2 + TokensBlockSpacing,
                                ),
                        )
                    }
                } else if (shown != null) {
                    TokenBlock(
                        send = shown,
                        unit = recovery.unit,
                        qrSize = qrSize,
                        sent = sentLabel(shown.id),
                        mintName = mintName,
                        notice = notice,
                        onFactsMeasured = { factsHeight = it },
                        modifier = Modifier.padding(horizontal = CashuTheme.spacing.comfortable),
                    )
                }
            }
        }
        Column(
            modifier = Modifier
                .padding(horizontal = CashuTheme.spacing.comfortable)
                .navigationBarsPadding()
                .padding(top = CashuTheme.spacing.micro, bottom = CashuTheme.spacing.comfortable),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            val checkLabel = if (checking) "Checking…" else "Check Status"
            val reclaim = { shown?.let(onReclaim) ?: Unit }
            // Stacked on a narrow window or at large text, as the
            // confirmation sheet's pair is.
            val fontScale = LocalDensity.current.fontScale
            BoxWithConstraints {
                if (maxWidth < 320.dp || fontScale >= 1.3f) {
                    Column(verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug)) {
                        SecondaryButton(
                            text = checkLabel, onClick = onCheck, enabled = !working, compact = true,
                            modifier = Modifier.testTag("cashu.send.check-recovery"),
                        )
                        SecondaryButton(
                            text = "Reclaim", onClick = reclaim, enabled = !working && shown != null, compact = true,
                            modifier = Modifier.testTag("cashu.send.reclaim"),
                        )
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug)) {
                        SecondaryButton(
                            text = checkLabel, onClick = onCheck, enabled = !working, compact = true,
                            modifier = Modifier.weight(1f).testTag("cashu.send.check-recovery"),
                        )
                        SecondaryButton(
                            text = "Reclaim", onClick = reclaim, enabled = !working && shown != null, compact = true,
                            modifier = Modifier.weight(1f).testTag("cashu.send.reclaim"),
                        )
                    }
                }
            }
        }
    }
}

/** A dot per token under the pager, the one on show filled. */
@Composable
private fun PageDots(count: Int, current: Int, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.clearAndSetSemantics {},
    ) {
        repeat(count) { index ->
            val color by animateColorAsState(
                targetValue = if (index == current) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                animationSpec = tween(durationMillis = 200),
                label = "pending-ecash-page-dot",
            )
            Box(Modifier.size(PageDotSize).background(color, CircleShape))
        }
    }
}

@Composable
private fun TokenBlock(
    send: PendingSend,
    unit: String,
    qrSize: Dp,
    sent: String?,
    mintName: String,
    notice: SendRecoveryNotice?,
    onFactsMeasured: (Int) -> Unit,
    modifier: Modifier = Modifier,
    dotsRoom: Boolean = false,
) {
    val amount = sendRecoveryAmount(send.amount, unit)
    val clipboard = LocalClipboard.current
    val confirmationToastController = LocalConfirmationToastController.current
    val scope = rememberCoroutineScope()
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(TokensBlockSpacing),
    ) {
        if (send.token != null) {
            QrCard(
                content = send.token,
                size = qrSize,
                shareSubject = "Cashu token",
                confirmationMessage = "Copied ecash token",
            )
        }
        if (dotsRoom) {
            // Room for the page dots, which sit still over the pager.
            Spacer(Modifier.height(PageDotSize))
        }
        Column(
            modifier = Modifier.fillMaxWidth().onSizeChanged { onFactsMeasured(it.height) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TokensBlockSpacing),
        ) {
            PaymentConfirmationAmount(
                presentation = PaymentConfirmationAmountPresentation(
                    primary = amount,
                    alternate = null,
                    talkBackDescription = "Amount, $amount",
                ),
                generatedReceipt = true,
            )
            if (send.token == null) {
                Text(
                    text = "This token can't be shown, but you can still check its status or reclaim it.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = CashuTheme.spacing.default),
                )
            }
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                if (sent != null) {
                    InspectorRow(label = "Date", value = sent)
                }
                InspectorRow(label = "Mint", value = mintName)
                if (send.token != null) {
                    // The receipts' way with an opaque value: the fixed short
                    // cut, and a tap copies all of it.
                    InspectorRow(
                        label = "Token",
                        value = TransactionDisplay.middleTruncated(send.token),
                        onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Cashu token", send.token)))
                                confirmationToastController?.show("Copied ecash token")
                            }
                        },
                        trailingIcon = Icons.Outlined.ContentCopy,
                    )
                }
            }
            notice?.let { RecoveryNotice(it) }
        }
    }
}

@Composable
private fun RecoveryNotice(notice: SendRecoveryNotice) {
    InlineNotice(
        text = notice.title ?: notice.message,
        detail = notice.message.takeIf { notice.title != null },
        severity = notice.severity,
        modifier = Modifier
            .paymentDetailWidth()
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

/**
 * The status screen's anatomy with the caution glyph, as on the pay confirm:
 * nothing was lost, the wallet only needs another look. Its one action
 * is Check Again, where the shared status screen offers Done.
 */
@Composable
private fun UnconfirmedFace(
    detail: String,
    mintName: String,
    onCheck: () -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val scaffoldHeight = maxHeight
        Column(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.height(scaffoldHeight * StatusTopFraction))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = StatusHeroMinHeight)
                        .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    contentAlignment = Alignment.TopCenter,
                ) {
                    ConfirmCautionFace(message = "Couldn't confirm send", detail = detail)
                }
                Column(
                    modifier = Modifier
                        .padding(horizontal = CashuTheme.spacing.comfortable)
                        .padding(top = CashuTheme.spacing.snug),
                ) {
                    InspectorRow(label = "Mint", value = mintName)
                }
            }
            Column(
                modifier = Modifier
                    .padding(horizontal = CashuTheme.spacing.comfortable)
                    .navigationBarsPadding()
                    .padding(bottom = CashuTheme.spacing.comfortable),
                verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
            ) {
                PrimaryButton(
                    text = "Check Again",
                    onClick = onCheck,
                    modifier = Modifier.testTag("cashu.send.check-recovery"),
                )
            }
        }
    }
}
