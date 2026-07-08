package org.cashu.wallet.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.cashu.wallet.ui.navigation.Routes
import org.cashu.wallet.ui.navigation.TopTab
import org.cashu.wallet.ui.navigation.cashuRequestDetailRouteFor
import org.cashu.wallet.ui.navigation.mintDetailRouteFor
import org.cashu.wallet.ui.navigation.navigateToTab
import org.cashu.wallet.ui.navigation.transactionDetailRouteFor

data class FakeWalletContainer(
    val balanceLabel: String = "42 sats",
    val receivedDeltaLabel: String = "+21 sats",
    val historyItems: List<FakeHistoryItem> = listOf(
        FakeHistoryItem.Transaction("tx-1", "Received ecash", "+21 sats"),
        FakeHistoryItem.CashuRequest("request-1", "Reusable invoice", "42 sats"),
    ),
    val mintNames: List<String> = listOf("Fake Mint", "Backup Mint"),
    val actionLog: FakeWalletActionLog = FakeWalletActionLog(),
)

class FakeWalletActionLog {
    private val events = mutableStateListOf<String>()

    fun record(event: String) {
        events += event
    }

    fun snapshot(): List<String> = events.toList()
}

sealed interface FakeHistoryItem {
    val id: String
    val title: String
    val amountLabel: String

    data class Transaction(
        override val id: String,
        override val title: String,
        override val amountLabel: String,
    ) : FakeHistoryItem

    data class CashuRequest(
        override val id: String,
        override val title: String,
        override val amountLabel: String,
    ) : FakeHistoryItem
}

@Composable
fun FakeWalletApp(
    container: FakeWalletContainer = FakeWalletContainer(),
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    var scannerVisible by remember { mutableStateOf(false) }
    var contactlessVisible by remember { mutableStateOf(false) }

    Box(modifier.fillMaxSize()) {
        FakeWalletScaffold(
            container = container,
            navController = navController,
            onScan = {
                container.actionLog.record("scan")
                scannerVisible = true
            },
            onContactless = {
                container.actionLog.record("contactless")
                contactlessVisible = true
            },
        )
        if (scannerVisible) {
            FakeFullScreenOverlay(
                title = "Scanner",
                body = "Ready for QR payloads",
                closeLabel = "Close scanner",
                onClose = { scannerVisible = false },
            )
        }
        if (contactlessVisible) {
            FakeFullScreenOverlay(
                title = "Contactless Pay",
                body = "Ready for NFC payloads",
                closeLabel = "Close contactless",
                onClose = { contactlessVisible = false },
            )
        }
    }
}

@Composable
private fun FakeWalletScaffold(
    container: FakeWalletContainer,
    navController: NavHostController,
    onScan: () -> Unit,
    onContactless: () -> Unit,
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val selectedTab = TopTab.entries.firstOrNull { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (selectedTab != null) {
                FakeNavigationBar(
                    selected = selectedTab,
                    onSelect = { tab ->
                        if (tab != selectedTab) navController.navigateToTab(tab)
                    },
                )
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding),
        ) {
            composable(Routes.HOME) {
                FakeHomeScreen(
                    container = container,
                    onSend = { navController.navigate(Routes.SEND) },
                    onReceive = { navController.navigate(Routes.RECEIVE_ECASH) },
                    onScan = onScan,
                    onContactless = onContactless,
                    onHistory = { navController.navigateToTab(TopTab.History) },
                    onMints = { navController.navigateToTab(TopTab.Mints) },
                )
            }
            composable(Routes.HISTORY) {
                FakeHistoryScreen(
                    items = container.historyItems,
                    onOpenItem = { item ->
                        when (item) {
                            is FakeHistoryItem.Transaction -> navController.navigate(transactionDetailRouteFor(item.id))
                            is FakeHistoryItem.CashuRequest -> navController.navigate(cashuRequestDetailRouteFor(item.id))
                        }
                    },
                )
            }
            composable(Routes.MINTS) {
                FakeMintsScreen(
                    mintNames = container.mintNames,
                    onOpenMint = { navController.navigate(mintDetailRouteFor(it)) },
                    onScan = onScan,
                )
            }
            composable(Routes.SETTINGS) {
                FakeSettingsScreen(
                    onOpenBackupRestore = { navController.navigate(Routes.SETTINGS_BACKUP_RESTORE) },
                    onOpenLightning = { navController.navigate(Routes.SETTINGS_LIGHTNING) },
                    onOpenP2PK = { navController.navigate(Routes.SETTINGS_P2PK) },
                    onOpenNostr = { navController.navigate(Routes.SETTINGS_NOSTR) },
                    onOpenPrivacy = { navController.navigate(Routes.SETTINGS_PRIVACY) },
                )
            }
            composable(Routes.SEND) {
                FakePushedScreen(
                    title = "Send",
                    body = "Unified send flow",
                    onBack = { navController.popBackStack() },
                    actions = {
                        Button(onClick = { navController.navigate(Routes.SEND_ECASH) }) {
                            Text("Send ecash")
                        }
                    },
                )
            }
            composable(Routes.SEND_ECASH) {
                FakePushedScreen("Send ecash", "P2PK and token generation", navController::popBackStack)
            }
            composable(Routes.RECEIVE_ECASH) {
                FakePushedScreen(
                    title = "Receive ecash",
                    body = "Paste or scan a token",
                    onBack = { navController.popBackStack() },
                    actions = {
                        Button(onClick = { navController.navigate(Routes.RECEIVE_LIGHTNING) }) {
                            Text("New Request")
                        }
                        TextButton(onClick = { navController.navigate(Routes.RECEIVE_LOCKED_ECASH) }) {
                            Text("Locked ecash")
                        }
                    },
                )
            }
            composable(Routes.RECEIVE_LIGHTNING) {
                FakePushedScreen("Receive Lightning", "Invoice, reusable invoice, and on-chain", navController::popBackStack)
            }
            composable(Routes.RECEIVE_LOCKED_ECASH) {
                FakePushedScreen("Receive locked ecash", "NUT-10 locked receive request", navController::popBackStack)
            }
            composable(Routes.MINT_DETAIL) {
                FakePushedScreen("Mint detail", "NUT-06 metadata", navController::popBackStack)
            }
            composable(Routes.TRANSACTION_DETAIL) {
                FakePushedScreen("Transaction detail", "QR, copy, share, and explorer actions", navController::popBackStack)
            }
            composable(Routes.CASHU_REQUEST_DETAIL) {
                FakePushedScreen("Cashu Request detail", "Reusable request detail", navController::popBackStack)
            }
            composable(Routes.SETTINGS_BACKUP_RESTORE) {
                FakePushedScreen("Backup & Restore", "Seed reveal and restore entry", navController::popBackStack)
            }
            composable(Routes.SETTINGS_LIGHTNING) {
                FakePushedScreen("Lightning", "Address and claim preferences", navController::popBackStack)
            }
            composable(Routes.SETTINGS_P2PK) {
                FakePushedScreen("Locked Ecash", "Your key and advanced keys", navController::popBackStack)
            }
            composable(Routes.SETTINGS_NOSTR) {
                FakePushedScreen("Nostr", "Signer and relays", navController::popBackStack)
            }
            composable(Routes.SETTINGS_PRIVACY) {
                FakePushedScreen("Privacy", "Runtime-backed toggles", navController::popBackStack)
            }
        }
    }
}

@Composable
private fun FakeNavigationBar(selected: TopTab, onSelect: (TopTab) -> Unit) {
    NavigationBar {
        TopTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { onSelect(tab) },
                icon = {
                    Icon(imageVector = tab.icon, contentDescription = tab.label)
                },
                label = { Text(tab.label) },
            )
        }
    }
}

@Composable
private fun FakeHomeScreen(
    container: FakeWalletContainer,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onScan: () -> Unit,
    onContactless: () -> Unit,
    onHistory: () -> Unit,
    onMints: () -> Unit,
) {
    FakeScreenColumn(title = "Wallet") {
        Text(container.balanceLabel, style = MaterialTheme.typography.displaySmall)
        Text(container.receivedDeltaLabel, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSend) { Text("Send") }
            Button(onClick = onReceive) { Text("Receive") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onScan) { Text("Scan") }
            TextButton(onClick = onContactless) { Text("Contactless") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onHistory) { Text("Recent activity") }
            TextButton(onClick = onMints) { Text("Manage mints") }
        }
    }
}

@Composable
private fun FakeHistoryScreen(items: List<FakeHistoryItem>, onOpenItem: (FakeHistoryItem) -> Unit) {
    FakeLazyScreen(title = "History") {
        items(items, key = { it.id }) { item ->
            TextButton(
                onClick = { onOpenItem(item) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Column(Modifier.fillMaxWidth()) {
                    Text(item.title)
                    Text(item.amountLabel, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun FakeMintsScreen(mintNames: List<String>, onOpenMint: (String) -> Unit, onScan: () -> Unit) {
    FakeLazyScreen(title = "Mints") {
        item {
            Button(onClick = onScan, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                Text("Scan mint")
            }
        }
        items(mintNames, key = { it }) { mint ->
            TextButton(
                onClick = { onOpenMint(mint) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(mint, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun FakeSettingsScreen(
    onOpenBackupRestore: () -> Unit,
    onOpenLightning: () -> Unit,
    onOpenP2PK: () -> Unit,
    onOpenNostr: () -> Unit,
    onOpenPrivacy: () -> Unit,
) {
    FakeScreenColumn(title = "Settings") {
        TextButton(onClick = onOpenBackupRestore) { Text("Backup & Restore") }
        TextButton(onClick = onOpenLightning) { Text("Lightning") }
        TextButton(onClick = onOpenP2PK) { Text("Locked Ecash") }
        TextButton(onClick = onOpenNostr) { Text("Nostr") }
        TextButton(onClick = onOpenPrivacy) { Text("Privacy") }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FakePushedScreen(
    title: String,
    body: String,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { contentDescription = "Back" },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
            )
        },
    ) { padding ->
        FakeScreenColumn(title = title, modifier = Modifier.padding(padding)) {
            Text(body)
            actions()
        }
    }
}

@Composable
private fun FakeFullScreenOverlay(
    title: String,
    body: String,
    closeLabel: String,
    onClose: () -> Unit,
) {
    Scaffold { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(24.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Text(body)
                Button(onClick = onClose) {
                    Text(closeLabel)
                }
            }
        }
    }
}

@Composable
private fun FakeScreenColumn(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        content()
    }
}

@Composable
private fun FakeLazyScreen(
    title: String,
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                title,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        content()
    }
}

private val TopTab.icon: ImageVector
    get() = when (this) {
        TopTab.Home -> Icons.Filled.AccountBalanceWallet
        TopTab.History -> Icons.Filled.History
        TopTab.Mints -> Icons.Filled.AccountBalance
        TopTab.Settings -> Icons.Filled.Settings
    }
