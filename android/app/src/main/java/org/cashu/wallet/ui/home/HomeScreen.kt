package org.cashu.wallet.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import org.cashu.wallet.Core.AmountDisplayPrimary
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.CashuRequestStore
import org.cashu.wallet.Core.HomeBalance
import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Core.PriceService
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.TransactionDisplay
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Core.displayText
import org.cashu.wallet.Models.CashuRequest
import org.cashu.wallet.Models.WalletTransaction
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.BalanceDisplay
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.CashuRequestRow
import org.cashu.wallet.ui.components.requestRowAmount
import org.cashu.wallet.ui.components.EmptyState
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.MintChip
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.SecondaryButton
import org.cashu.wallet.ui.components.SectionHeader
import org.cashu.wallet.ui.components.TransactionRow
import org.cashu.wallet.ui.components.TransactionRowModel
import org.cashu.wallet.ui.components.formatRelativeTimestamp
import org.cashu.wallet.ui.theme.CashuTheme

private const val RECENT_LIMIT = 5

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

    val density = LocalDensity.current
    // iOS parity: MainWalletView measures the pinned header (GeometryReader +
    // PreferenceKey) and derives the scroll inset + fade mask from the measured
    // height. Mirror that here with onSizeChanged so the list clears the pinned
    // region in every configuration (fiat line, unit pager, font scale, insets).
    var pinnedTopPx by remember { mutableIntStateOf(0) }
    val pinnedTopDp = with(density) { pinnedTopPx.toDp() }
    val fadeBandPx = with(density) { FADE_BAND_HEIGHT.toPx() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(contentPadding)
            // The scaffold's contentPadding already carries the status-bar inset;
            // consume it so PinnedTop's statusBarsPadding() can't double-apply.
            .consumeWindowInsets(contentPadding),
    ) {
        val viewportHeight = maxHeight
        // Scrolling body sits behind the pinned top with a soft fade-mask at the
        // top edge so rows dissolve into the pinned region as they scroll up,
        // matching the iOS LinearGradient scroll mask.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    // Hide the first frame until the pinned header has reported
                    // its measured height and the inset below is correct.
                    alpha = if (pinnedTopPx == 0) 0f else 1f
                }
                .drawWithCache {
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
                top = pinnedTopDp,
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
                        )
                    }
                }
            }
        }

        // Pinned top section (mint chip + balance + triptych).
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
                    sendEnabled = walletState.hasAnyBalance,
                )
            },
            onScan = onScan,
            modifier = Modifier.onSizeChanged { pinnedTopPx = it.height },
        )
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

@Composable
private fun PinnedTop(
    mintChip: @Composable () -> Unit,
    balance: @Composable () -> Unit,
    triptych: @Composable () -> Unit,
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
        // Top-right scan affordance (iOS parity — scan lives in the top bar, not in the action row).
        // Default M3 FilledTonalIconButton size is 40dp visual + 48dp touch target via
        // minimumInteractiveComponentSize. No explicit size needed.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            FilledTonalIconButton(
                onClick = onScan,
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = Icons.Outlined.QrCodeScanner,
                    contentDescription = "Scan QR",
                )
            }
        }
        // Mint chip centered above the balance.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            mintChip()
        }
        // Spacing.spacedBy(12) above + micro spacer + spacing.spacedBy(12) below =
        // ~28dp between mint→balance and balance→triptych (iOS-matched).
        Spacer(Modifier.height(CashuTheme.spacing.micro))
        balance()
        Spacer(Modifier.height(CashuTheme.spacing.micro))
        triptych()
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
    // Filled + tonal pair: receive is the on-ramp (primary), send is one step
    // quieter — standard M3 emphasis hierarchy.
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
        )
        SecondaryButton(
            text = "Send",
            onClick = onSend,
            modifier = Modifier.weight(1f),
            enabled = sendEnabled,
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
