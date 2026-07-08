package org.cashu.wallet.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.ArrowOutward
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.cashu.wallet.BuildConfig
import org.cashu.wallet.Core.AppLockManager
import org.cashu.wallet.Core.PriceService
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.ui.components.NavRow
import org.cashu.wallet.ui.components.SectionHeader
import org.cashu.wallet.ui.components.ToggleRow
import org.cashu.wallet.ui.performance.DebugRecompositionProbe
import org.cashu.wallet.ui.security.findFragmentActivity
import org.cashu.wallet.ui.theme.CashuTheme

private data class SettingsDisplayUiState(
    val showFiatBalance: Boolean = false,
    val bitcoinPriceCurrency: String = "USD",
    val useBitcoinSymbol: Boolean = false,
)

private data class SettingsAppLockUiState(
    val enabled: Boolean = false,
    val available: Boolean = false,
)

private enum class SettingsRouteAction {
    BackupRestore,
    Lightning,
    LockedEcash,
    Nostr,
    Privacy,
}

private data class SettingsRouteRowSpec(
    val key: String,
    val title: String,
    val leadingIcon: ImageVector,
    val action: SettingsRouteAction,
)

private data class SettingsExternalRowSpec(
    val key: String,
    val title: String,
    val leadingIcon: ImageVector,
    val url: String,
)

/**
 * Settings root — section order, rows, and copy mirror iOS SettingsView:
 * Display · Backup & Security · Payments · Integrations · Privacy · About ·
 * Danger, with the version footer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    walletManager: WalletManager,
    settingsManager: SettingsManager,
    appLockManager: AppLockManager,
    priceService: PriceService,
    onOpenBackupRestore: () -> Unit,
    onOpenLightning: () -> Unit,
    onOpenLockedEcash: () -> Unit,
    onOpenNostr: () -> Unit,
    onOpenPrivacy: () -> Unit,
    contentPadding: PaddingValues,
) {
    DebugRecompositionProbe("SettingsScreen")
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()
    val displayState by remember(settingsManager) {
        settingsManager.state
            .map {
                SettingsDisplayUiState(
                    showFiatBalance = it.showFiatBalance,
                    bitcoinPriceCurrency = it.bitcoinPriceCurrency,
                    useBitcoinSymbol = it.useBitcoinSymbol,
                )
            }
            .distinctUntilChanged()
    }.collectAsStateWithLifecycle(SettingsDisplayUiState())
    val appLockUiState by remember(settingsManager, appLockManager) {
        combine(settingsManager.state, appLockManager.state) { settings, appLock ->
            SettingsAppLockUiState(
                enabled = settings.appLockEnabled,
                available = appLock.isAvailable,
            )
        }.distinctUntilChanged()
    }.collectAsStateWithLifecycle(SettingsAppLockUiState())
    val backupRows = remember {
        listOf(
            SettingsRouteRowSpec(
                key = "backup-restore",
                title = "Backup & Restore",
                leadingIcon = Icons.Outlined.VpnKey,
                action = SettingsRouteAction.BackupRestore,
            ),
        )
    }
    val paymentRows = remember {
        listOf(
            SettingsRouteRowSpec(
                key = "lightning",
                title = "Lightning",
                leadingIcon = Icons.Outlined.Bolt,
                action = SettingsRouteAction.Lightning,
            ),
            SettingsRouteRowSpec(
                key = "locked-ecash",
                title = "Locked Ecash",
                leadingIcon = Icons.Outlined.Lock,
                action = SettingsRouteAction.LockedEcash,
            ),
        )
    }
    val integrationRows = remember {
        listOf(
            SettingsRouteRowSpec(
                key = "nostr",
                title = "Nostr",
                leadingIcon = Icons.Outlined.AccountCircle,
                action = SettingsRouteAction.Nostr,
            ),
        )
    }
    val privacyRows = remember {
        listOf(
            SettingsRouteRowSpec(
                key = "privacy",
                title = "Privacy",
                leadingIcon = Icons.Outlined.VisibilityOff,
                action = SettingsRouteAction.Privacy,
            ),
        )
    }
    val aboutRows = remember {
        listOf(
            SettingsExternalRowSpec(
                key = "learn",
                title = "Learn about Cashu",
                leadingIcon = Icons.Outlined.Public,
                url = "https://cashu.space",
            ),
            SettingsExternalRowSpec(
                key = "specs",
                title = "Protocol Specs (NUTs)",
                leadingIcon = Icons.Outlined.Description,
                url = "https://github.com/cashubtc/nuts",
            ),
        )
    }
    var confirmDelete by remember { mutableStateOf(false) }
    var appLockUnavailable by remember { mutableStateOf(false) }
    var currencyPickerOpen by remember { mutableStateOf(false) }

    fun openRoute(action: SettingsRouteAction) {
        when (action) {
            SettingsRouteAction.BackupRestore -> onOpenBackupRestore()
            SettingsRouteAction.Lightning -> onOpenLightning()
            SettingsRouteAction.LockedEcash -> onOpenLockedEcash()
            SettingsRouteAction.Nostr -> onOpenNostr()
            SettingsRouteAction.Privacy -> onOpenPrivacy()
        }
    }

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("Settings") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            // Generous bottom inset so the footer clears the navigation bar.
            contentPadding = PaddingValues(bottom = CashuTheme.spacing.section + CashuTheme.spacing.snug),
        ) {
            item("display-header") { SectionHeader("Display") }
            item("currency") {
                NavRow(
                    title = "Currency",
                    leadingIcon = Icons.Outlined.Payments,
                    trailingValue = if (displayState.showFiatBalance) displayState.bitcoinPriceCurrency else "Off",
                    onClick = { currencyPickerOpen = true },
                )
            }
            item("btc-symbol") {
                ToggleRow(
                    title = "Use ₿ symbol",
                    subtitle = "Use ₿ symbol instead of sats.",
                    leadingIcon = Icons.Outlined.CurrencyBitcoin,
                    checked = displayState.useBitcoinSymbol,
                    onCheckedChange = settingsManager::setUseBitcoinSymbol,
                )
            }

            item("backup-header") { SectionHeader("Backup & Security") }
            items(backupRows, key = { it.key }) { row ->
                RouteNavRow(row = row, onOpen = ::openRoute)
            }
            item("app-lock") {
                ToggleRow(
                    title = "App Lock",
                    subtitle = if (appLockUiState.available) {
                        "Require biometrics or device credential to open the wallet."
                    } else {
                        "Set a screen lock on this device to enable wallet locking."
                    },
                    leadingIcon = if (appLockUiState.enabled) Icons.Outlined.Lock else Icons.Outlined.LockOpen,
                    checked = appLockUiState.enabled,
                    onCheckedChange = { requested ->
                        if (!requested) {
                            settingsManager.setAppLockEnabled(false)
                            appLockManager.setEnabled(false)
                        } else if (!appLockManager.refreshAvailability()) {
                            appLockUnavailable = true
                        } else {
                            scope.launch {
                                if (appLockManager.authenticate(activity, "Confirm to enable App Lock")) {
                                    settingsManager.setAppLockEnabled(true)
                                    appLockManager.setEnabled(true)
                                }
                            }
                        }
                    },
                )
            }

            item("payments-header") { SectionHeader("Payments") }
            items(paymentRows, key = { it.key }) { row ->
                RouteNavRow(row = row, onOpen = ::openRoute)
            }

            item("integrations-header") { SectionHeader("Integrations") }
            items(integrationRows, key = { it.key }) { row ->
                RouteNavRow(row = row, onOpen = ::openRoute)
            }

            item("privacy-header") { SectionHeader("Privacy") }
            items(privacyRows, key = { it.key }) { row ->
                RouteNavRow(row = row, onOpen = ::openRoute)
            }

            item("about-header") { SectionHeader("About") }
            items(aboutRows, key = { it.key }) { row ->
                NavRow(
                    title = row.title,
                    leadingIcon = row.leadingIcon,
                    trailingIcon = Icons.Outlined.ArrowOutward,
                    onClick = { context.openExternal(row.url) },
                )
            }

            item("danger-header") { SectionHeader("Danger") }
            item("delete") {
                NavRow(
                    title = "Delete Wallet",
                    leadingIcon = Icons.Outlined.DeleteOutline,
                    onClick = { confirmDelete = true },
                    tint = MaterialTheme.colorScheme.error,
                    showChevron = false,
                )
            }

            item("footer") {
                Spacer(Modifier.height(CashuTheme.spacing.section))
                Text(
                    text = "Cashu Wallet · ${BuildConfig.VERSION_NAME}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    if (currencyPickerOpen) {
        CurrencyPickerSheet(
            settingsManager = settingsManager,
            priceService = priceService,
            onDismiss = { currencyPickerOpen = false },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete Wallet") },
            text = {
                Text(
                    "This permanently removes the local wallet, mint data, pending requests, Nostr identity, and locked-ecash keys from this device. Android cloud backup is not available yet, so make sure your seed phrase is backed up before deleting.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    walletManager.launch { walletManager.deleteWallet() }
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }

    if (appLockUnavailable) {
        AlertDialog(
            onDismissRequest = { appLockUnavailable = false },
            title = { Text("Screen lock required") },
            text = {
                Text(
                    "Set up a device PIN, password, pattern, or biometric unlock in Android Settings before enabling App Lock.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { appLockUnavailable = false }) { Text("OK") }
            },
        )
    }
}

@Composable
private fun RouteNavRow(
    row: SettingsRouteRowSpec,
    onOpen: (SettingsRouteAction) -> Unit,
) {
    NavRow(
        title = row.title,
        leadingIcon = row.leadingIcon,
        onClick = { onOpen(row.action) },
    )
}

private fun Context.openExternal(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { startActivity(intent) }
}
