package org.cashu.wallet.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import org.cashu.wallet.App.AppContainer
import org.cashu.wallet.Core.Platform.ConnectivityState
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.navigation.CashuNavHost
import org.cashu.wallet.ui.navigation.Routes
import org.cashu.wallet.ui.navigation.TopTab
import org.cashu.wallet.ui.navigation.navigateToTab

@Composable
fun WalletScaffold(
    container: AppContainer,
    connectivityState: ConnectivityState,
    onScan: () -> Unit,
    onReceiveEcash: () -> Unit,
    onReceiveLightning: () -> Unit,
    onSend: () -> Unit,
    pendingMintScan: String?,
    onPendingMintScanConsumed: () -> Unit,
    onClaimReceiveToken: (String) -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val selectedTab = TopTab.entries.firstOrNull { it.route == currentRoute }
    Scaffold(
        bottomBar = {
            // Slide the bar away on pushed destinations instead of blinking it out.
            AnimatedVisibility(
                visible = selectedTab != null,
                enter = slideInVertically(
                    spring(
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = IntOffset.VisibilityThreshold,
                    ),
                ) { it } + fadeIn(spring(stiffness = Spring.StiffnessMedium)),
                exit = slideOutVertically(
                    spring(
                        stiffness = Spring.StiffnessMediumLow,
                        visibilityThreshold = IntOffset.VisibilityThreshold,
                    ),
                ) { it } + fadeOut(spring(stiffness = Spring.StiffnessMedium)),
            ) {
                CashuNavigationBar(
                    selected = selectedTab ?: TopTab.Home,
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
            onReceiveEcash = onReceiveEcash,
            onReceiveLightning = onReceiveLightning,
            onSend = onSend,
            pendingMintScan = pendingMintScan,
            onPendingMintScanConsumed = onPendingMintScanConsumed,
            onClaimReceiveToken = onClaimReceiveToken,
        )
    }
}

@Composable
private fun CashuNavigationBar(
    selected: TopTab,
    onSelect: (TopTab) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    // Unified with the canvas: the bar shares the page background (white/black
    // inverted ink) instead of sitting on a tonal surface band; a full-bleed
    // hairline is the only separation — nearly invisible in dark mode.
    Column {
        CanvasDivider(leadingInset = 0.dp)
        NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
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
                            modifier = Modifier.size(26.dp),
                        )
                    },
                    label = { Text(tab.label, style = MaterialTheme.typography.labelLarge) },
                )
            }
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
