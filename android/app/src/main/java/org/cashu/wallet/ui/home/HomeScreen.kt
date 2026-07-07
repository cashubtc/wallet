package org.cashu.wallet.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.cashu.wallet.Core.AmountDisplayPrimary
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.CashuRequestStore
import org.cashu.wallet.Core.HomeBalance
import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Core.PriceService
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.TransactionDisplay
import org.cashu.wallet.Core.WalletReceiveEvent
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Core.displayText
import org.cashu.wallet.Core.showsHomeSatDelta
import org.cashu.wallet.Models.CashuRequest
import org.cashu.wallet.Models.TransactionStatus
import org.cashu.wallet.Models.WalletTransaction
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.BalanceDisplay
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.CashuRequestRow
import org.cashu.wallet.ui.components.requestRowAmount
import org.cashu.wallet.ui.components.EmptyState
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.MintChip
import org.cashu.wallet.ui.components.SectionHeader
import org.cashu.wallet.ui.components.TransactionRow
import org.cashu.wallet.ui.components.TransactionRowModel
import org.cashu.wallet.ui.components.formatRelativeTimestamp
import org.cashu.wallet.ui.theme.CashuTheme

private const val RECENT_LIMIT = 5
private const val RECEIVED_BEAT_DURATION_MS = 2600L

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
    var receivedBeat by remember { mutableStateOf<WalletReceiveEvent?>(null) }

    LaunchedEffect(walletManager) {
        walletManager.receiveEvents.collect { event ->
            if (event.showsHomeSatDelta()) {
                receivedBeat = event
            }
        }
    }

    LaunchedEffect(receivedBeat?.id) {
        val beatId = receivedBeat?.id ?: return@LaunchedEffect
        delay(RECEIVED_BEAT_DURATION_MS)
        if (receivedBeat?.id == beatId) {
            receivedBeat = null
        }
    }

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
    val pinnedTopPx = with(density) { PINNED_TOP_HEIGHT.toPx() }
    val fadeBandPx = with(density) { FADE_BAND_HEIGHT.toPx() }

    Box(modifier = Modifier.fillMaxSize().padding(contentPadding)) {
        // Scrolling body sits behind the pinned top with a soft fade-mask at the
        // top edge so rows dissolve into the pinned region as they scroll up,
        // matching the iOS LinearGradient scroll mask.
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
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
                top = PINNED_TOP_HEIGHT,
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
                    // iOS: a single quiet tray empty state; the add-mint CTA
                    // survives only for the true first-run (no mints yet).
                    EmptyState(
                        icon = if (hasMints) Icons.Outlined.Inbox else Icons.Outlined.AccountBalance,
                        title = if (hasMints) "No Activity Yet" else "Add a mint to get started",
                        supporting = if (hasMints) "Your recent payments will show up here."
                        else "Mints custody your ecash. Add one to begin.",
                        actionLabel = if (!hasMints) "Add mint" else null,
                        onAction = if (!hasMints) onOpenMints else null,
                        modifier = Modifier.heightIn(min = 320.dp),
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
                        GhostButton(text = "View all activity", onClick = onOpenHistory)
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(VIEW_ALL_CHEVRON_SIZE),
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
                    modifier = Modifier.semantics {
                        role = Role.Button
                        contentDescription = walletState.activeMint?.let { mint ->
                            "Active mint ${mint.name}. Double tap to change mint."
                        } ?: "No active mint. Double tap to add a mint."
                    },
                )
            },
            balance = {
                val satHero: @Composable () -> Unit = {
                    BalanceDisplay(
                        amount = balanceDisplay,
                        modifier = Modifier.semantics {
                            role = Role.Button
                            contentDescription = buildString {
                                append("Balance ${balanceDisplay.primary}")
                                balanceDisplay.secondary?.let { append(", $it") }
                                append(". Double tap to toggle primary display.")
                            }
                        },
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
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
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
                    ReceivedDeltaBeat(
                        event = receivedBeat,
                        formatter = formatter,
                        useBitcoinSymbol = settings.useBitcoinSymbol,
                    )
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

// PINNED_TOP_HEIGHT must match the LazyColumn's top contentPadding so the fade
// mask aligns with the bottom edge of the pinned region.
private val PINNED_TOP_HEIGHT = 280.dp
private val FADE_BAND_HEIGHT = 32.dp
private val VIEW_ALL_CHEVRON_SIZE = 16.dp
private val ACTION_BUTTON_MIN_HEIGHT = 56.dp

@Composable
private fun PinnedTop(
    mintChip: @Composable () -> Unit,
    balance: @Composable () -> Unit,
    triptych: @Composable () -> Unit,
    onScan: () -> Unit,
) {
    Column(
        modifier = Modifier
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
                modifier = Modifier.semantics {
                    role = Role.Button
                    contentDescription = "Scan QR code"
                },
            ) {
                Icon(
                    imageVector = Icons.Outlined.QrCodeScanner,
                    contentDescription = null,
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
                Box(
                    modifier = Modifier
                        .size(PAGE_DOT_SIZE)
                        .semantics {
                            contentDescription = "Balance page ${index + 1} of ${units.size}: $unit"
                        }
                        .background(
                            color = if (index == pagerState.currentPage) {
                                MaterialTheme.colorScheme.onSurface
                            } else {
                                MaterialTheme.colorScheme.outlineVariant
                            },
                            shape = CircleShape,
                        ),
                )
            }
        }
    }
}

private val PAGE_DOT_SIZE = 6.dp

@Composable
private fun ReceivedDeltaBeat(
    event: WalletReceiveEvent?,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
) {
    AnimatedVisibility(
        visible = event != null,
        enter = fadeIn() + scaleIn(initialScale = 0.94f),
        exit = fadeOut() + scaleOut(targetScale = 0.98f),
    ) {
        val amount = event?.let { formatter.formatWalletSats(it.amount, useBitcoinSymbol) }.orEmpty()
        Row(
            modifier = Modifier
                .padding(top = CashuTheme.spacing.snug)
                .background(
                    color = CashuTheme.colors.receivedContainer,
                    shape = MaterialTheme.shapes.extraLarge,
                )
                .padding(horizontal = CashuTheme.spacing.default, vertical = CashuTheme.spacing.tight)
                .semantics {
                    liveRegion = LiveRegionMode.Polite
                    contentDescription = "Received $amount"
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight),
        ) {
            Text(
                text = "+$amount",
                style = MaterialTheme.typography.labelLarge,
                color = CashuTheme.colors.received,
            )
        }
    }
}

@Composable
private fun ActionDuet(
    onReceive: () -> Unit,
    onSend: () -> Unit,
    receiveEnabled: Boolean,
    sendEnabled: Boolean,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilledTonalButton(
            onClick = onReceive,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ACTION_BUTTON_MIN_HEIGHT)
                .semantics {
                    role = Role.Button
                    contentDescription = "Receive"
                },
            shape = MaterialTheme.shapes.extraLarge,
            enabled = receiveEnabled,
        ) {
            Text("Receive", style = MaterialTheme.typography.labelLarge)
        }
        FilledTonalButton(
            onClick = onSend,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = ACTION_BUTTON_MIN_HEIGHT)
                .semantics {
                    role = Role.Button
                    contentDescription = "Send"
                },
            shape = MaterialTheme.shapes.extraLarge,
            enabled = sendEnabled,
        ) {
            Text("Send", style = MaterialTheme.typography.labelLarge)
        }
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
