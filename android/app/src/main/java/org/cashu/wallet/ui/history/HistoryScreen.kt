package org.cashu.wallet.ui.history

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.HistoryFilter
import org.cashu.wallet.Core.PriceService
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.TransactionDisplay
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Core.filterTransactions
import org.cashu.wallet.Core.groupTransactionsByDate
import org.cashu.wallet.Models.WalletTransaction
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.EmptyState
import org.cashu.wallet.ui.components.SectionHeader
import org.cashu.wallet.ui.components.TransactionRow
import org.cashu.wallet.ui.components.TransactionRowModel
import org.cashu.wallet.ui.components.formatRelativeTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    walletManager: WalletManager,
    settingsManager: SettingsManager,
    priceService: PriceService,
    onOpenTransaction: (WalletTransaction) -> Unit,
    contentPadding: PaddingValues,
) {
    val walletState by walletManager.state.collectAsState()
    val settings by settingsManager.state.collectAsState()
    val priceState by priceService.state.collectAsState()
    val formatter = remember { AmountFormatter() }

    var filter by remember { mutableStateOf(HistoryFilter.All) }
    var filterMenuOpen by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        walletManager.loadTransactions()
    }

    val filtered by remember(walletState.transactions, filter, query) {
        derivedStateOf {
            val base = filterTransactions(walletState.transactions, filter)
            if (query.isBlank()) base
            else base.filter { tx ->
                TransactionDisplay.title(tx).contains(query, ignoreCase = true) ||
                    tx.amount.toString().contains(query) ||
                    tx.memo?.contains(query, ignoreCase = true) == true
            }
        }
    }
    val sections by remember(filtered) {
        derivedStateOf { groupTransactionsByDate(filtered, System.currentTimeMillis()) }
    }

    val topBarState = rememberTopAppBarState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(state = topBarState)

    Scaffold(
        modifier = Modifier
            .padding(contentPadding)
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text("History", style = MaterialTheme.typography.headlineMedium) },
                scrollBehavior = scrollBehavior,
                colors = TopAppBarDefaults.largeTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
                actions = {
                    IconButton(onClick = { searching = !searching }) {
                        Icon(Icons.Outlined.Search, contentDescription = "Search")
                    }
                    Box {
                        IconButton(onClick = { filterMenuOpen = true }) {
                            Icon(
                                imageVector = if (filter == HistoryFilter.All)
                                    Icons.Outlined.FilterList else Icons.Filled.FilterList,
                                contentDescription = "Filter",
                            )
                        }
                        DropdownMenu(
                            expanded = filterMenuOpen,
                            onDismissRequest = { filterMenuOpen = false },
                        ) {
                            HistoryFilter.entries.forEach { entry ->
                                DropdownMenuItem(
                                    text = { Text(entry.label) },
                                    onClick = {
                                        filter = entry
                                        filterMenuOpen = false
                                    },
                                    trailingIcon = if (entry == filter) {
                                        { Icon(Icons.Outlined.Check, contentDescription = null) }
                                    } else null,
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (sections.isEmpty()) {
                EmptyState(
                    icon = when {
                        query.isNotBlank() -> Icons.Outlined.Search
                        filter == HistoryFilter.Pending -> Icons.Outlined.Schedule
                        else -> Icons.Outlined.Bolt
                    },
                    title = when {
                        query.isNotBlank() -> "No matches"
                        filter == HistoryFilter.Pending -> "No pending transactions"
                        filter == HistoryFilter.Completed -> "No completed transactions"
                        else -> "No transactions yet"
                    },
                    supporting = if (query.isBlank() && filter == HistoryFilter.All)
                        "Your transaction history will appear here." else null,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (searching) {
                        item("search") {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                placeholder = { Text("Search transactions") },
                                singleLine = true,
                                shape = MaterialTheme.shapes.medium,
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                                ),
                            )
                        }
                    }
                    sections.forEach { section ->
                        item(key = "header-${section.title}") {
                            SectionHeader(section.title.uppercase())
                        }
                        items(section.transactions, key = { it.id }) { tx ->
                            TransactionRow(
                                model = TransactionRowModel(
                                    transaction = tx,
                                    title = TransactionDisplay.title(tx),
                                    timestamp = formatRelativeTimestamp(tx.dateEpochMillis),
                                    primaryAmount = formatter.formatWalletSats(
                                        tx.amount, settings.useBitcoinSymbol,
                                    ),
                                    secondaryAmount = if (
                                        settings.showFiatBalance && priceState.btcPrice > 0
                                    ) {
                                        formatter.formatFiat(
                                            tx.amount,
                                            priceState.btcPrice,
                                            settings.bitcoinPriceCurrency,
                                        )
                                    } else null,
                                ),
                                onClick = { onOpenTransaction(tx) },
                            )
                            if (tx != section.transactions.last()) CanvasDivider()
                        }
                    }
                }
            }
        }
    }
}

