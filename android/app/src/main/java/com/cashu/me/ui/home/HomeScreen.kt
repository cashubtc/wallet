package com.cashu.me.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.cashu.me.Core.AmountDisplayPrimary
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.Core.CashuRequestStore
import com.cashu.me.Core.HomeBalance
import com.cashu.me.Core.Protocols.CurrencyAmount
import com.cashu.me.Core.Protocols.CurrencyRegistry
import com.cashu.me.Core.PriceService
import com.cashu.me.Core.SettingsManager
import com.cashu.me.Core.TransactionDisplay
import com.cashu.me.Core.WalletManager
import com.cashu.me.Core.displayText
import com.cashu.me.Models.CashuRequest
import com.cashu.me.Models.WalletTransaction
import com.cashu.me.ui.components.AmountText
import com.cashu.me.ui.components.BalanceDisplay
import com.cashu.me.ui.components.CanvasDivider
import com.cashu.me.ui.components.CashuRequestRow
import com.cashu.me.ui.components.requestRowAmount
import com.cashu.me.ui.components.EmptyState
import com.cashu.me.ui.components.GhostButton
import com.cashu.me.ui.components.MintChip
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.components.SectionHeader
import com.cashu.me.ui.components.TransactionRow
import com.cashu.me.ui.components.TransactionRowModel
import com.cashu.me.ui.components.ToolbarIcon
import com.cashu.me.ui.components.formatRelativeTimestamp
import com.cashu.me.ui.theme.CashuTheme

private const val RECENT_LIMIT = 5

// iOS MainWalletView: the received-delta beat auto-dismisses after 2.5s.
private const val RECEIVED_DELTA_DISMISS_MS = 2_500L

@Composable
fun HomeScreen(
    walletManager: WalletManager,
    settingsManager: SettingsManager,
    priceService: PriceService,
    cashuRequestStore: CashuRequestStore,
    onOpenMints: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenTransaction: (WalletTransaction) -> Unit,
    onOpenCashuRequest: (CashuRequest) -> Unit,
    onReceive: (ReceiveAction) -> Unit,
    onSend: () -> Unit,
    onOpenSettings: () -> Unit,
    onScan: () -> Unit,
    contentPadding: PaddingValues,
) {
    val walletState by walletManager.state.collectAsState()
    val settings by settingsManager.state.collectAsState()
    val priceState by priceService.state.collectAsState()
    val requestState by cashuRequestStore.state.collectAsState()
    val formatter = remember { AmountFormatter() }

    var receiveChooserOpen by remember { mutableStateOf(false) }

    val balanceDisplay = remember(walletState.balance, settings, priceState) {
        formatter.displayText(
            amountSats = walletState.balance,
            preferredPrimary = settings.amountDisplayPrimary,
            showFiat = settings.showFiatBalance && priceState.btcPrice > 0,
            btcPrice = priceState.btcPrice,
            currencyCode = settings.bitcoinPriceCurrency,
            useBitcoinSymbol = settings.useBitcoinSymbol,
        )
    }

    // Unified timeline: merge transactions + Cashu Requests, dedup claim-tx ids,
    // sort by date descending, cap at RECENT_LIMIT.
    val recentItems = remember(walletState.transactions, requestState.requests) {
        unifiedRecent(walletState.transactions, requestState.requests, RECENT_LIMIT)
    }

    // Received-delta beat (iOS MainWalletView "payment-received celebration"):
    // when the balance rises while Home is composed (receives land behind the
    // flow sheet), a transient monochrome "+N" takes over the fiat slot for
    // 2.5s, then fiat fades back. Rapid receives coalesce last-write-wins (the
    // LaunchedEffect restart cancels the prior dismiss timer); a balance drop
    // clears the beat immediately. Tracking only starts once the wallet is
    // initialized and idle, so the startup 0 → N load can't fire a spurious
    // beat (iOS keys this off an explicit token-received notification instead).
    var lastObservedBalance by remember { mutableStateOf<Long?>(null) }
    var receivedDelta by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(walletState.balance, walletState.isInitialized, walletState.isLoading) {
        if (!walletState.isInitialized || walletState.isLoading) return@LaunchedEffect
        val previous = lastObservedBalance
        lastObservedBalance = walletState.balance
        if (previous != null && walletState.balance > previous) {
            receivedDelta = "+" + formatter.formatSats(
                walletState.balance - previous,
                includeUnit = false,
            )
            delay(RECEIVED_DELTA_DISMISS_MS)
            receivedDelta = null
        } else {
            receivedDelta = null
        }
    }

    // iOS parity: MainWalletView measures the pinned header (GeometryReader +
    // PreferenceKey) and derives the scroll inset + fade mask from the measured
    // height. SubcomposeLayout measures the pinned header *before* the list is
    // composed, so the very first frame lays out with the correct inset — this
    // replaces the old onSizeChanged + hide-first-frame alpha hack.
    SubcomposeLayout(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            // The scaffold's contentPadding already carries the status-bar inset;
            // consume it so PinnedTop's statusBarsPadding() can't double-apply.
            .consumeWindowInsets(contentPadding),
    ) { constraints ->
        // Pinned top section (mint chip + balance + triptych), measured first.
        val pinned = subcompose(HomeSlot.Pinned) {
            PinnedTop(
                mintChip = {
                    MintChip(
                        activeMint = walletState.activeMint,
                        mints = walletState.mints,
                        onSelect = { mint -> walletManager.launch { walletManager.setActiveMint(mint) } },
                        onManage = onOpenMints,
                    )
                },
                balance = {
                    val satHero: @Composable () -> Unit = {
                        BalanceDisplay(
                            amount = balanceDisplay,
                            // iOS: tapping the hero toggles the ₿ symbol vs "sat".
                            onTogglePrimary = {
                                settingsManager.setUseBitcoinSymbol(!settings.useBitcoinSymbol)
                            },
                            receivedDelta = receivedDelta,
                        )
                    }
                    // Multi-unit pager carve-out: one hero number at a time, only
                    // when the active mint is multi-unit AND non-sat balance is held.
                    val showsPager = HomeBalance.showsUnitPager(
                        activeMintSupportsMultipleUnits = walletState.activeMint?.supportsMultipleUnits == true,
                        balancesByUnit = walletState.balancesByUnit,
                    )
                    if (showsPager) {
                        UnitBalancePager(
                            balancesByUnit = walletState.balancesByUnit,
                            persistedUnit = settings.homeBalanceUnit,
                            onUnitSelected = settingsManager::setHomeBalanceUnit,
                            satHero = satHero,
                        )
                    } else {
                        satHero()
                    }
                },
                triptych = {
                    ActionDuet(
                        onReceive = { receiveChooserOpen = true },
                        // Send opens the unified surface directly — no chooser.
                        onSend = onSend,
                        receiveEnabled = walletState.activeMint != null,
                        // iOS parity: Send is tappable at zero balance; the sheet shows
                        // "Nothing to send yet" with a Receive CTA instead of disabling here.
                        sendEnabled = walletState.activeMint != null,
                    )
                },
                onOpenSettings = onOpenSettings,
                onScan = onScan,
            )
        }.first().measure(constraints.copy(minHeight = 0))

        val pinnedTopPx = pinned.height
        val pinnedTopDp = pinnedTopPx.toDp()
        val viewportHeight = constraints.maxHeight.toDp()

        // Scrolling body sits behind the pinned top with a soft fade-mask at the
        // top edge so rows dissolve into the pinned region as they scroll up,
        // matching the iOS LinearGradient scroll mask.
        val body = subcompose(HomeSlot.Body) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithCache {
                        val fadeBandPx = FADE_BAND_HEIGHT.toPx()
                        val total = size.height.coerceAtLeast(1f)
                        val clearEnd = (pinnedTopPx / total).coerceIn(0f, 1f)
                        val opaqueAt = ((pinnedTopPx + fadeBandPx) / total).coerceIn(0f, 1f)
                        val brush = Brush.verticalGradient(
                            0f to Color.Transparent,
                            clearEnd to Color.Transparent,
                            opaqueAt to Color.Black,
                            1f to Color.Black,
                        )
                        onDrawWithContent {
                            drawContent()
                            drawRect(brush = brush, blendMode = BlendMode.DstIn)
                        }
                    },
                contentPadding = PaddingValues(
                    top = pinnedTopDp + CashuTheme.spacing.snug,
                    bottom = CashuTheme.spacing.section,
                ),
            ) {
                item("section-header") {
                    if (recentItems.isNotEmpty()) {
                        SectionHeader(text = "Recent")
                    }
                }
                if (recentItems.isEmpty()) {
                    item("empty") {
                        val hasMints = walletState.mints.isNotEmpty()
                        // iOS: a single quiet tray empty state, centered in the region
                        // below the pinned header (containerRelativeFrame parity) —
                        // sized from the measured header, not a hardcoded height.
                        val emptyHeight = (viewportHeight - pinnedTopDp - CashuTheme.spacing.section)
                            .coerceAtLeast(EMPTY_STATE_MIN_HEIGHT)
                        EmptyState(
                            icon = if (hasMints) Icons.Outlined.Inbox else Icons.Outlined.AccountBalance,
                            title = if (hasMints) "No Activity Yet" else "Add a mint to get started",
                            supporting = if (hasMints) "Your recent payments will show up here."
                            else "Mints custody your ecash. Add one to begin.",
                            actionLabel = if (!hasMints) "Add mint" else null,
                            onAction = if (!hasMints) onOpenMints else null,
                            modifier = Modifier.height(emptyHeight),
                        )
                    }
                } else {
                    items(recentItems, key = { it.key }) { item ->
                        // Spring-animated placement when the timeline reshuffles
                        // (new payment lands, request claimed) — History parity.
                        Column(modifier = Modifier.animateItem()) {
                            when (item) {
                                is HomeRecentItem.Tx -> {
                                    val tx = item.transaction
                                    TransactionRow(
                                        model = TransactionRowModel(
                                            transaction = tx,
                                            title = TransactionDisplay.title(tx),
                                            timestamp = formatRelativeTimestamp(tx.dateEpochMillis),
                                            primaryAmount = formatter.formatWalletSats(
                                                tx.amount, settings.useBitcoinSymbol,
                                            ),
                                            secondaryAmount = if (settings.showFiatBalance && priceState.btcPrice > 0)
                                                formatter.formatFiat(tx.amount, priceState.btcPrice, settings.bitcoinPriceCurrency)
                                            else null,
                                        ),
                                        onClick = { onOpenTransaction(tx) },
                                    )
                                }
                                is HomeRecentItem.Req -> {
                                    val req = item.request
                                    CashuRequestRow(
                                        request = req,
                                        timestamp = formatRelativeTimestamp(req.createdAtEpochMillis),
                                        primaryAmountText = requestRowAmount(
                                            req, formatter, settings.useBitcoinSymbol,
                                        ),
                                        secondaryAmountText = null,
                                        onClick = { onOpenCashuRequest(req) },
                                    )
                                }
                            }
                            if (item != recentItems.last()) CanvasDivider()
                        }
                    }
                    item("view-all") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = CashuTheme.spacing.snug),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            // Chevron lives inside the button so the whole affordance
                            // is one touch target (iOS: text + chevron in one Button).
                            GhostButton(
                                text = "View all activity",
                                onClick = onOpenHistory,
                                trailingIcon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                                textStyle = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                }
            }
        }.first().measure(constraints)

        layout(constraints.maxWidth, constraints.maxHeight) {
            body.place(0, 0)
            pinned.place(0, 0)
        }
    }

    if (receiveChooserOpen) {
        ReceiveChooserSheet(
            onSelect = { action ->
                receiveChooserOpen = false
                onReceive(action)
            },
            onDismiss = { receiveChooserOpen = false },
        )
    }
}

// iOS scrollFadeBand: rows dissolve over a 24pt band beneath the measured
// pinned-header bottom edge (MainWalletView.scrollFadeBand = 24).
private val FADE_BAND_HEIGHT = 24.dp
// Floor for the empty-state slot when the pinned header dominates the viewport
// (large font scales); keeps the tray glyph + copy visible and scrollable.
private val EMPTY_STATE_MIN_HEIGHT = 240.dp

/** SubcomposeLayout slots for the Home screen. */
private enum class HomeSlot { Pinned, Body }

@Composable
private fun PinnedTop(
    mintChip: @Composable () -> Unit,
    balance: @Composable () -> Unit,
    triptych: @Composable () -> Unit,
    onOpenSettings: () -> Unit,
    onScan: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Solid background; the fade effect lives on the LazyColumn mask below
            // (rows fade as they scroll up past the pinned region).
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .padding(horizontal = CashuTheme.spacing.comfortable)
            .padding(top = CashuTheme.spacing.snug, bottom = CashuTheme.spacing.comfortable),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Wallet-level navigation affordances: settings on the leading edge,
        // scanner on the trailing edge. IconButton preserves a 48dp touch target.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = onOpenSettings) {
                ToolbarIcon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Settings",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onScan) {
                ToolbarIcon(
                    imageVector = Icons.Outlined.QrCodeScanner,
                    contentDescription = "Scan QR",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // Mint chip + balance + Receive/Send — tighter vertical rhythm than the
        // older ~28dp gaps so the hero block reads as one unit under the nav row.
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                mintChip()
            }
            balance()
            triptych()
        }
    }
}

/**
 * Home balance unit pager (iOS MainWalletView.unitBalanceHero). Page order is
 * sat first then held non-sat units sorted; the sat page keeps the ₿/fiat
 * toggle; non-sat pages render in their own currency with no fiat conversion
 * (eur is already fiat). Selection persists and clamps back to sat when the
 * unit no longer holds balance.
 */
@Composable
private fun UnitBalancePager(
    balancesByUnit: Map<String, Long>,
    persistedUnit: String,
    onUnitSelected: (String) -> Unit,
    satHero: @Composable () -> Unit,
) {
    val units = HomeBalance.homeBalanceUnits(balancesByUnit)
    val resolvedUnit = HomeBalance.resolvedUnit(persistedUnit, units)
    val pagerState = rememberPagerState(
        initialPage = units.indexOf(resolvedUnit).coerceAtLeast(0),
        pageCount = { units.size },
    )
    // Persist swipes; clamp when the held-unit list changes under the pager.
    LaunchedEffect(pagerState.currentPage, units) {
        units.getOrNull(pagerState.currentPage)?.let { current ->
            if (current != persistedUnit) onUnitSelected(current)
        }
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth(),
            key = { units[it] },
        ) { page ->
            val unit = units[page]
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (unit.equals("sat", ignoreCase = true)) {
                    satHero()
                } else {
                    AmountText(
                        text = CurrencyAmount(
                            balancesByUnit[unit] ?: 0L,
                            CurrencyRegistry.currencyForMintUnit(unit),
                        ).formatted(),
                        style = MaterialTheme.typography.displayMedium,
                    )
                }
            }
        }
        Spacer(Modifier.height(CashuTheme.spacing.snug))
        Row(horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight)) {
            units.forEachIndexed { index, unit ->
                val selected = index == pagerState.currentPage
                // Animated M3 page indicator: the active dot stretches into a pill.
                val dotWidth by animateDpAsState(
                    targetValue = if (selected) PAGE_DOT_SIZE * 2.5f else PAGE_DOT_SIZE,
                    animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                    label = "dot-width",
                )
                val dotColor by animateColorAsState(
                    targetValue = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    label = "dot-color",
                )
                Box(
                    modifier = Modifier
                        .height(PAGE_DOT_SIZE)
                        .width(dotWidth)
                        .background(color = dotColor, shape = CircleShape),
                )
            }
        }
    }
}

private val PAGE_DOT_SIZE = 6.dp

@Composable
private fun ActionDuet(
    onReceive: () -> Unit,
    onSend: () -> Unit,
    receiveEnabled: Boolean,
    sendEnabled: Boolean,
) {
    // Twin CTAs (iOS parity): Receive and Send carry equal weight on the home
    // canvas — no filled/tonal hierarchy between them. Styled as neutral
    // tonal pills (same fill/content colors as the history row's arrow
    // chips) rather than the inverted-ink PrimaryButton default, which reads
    // as too strong for a pair of equally-weighted actions.
    val actionColors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PrimaryButton(
            text = "Receive",
            onClick = onReceive,
            modifier = Modifier.weight(1f),
            enabled = receiveEnabled,
            colors = actionColors,
        )
        PrimaryButton(
            text = "Send",
            onClick = onSend,
            modifier = Modifier.weight(1f),
            enabled = sendEnabled,
            colors = actionColors,
        )
    }
}

/** Unified Home/History timeline item. Mirrors iOS HistoryItem enum. */
internal sealed interface HomeRecentItem {
    val date: Long
    val key: String
    data class Tx(val transaction: WalletTransaction) : HomeRecentItem {
        override val date: Long get() = transaction.dateEpochMillis
        override val key: String get() = "tx:${transaction.id}"
    }
    data class Req(val request: CashuRequest) : HomeRecentItem {
        override val date: Long get() = request.createdAtEpochMillis
        override val key: String get() = "req:${request.id}"
    }
}

/**
 * Merge transactions + Cashu Requests, suppress transactions that are already
 * claim-attached to a request (so the request is the single representation),
 * sort by date desc, return up to [limit].
 */
internal fun unifiedRecent(
    transactions: List<WalletTransaction>,
    requests: List<CashuRequest>,
    limit: Int,
): List<HomeRecentItem> {
    val claimedTxIds = buildSet {
        requests.forEach { req ->
            req.receivedPayments.forEach { add(it.transactionId) }
        }
    }
    val txItems = transactions
        .filterNot { it.id in claimedTxIds }
        .map { HomeRecentItem.Tx(it) as HomeRecentItem }
    val reqItems = requests.map { HomeRecentItem.Req(it) as HomeRecentItem }
    return (txItems + reqItems).sortedByDescending { it.date }.take(limit)
}
