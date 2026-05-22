package org.cashu.wallet.ui.shell

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.cashu.wallet.App.AppContainer
import org.cashu.wallet.Core.Platform.ConnectivityState
import org.cashu.wallet.ui.navigation.CashuNavHost
import org.cashu.wallet.ui.navigation.Routes
import org.cashu.wallet.ui.navigation.TopTab
import org.cashu.wallet.ui.navigation.navigateToTab

@Composable
fun WalletScaffold(
    container: AppContainer,
    connectivityState: ConnectivityState,
    onScan: () -> Unit,
    onContactless: () -> Unit,
    pendingReceiveScan: String?,
    onPendingReceiveScanConsumed: () -> Unit,
    pendingSendScan: String?,
    onPendingSendScanConsumed: () -> Unit,
    pendingMintScan: String?,
    onPendingMintScanConsumed: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val selectedTab = TopTab.entries.firstOrNull { it.route == currentRoute }
    Scaffold(
        bottomBar = {
            // Hide bottom bar on pushed destinations that aren't a top tab.
            if (selectedTab != null) {
                CashuNavigationBar(
                    selected = selectedTab,
                    onSelect = { tab ->
                        if (tab != selectedTab) navController.navigateToTab(tab)
                    },
                )
            }
        },
    ) { padding ->
        CashuNavHost(
            navController = navController,
            container = container,
            connectivityState = connectivityState,
            contentPadding = padding,
            onScan = onScan,
            onContactless = onContactless,
            pendingReceiveScan = pendingReceiveScan,
            onPendingReceiveScanConsumed = onPendingReceiveScanConsumed,
            pendingSendScan = pendingSendScan,
            onPendingSendScanConsumed = onPendingSendScanConsumed,
            pendingMintScan = pendingMintScan,
            onPendingMintScanConsumed = onPendingMintScanConsumed,
        )
    }
}

@Composable
private fun CashuNavigationBar(
    selected: TopTab,
    onSelect: (TopTab) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    NavigationBar {
        TopTab.entries.forEach { tab ->
            val isSelected = tab == selected
            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onSelect(tab)
                },
                icon = {
                    Icon(
                        imageVector = if (isSelected) tab.iconSelected else tab.iconOutlined,
                        contentDescription = tab.label,
                    )
                },
                label = { Text(tab.label) },
            )
        }
    }
}

private val TopTab.iconOutlined: ImageVector
    get() = when (this) {
        TopTab.Home -> Icons.Outlined.AccountBalanceWallet
        TopTab.History -> Icons.Outlined.History
        TopTab.Mints -> Icons.Outlined.AccountBalance
        TopTab.Settings -> Icons.Outlined.Settings
    }

private val TopTab.iconSelected: ImageVector
    get() = when (this) {
        TopTab.Home -> Icons.Filled.AccountBalanceWallet
        TopTab.History -> Icons.Filled.History
        TopTab.Mints -> Icons.Filled.AccountBalance
        TopTab.Settings -> Icons.Filled.Settings
    }

@Suppress("unused")
internal val DefaultStartTabRoute = Routes.HOME
