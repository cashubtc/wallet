package org.cashu.wallet.ui.navigation

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import org.cashu.wallet.App.AppContainer
import org.cashu.wallet.Core.Platform.ConnectivityState
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import androidx.navigation.NavType
import androidx.navigation.navArgument
import org.cashu.wallet.ui.history.HistoryScreen
import org.cashu.wallet.ui.history.TransactionDetailScreen
import org.cashu.wallet.ui.home.HomeScreen
import org.cashu.wallet.ui.home.ReceiveAction
import org.cashu.wallet.ui.mints.MintDetailScreen
import org.cashu.wallet.ui.mints.MintsScreen
import org.cashu.wallet.ui.receive.CashuRequestDetailScreen
import org.cashu.wallet.ui.settings.AdvancedKeysScreen
import org.cashu.wallet.ui.settings.BackupRestoreScreen
import org.cashu.wallet.ui.settings.BackupScreen
import org.cashu.wallet.ui.settings.DeviceKeyDetailScreen
import org.cashu.wallet.ui.settings.LightningScreen
import org.cashu.wallet.ui.settings.NostrScreen
import org.cashu.wallet.ui.settings.P2PKScreen
import org.cashu.wallet.ui.settings.PrivacyScreen
import org.cashu.wallet.ui.settings.SettingsScreen

/**
 * The NavHost. Tabs + pushed detail destinations only — the money flows
 * (Send, Send Ecash, Receive Ecash, Receive Lightning) are native modal
 * bottom sheets hosted by the shell (see `ui.shell.WalletFlowSheetHost`),
 * and Scanner/Contactless are shell overlays.
 */
@Composable
fun CashuNavHost(
    navController: NavHostController,
    container: AppContainer,
    connectivityState: ConnectivityState,
    contentPadding: PaddingValues,
    onScan: () -> Unit,
    onReceiveEcash: () -> Unit,
    onReceiveLightning: () -> Unit,
    onSend: () -> Unit,
    pendingMintScan: String?,
    onPendingMintScanConsumed: () -> Unit,
    onClaimReceiveToken: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
        // Shared-axis X for pushed destinations, spring-driven (M3 Expressive).
        enterTransition = pushEnter,
        exitTransition = pushExit,
        popEnterTransition = popEnter,
        popExitTransition = popExit,
    ) {
        tabDestinations(
            navController = navController,
            container = container,
            connectivityState = connectivityState,
            contentPadding = contentPadding,
            onScan = onScan,
            onReceiveEcash = onReceiveEcash,
            onReceiveLightning = onReceiveLightning,
            onSend = onSend,
            pendingMintScan = pendingMintScan,
            onPendingMintScanConsumed = onPendingMintScanConsumed,
        )
        composable(
            route = Routes.MINT_DETAIL,
            arguments = listOf(navArgument("mintUrl") { type = NavType.StringType }),
        ) { entry ->
            val encoded = entry.arguments?.getString("mintUrl").orEmpty()
            val mintUrl = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
            MintDetailScreen(
                walletManager = container.walletManager,
                mintUrl = mintUrl,
                onClose = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.TRANSACTION_DETAIL,
            arguments = listOf(navArgument("transactionId") { type = NavType.StringType }),
        ) { entry ->
            val encoded = entry.arguments?.getString("transactionId").orEmpty()
            val txId = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
            TransactionDetailScreen(
                walletManager = container.walletManager,
                settingsManager = container.settingsManager,
                transactionId = txId,
                onClose = { navController.popBackStack() },
                onClaimReceiveToken = onClaimReceiveToken,
            )
        }
        composable(
            route = Routes.CASHU_REQUEST_DETAIL,
            arguments = listOf(navArgument("requestId") { type = NavType.StringType }),
        ) { entry ->
            val encoded = entry.arguments?.getString("requestId").orEmpty()
            val requestId = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
            CashuRequestDetailScreen(
                walletManager = container.walletManager,
                settingsManager = container.settingsManager,
                cashuRequestStore = container.cashuRequestStore,
                requestId = requestId,
                onClose = { navController.popBackStack() },
            )
        }

        // Settings sub-screens
        composable(Routes.SETTINGS_BACKUP_RESTORE) {
            BackupRestoreScreen(
                walletManager = container.walletManager,
                settingsManager = container.settingsManager,
                nostrMintBackupService = container.nostrMintBackupService,
                onOpenBackup = { navController.navigate(Routes.SETTINGS_BACKUP) },
                onClose = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_BACKUP) {
            BackupScreen(
                walletManager = container.walletManager,
                onClose = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_LIGHTNING) {
            LightningScreen(
                walletManager = container.walletManager,
                npcService = container.npcService,
                onClose = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_P2PK) {
            P2PKScreen(
                settingsManager = container.settingsManager,
                nostrService = container.nostrService,
                onOpenAdvancedKeys = { navController.navigate(Routes.SETTINGS_P2PK_ADVANCED) },
                onClose = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_P2PK_ADVANCED) {
            AdvancedKeysScreen(
                settingsManager = container.settingsManager,
                onOpenKey = { keyId ->
                    navController.navigate(
                        Routes.SETTINGS_P2PK_KEY.replace(
                            "{keyId}",
                            URLEncoder.encode(keyId, StandardCharsets.UTF_8.name()),
                        ),
                    )
                },
                onClose = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.SETTINGS_P2PK_KEY,
            arguments = listOf(navArgument("keyId") { type = NavType.StringType }),
        ) { entry ->
            val encoded = entry.arguments?.getString("keyId").orEmpty()
            val keyId = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
            DeviceKeyDetailScreen(
                settingsManager = container.settingsManager,
                keyId = keyId,
                onClose = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_NOSTR) {
            NostrScreen(
                nostrService = container.nostrService,
                settingsManager = container.settingsManager,
                onClose = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_PRIVACY) {
            PrivacyScreen(
                settingsManager = container.settingsManager,
                onClose = { navController.popBackStack() },
            )
        }
    }
}

internal fun mintDetailRouteFor(mintUrl: String): String {
    val encoded = URLEncoder.encode(mintUrl, StandardCharsets.UTF_8.name())
    return Routes.MINT_DETAIL.replace("{mintUrl}", encoded)
}

internal fun transactionDetailRouteFor(transactionId: String): String {
    val encoded = URLEncoder.encode(transactionId, StandardCharsets.UTF_8.name())
    return Routes.TRANSACTION_DETAIL.replace("{transactionId}", encoded)
}

internal fun cashuRequestDetailRouteFor(requestId: String): String {
    val encoded = URLEncoder.encode(requestId, StandardCharsets.UTF_8.name())
    return Routes.CASHU_REQUEST_DETAIL.replace("{requestId}", encoded)
}

private fun NavGraphBuilder.tabDestinations(
    navController: NavHostController,
    container: AppContainer,
    connectivityState: ConnectivityState,
    contentPadding: PaddingValues,
    onScan: () -> Unit,
    onReceiveEcash: () -> Unit,
    onReceiveLightning: () -> Unit,
    onSend: () -> Unit,
    pendingMintScan: String?,
    onPendingMintScanConsumed: () -> Unit,
) {
    composable(
        route = Routes.HOME,
        enterTransition = tabEnter,
        exitTransition = tabExit,
        popEnterTransition = tabEnter,
        popExitTransition = tabExit,
    ) {
        HomeScreen(
            walletManager = container.walletManager,
            settingsManager = container.settingsManager,
            priceService = container.priceService,
            cashuRequestStore = container.cashuRequestStore,
            onOpenMints = { navController.navigateToTab(TopTab.Mints) },
            onOpenHistory = { navController.navigateToTab(TopTab.History) },
            onOpenTransaction = { tx ->
                navController.navigate(transactionDetailRouteFor(tx.id))
            },
            onOpenCashuRequest = { req ->
                navController.navigate(cashuRequestDetailRouteFor(req.id))
            },
            onReceive = { action ->
                when (action) {
                    ReceiveAction.Ecash -> onReceiveEcash()
                    ReceiveAction.Bitcoin -> onReceiveLightning()
                }
            },
            // Send goes straight to the unified surface — no chooser (iOS parity).
            onSend = onSend,
            onScan = onScan,
            contentPadding = contentPadding,
        )
    }
    composable(
        route = Routes.HISTORY,
        enterTransition = tabEnter,
        exitTransition = tabExit,
        popEnterTransition = tabEnter,
        popExitTransition = tabExit,
    ) {
        HistoryScreen(
            walletManager = container.walletManager,
            settingsManager = container.settingsManager,
            priceService = container.priceService,
            cashuRequestStore = container.cashuRequestStore,
            onOpenTransaction = { tx ->
                navController.navigate(transactionDetailRouteFor(tx.id))
            },
            onOpenCashuRequest = { req ->
                navController.navigate(cashuRequestDetailRouteFor(req.id))
            },
            contentPadding = contentPadding,
        )
    }
    composable(
        route = Routes.MINTS,
        enterTransition = tabEnter,
        exitTransition = tabExit,
        popEnterTransition = tabEnter,
        popExitTransition = tabExit,
    ) {
        MintsScreen(
            walletManager = container.walletManager,
            settingsManager = container.settingsManager,
            mintDiscoveryManager = container.mintDiscoveryManager,
            onOpenMint = { mint -> navController.navigate(mintDetailRouteFor(mint.url)) },
            onScan = onScan,
            contentPadding = contentPadding,
            scannedMintUrl = pendingMintScan,
            onScannedMintUrlConsumed = onPendingMintScanConsumed,
        )
    }
    composable(
        route = Routes.SETTINGS,
        enterTransition = tabEnter,
        exitTransition = tabExit,
        popEnterTransition = tabEnter,
        popExitTransition = tabExit,
    ) {
        SettingsScreen(
            walletManager = container.walletManager,
            settingsManager = container.settingsManager,
            priceService = container.priceService,
            onOpenBackupRestore = { navController.navigate(Routes.SETTINGS_BACKUP_RESTORE) },
            onOpenLightning = { navController.navigate(Routes.SETTINGS_LIGHTNING) },
            onOpenLockedEcash = { navController.navigate(Routes.SETTINGS_P2PK) },
            onOpenNostr = { navController.navigate(Routes.SETTINGS_NOSTR) },
            onOpenPrivacy = { navController.navigate(Routes.SETTINGS_PRIVACY) },
            contentPadding = contentPadding,
        )
    }
}

/** Navigate to a top-level tab, popping back to the start destination and saving state. */
fun NavHostController.navigateToTab(tab: TopTab) {
    navigate(tab.route) {
        popUpTo(graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

// ---------------------------------------------------------------------------
// Motion: shared-axis X (push/pop) + fade-through (tab switches), all springs.
// ---------------------------------------------------------------------------

private val slideSpring = spring(
    stiffness = Spring.StiffnessMediumLow,
    visibilityThreshold = IntOffset.VisibilityThreshold,
)
private val fadeSpring = spring<Float>(stiffness = Spring.StiffnessMedium)

private val pushEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(slideSpring) { it / 4 } + fadeIn(fadeSpring)
}
private val pushExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(slideSpring) { -it / 4 } + fadeOut(fadeSpring)
}
private val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(slideSpring) { -it / 4 } + fadeIn(fadeSpring)
}
private val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(slideSpring) { it / 4 } + fadeOut(fadeSpring)
}

/** M3 fade-through between sibling tabs (fade + 98% scale settle, no slide). */
internal val tabEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    fadeIn(fadeSpring) + scaleIn(initialScale = 0.98f, animationSpec = fadeSpring)
}
internal val tabExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    fadeOut(fadeSpring)
}
