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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
fun FakeOnboardingFlow(modifier: Modifier = Modifier) {
    var step by remember { mutableStateOf("welcome") }

    FakeScreenColumn(title = "Onboarding", modifier = modifier) {
        when (step) {
            "welcome" -> {
                Text("Create or restore wallet")
                Button(onClick = { step = "seed" }) { Text("Create wallet") }
                TextButton(onClick = { step = "restore-method" }) { Text("Restore wallet") }
            }
            "seed" -> {
                Text("Seed phrase")
                Text("abandon abandon abandon")
                Button(onClick = { step = "first-mint" }) { Text("I saved my seed") }
            }
            "first-mint" -> {
                Text("First mint")
                Button(onClick = { step = "ready" }) { Text("Add first mint") }
                TextButton(onClick = { step = "ready" }) { Text("Skip first mint") }
            }
            "restore-method" -> {
                Text("Restore method")
                Button(onClick = { step = "restore-input" }) { Text("Seed restore") }
                TextButton(onClick = { step = "cloud-restore" }) { Text("Cloud restore") }
            }
            "cloud-restore" -> {
                Text("Cloud restore unavailable")
                Button(onClick = { step = "restore-method" }) { Text("Choose another method") }
            }
            "restore-input" -> {
                Text("Restore seed input")
                Button(onClick = { step = "restore-progress" }) { Text("Continue restore") }
            }
            "restore-progress" -> {
                Text("Staged mint restore progress")
                Button(onClick = { step = "restore-results" }) { Text("Show restore results") }
            }
            "restore-results" -> {
                Text("Staged mint restore results")
                Button(onClick = { step = "ready" }) { Text("Finish restore") }
            }
            "ready" -> {
                Text("Wallet ready")
            }
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
                FakeSendScreen(
                    onBack = { navController.popBackStack() },
                    onSendEcash = { navController.navigate(Routes.SEND_ECASH) },
                )
            }
            composable(Routes.SEND_ECASH) {
                FakeSendEcashScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.RECEIVE_ECASH) {
                FakeReceiveEcashScreen(
                    onBack = { navController.popBackStack() },
                    onNewRequest = { navController.navigate(Routes.RECEIVE_LIGHTNING) },
                    onLockedEcash = { navController.navigate(Routes.RECEIVE_LOCKED_ECASH) },
                )
            }
            composable(Routes.RECEIVE_LIGHTNING) {
                FakeReceiveLightningScreen(onBack = { navController.popBackStack() })
            }
            composable(Routes.RECEIVE_LOCKED_ECASH) {
                FakePushedScreen("Receive locked ecash", "NUT-10 locked receive request", navController::popBackStack)
            }
            composable(Routes.MINT_DETAIL) {
                FakePushedScreen("Mint detail", "Full NUT-06 metadata", navController::popBackStack)
            }
            composable(Routes.TRANSACTION_DETAIL) {
                FakePushedScreen("Transaction detail", "QR, copy, share, and explorer actions", navController::popBackStack)
            }
            composable(Routes.CASHU_REQUEST_DETAIL) {
                FakePushedScreen("Cashu Request detail", "Reusable request detail", navController::popBackStack)
            }
            composable(Routes.SETTINGS_BACKUP_RESTORE) {
                FakePushedScreen("Backup & Restore", "Backup reveal auth and restore entry", navController::popBackStack)
            }
            composable(Routes.SETTINGS_LIGHTNING) {
                FakePushedScreen("Lightning", "Lightning address rows, mint selection, and claim preferences", navController::popBackStack)
            }
            composable(Routes.SETTINGS_P2PK) {
                FakePushedScreen("Locked Ecash", "P2PK key flows and reveal auth", navController::popBackStack)
            }
            composable(Routes.SETTINGS_NOSTR) {
                FakePushedScreen("Nostr", "Nostr reveal auth and relay validation", navController::popBackStack)
            }
            composable(Routes.SETTINGS_PRIVACY) {
                FakePushedScreen("Privacy", "Runtime-backed privacy toggles", navController::popBackStack)
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
    var balanceVisible by remember { mutableStateOf(true) }
    var unitIndex by remember { mutableStateOf(0) }
    val units = listOf("sat", "usd")
    val recent = container.historyItems.firstOrNull()

    FakeScreenColumn(title = "Wallet") {
        Text(
            if (balanceVisible) container.balanceLabel else "Balance hidden",
            style = MaterialTheme.typography.displaySmall,
        )
        Text("Unit: ${units[unitIndex]}")
        Text(container.receivedDeltaLabel, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = { balanceVisible = !balanceVisible }) {
                Text(if (balanceVisible) "Hide balance" else "Show balance")
            }
            TextButton(onClick = { unitIndex = (unitIndex + 1) % units.size }) {
                Text("Next unit")
            }
        }
        if (recent == null) {
            Text("No History Yet")
        } else {
            TextButton(onClick = onHistory) {
                Text("Recent: ${recent.title}")
            }
        }
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
    var searching by remember { mutableStateOf(false) }
    var requestDeleted by remember { mutableStateOf(false) }
    val visibleItems = if (requestDeleted) items.filterNot { it is FakeHistoryItem.CashuRequest } else items

    FakeLazyScreen(title = "History") {
        item {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Button(onClick = { searching = true }) { Text("Search history") }
                TextButton(onClick = { requestDeleted = true }) { Text("Delete request") }
            }
            if (searching) {
                Text("No Results", modifier = Modifier.padding(horizontal = 20.dp))
            }
        }
        items(visibleItems, key = { it.id }) { item ->
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
    var discoveredAdded by remember { mutableStateOf(false) }
    var activeMint by remember { mutableStateOf(mintNames.firstOrNull().orEmpty()) }
    var removedMint by remember { mutableStateOf<String?>(null) }
    val visibleMints = mintNames.filterNot { it == removedMint }

    FakeLazyScreen(title = "Mints") {
        item {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onScan) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                        Text("Scan mint")
                    }
                    TextButton(onClick = { discoveredAdded = true }) {
                        Text("Add discovered mint")
                    }
                }
                Text("Paste mint")
                Text("Discovery search")
                if (discoveredAdded) Text("Discovered mint added")
            }
        }
        items(visibleMints, key = { it }) { mint ->
            Column(Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = { onOpenMint(mint) },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        if (mint == activeMint) "$mint active" else mint,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 20.dp),
                ) {
                    TextButton(onClick = { activeMint = mint }) { Text("Set active $mint") }
                    TextButton(onClick = { removedMint = mint }) { Text("Remove $mint") }
                }
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
        Text("App Lock")
        Text("Privacy toggles")
        Text("Delete wallet")
        TextButton(onClick = onOpenBackupRestore) { Text("Backup & Restore") }
        TextButton(onClick = onOpenLightning) { Text("Lightning") }
        TextButton(onClick = onOpenP2PK) { Text("Locked Ecash") }
        TextButton(onClick = onOpenNostr) { Text("Nostr") }
        TextButton(onClick = onOpenPrivacy) { Text("Privacy") }
    }
}

@Composable
private fun FakeSendScreen(onBack: () -> Unit, onSendEcash: () -> Unit) {
    var status by remember { mutableStateOf("Destination input") }

    FakePushedScreen(
        title = "Send",
        body = "Unified send flow",
        onBack = onBack,
        actions = {
            Text(status)
            Text("Amount entry")
            Text("Mint switch")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { status = "Quote loading" }) { Text("Load quote") }
                TextButton(onClick = { status = "Mint switched" }) { Text("Switch mint") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { status = "Success status" }) { Text("Pay") }
                TextButton(onClick = { status = "Failure status" }) { Text("Fail payment") }
            }
            Button(onClick = onSendEcash) {
                Text("Send ecash")
            }
        },
    )
}

@Composable
private fun FakeSendEcashScreen(onBack: () -> Unit) {
    var generated by remember { mutableStateOf(false) }

    FakePushedScreen(
        title = "Send ecash",
        body = "P2PK lock field",
        onBack = onBack,
        actions = {
            Text("Amount keypad")
            Text("P2PK quick fill")
            Button(onClick = { generated = true }) { Text("Generate token") }
            if (generated) {
                Text("Generated token")
                Text("Copy token")
                Text("Share token")
            }
        },
    )
}

@Composable
private fun FakeReceiveEcashScreen(
    onBack: () -> Unit,
    onNewRequest: () -> Unit,
    onLockedEcash: () -> Unit,
) {
    var status by remember { mutableStateOf("Paste token") }

    FakePushedScreen(
        title = "Receive ecash",
        body = "Paste or scan a token",
        onBack = onBack,
        actions = {
            Text(status)
            Text("Locked token: Your key")
            Text("Unknown mint warning")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { status = "Review token" }) { Text("Review") }
                TextButton(onClick = { status = "Receive later saved" }) { Text("Receive later") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { status = "Receive success" }) { Text("Accept token") }
                TextButton(onClick = { status = "Receive failure" }) { Text("Fail receive") }
            }
            Button(onClick = onNewRequest) { Text("New Request") }
            TextButton(onClick = onLockedEcash) { Text("Locked ecash") }
        },
    )
}

@Composable
private fun FakeReceiveLightningScreen(onBack: () -> Unit) {
    var method by remember { mutableStateOf("BOLT11 invoice display") }

    FakePushedScreen(
        title = "Receive Lightning",
        body = "Method picker",
        onBack = onBack,
        actions = {
            Text(method)
            Text("Expiry countdown")
            Text("BOLT12 reusable offer editing")
            Text("On-chain observer link")
            Text("Success status")
            Text("Failure status")
            Text("Back behavior")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { method = "BOLT11 invoice display" }) { Text("Lightning invoice") }
                TextButton(onClick = { method = "BOLT12 reusable invoice" }) { Text("Reusable invoice") }
            }
            TextButton(onClick = { method = "On-chain address display" }) {
                Text("On-chain address")
            }
        },
    )
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
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
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
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            ) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Text(body)
                if (title == "Scanner") {
                    Text("Permission denied")
                    Text("Permission granted")
                    Text("Animated UR progress")
                    Text("Quick-fill routing")
                    Text("Unsupported payload")
                }
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
            .verticalScroll(rememberScrollState())
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
