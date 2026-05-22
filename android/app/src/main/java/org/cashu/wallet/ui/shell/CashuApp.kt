package org.cashu.wallet.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.rememberNavController
import org.cashu.wallet.App.AppContainer
import org.cashu.wallet.Core.Navigation.CashuRoute
import org.cashu.wallet.Core.PaymentRequestDecodeResult
import org.cashu.wallet.Core.PaymentRequestDecoder
import org.cashu.wallet.Core.TokenParser
import org.cashu.wallet.Views.Components.ScannerView
import org.cashu.wallet.Views.Main.OnboardingView
import org.cashu.wallet.Views.Send.ContactlessPayView
import org.cashu.wallet.ui.navigation.Routes
import org.cashu.wallet.ui.navigation.TopTab
import org.cashu.wallet.ui.navigation.navigateToTab
import org.cashu.wallet.ui.theme.CashuTheme

/**
 * Top-level entry. Replaces `App.ContentView.CashuWalletApp`.
 *
 * Gating order matches iOS:
 *   - `!isInitialized`  → centered spinner
 *   - `needsOnboarding` → full-screen onboarding (no bottom nav)
 *   - otherwise         → 4-tab `WalletScaffold` over a `NavHost`
 *
 * Scanner and Contactless render as full-screen overlays driven by shell state,
 * matching the previous behavior. Later PRs may push them as NavHost destinations.
 */
@Composable
fun CashuApp(container: AppContainer) {
    CashuTheme {
        val walletState by container.walletManager.state.collectAsState()
        val lifecycleOwner = LocalLifecycleOwner.current
        val isAuthenticated = walletState.isInitialized && !walletState.needsOnboarding

        LaunchedEffect(Unit) {
            container.walletManager.initialize()
        }
        LaunchedEffect(isAuthenticated) {
            if (isAuthenticated) {
                container.cashuRequestListener.start()
                val settings = container.settingsManager.state.value
                if (settings.checkPendingOnStartup && settings.checkSentTokens) {
                    container.walletManager.checkAllPendingTokens()
                }
            } else {
                container.cashuRequestListener.stop()
            }
        }
        DisposableEffect(lifecycleOwner, isAuthenticated) {
            val observer = LifecycleEventObserver { _, event ->
                if (!isAuthenticated) return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_START,
                    Lifecycle.Event.ON_RESUME -> container.cashuRequestListener.start()
                    Lifecycle.Event.ON_STOP -> container.cashuRequestListener.stop()
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                container.cashuRequestListener.stop()
            }
        }

        when {
            !walletState.isInitialized -> LoadingScreen()
            walletState.needsOnboarding -> OnboardingView(walletManager = container.walletManager)
            else -> AuthenticatedShell(container = container)
        }
    }
}

@Composable
private fun AuthenticatedShell(container: AppContainer) {
    val navController = rememberNavController()
    var showContactless by remember { mutableStateOf(false) }
    var scannerTarget by remember { mutableStateOf<ScannerTarget?>(null) }
    var pendingReceiveScan by remember { mutableStateOf<String?>(null) }
    var pendingSendScan by remember { mutableStateOf<String?>(null) }
    var pendingMintScan by remember { mutableStateOf<String?>(null) }

    val pendingDeepLink by container.navigationManager.pendingDeepLink.collectAsState()
    val connectivityState by container.connectivityObserver.state.collectAsState()

    LaunchedEffect(pendingDeepLink) {
        val deepLink = pendingDeepLink ?: return@LaunchedEffect
        when (deepLink.route) {
            CashuRoute.Receive -> {
                pendingReceiveScan = deepLink.payload.orEmpty()
                navController.navigate(Routes.RECEIVE_ECASH)
            }
            CashuRoute.Send -> {
                pendingSendScan = deepLink.payload.orEmpty()
                navController.navigate(Routes.SEND_ECASH)
            }
            CashuRoute.Mints -> {
                pendingMintScan = deepLink.payload.orEmpty()
                navController.navigateToTab(TopTab.Mints)
            }
            CashuRoute.Main -> navController.navigateToTab(TopTab.Home)
            CashuRoute.History -> navController.navigateToTab(TopTab.History)
            CashuRoute.Settings -> navController.navigateToTab(TopTab.Settings)
            CashuRoute.Scanner -> scannerTarget = ScannerTarget.Auto
            CashuRoute.Contactless -> showContactless = true
        }
        container.navigationManager.consumeDeepLink()
    }

    val activeScannerTarget = scannerTarget
    when {
        showContactless -> ContactlessPayView(
            walletManager = container.walletManager,
            onClose = { showContactless = false },
            onLightningRequest = { invoice ->
                pendingSendScan = invoice
                showContactless = false
                navController.navigate(Routes.SEND_ECASH)
            },
        )
        activeScannerTarget != null -> ScannerView(
            onClose = { scannerTarget = null },
            onScanned = { payload ->
                scannerTarget = null
                routeScannedPayload(
                    target = activeScannerTarget,
                    payload = payload,
                    onReceive = {
                        pendingReceiveScan = it
                        navController.navigate(Routes.RECEIVE_ECASH)
                    },
                    onSend = {
                        pendingSendScan = it
                        navController.navigate(Routes.SEND_ECASH)
                    },
                    onMint = {
                        pendingMintScan = it
                        navController.navigateToTab(TopTab.Mints)
                    },
                )
            },
        )
        else -> WalletScaffold(
            container = container,
            connectivityState = connectivityState,
            onScan = { scannerTarget = ScannerTarget.Auto },
            onContactless = { showContactless = true },
            pendingReceiveScan = pendingReceiveScan,
            onPendingReceiveScanConsumed = { pendingReceiveScan = null },
            pendingSendScan = pendingSendScan,
            onPendingSendScanConsumed = { pendingSendScan = null },
            pendingMintScan = pendingMintScan,
            onPendingMintScanConsumed = { pendingMintScan = null },
            navController = navController,
        )
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

internal enum class ScannerTarget { Auto, Receive, Send, Mints }

private fun routeScannedPayload(
    target: ScannerTarget,
    payload: String,
    onReceive: (String) -> Unit,
    onSend: (String) -> Unit,
    onMint: (String) -> Unit,
) {
    val trimmed = payload.trim()
    when (target) {
        ScannerTarget.Receive -> {
            onReceive(TokenParser.extractToken(trimmed) ?: trimmed)
            return
        }
        ScannerTarget.Send -> {
            onSend(trimmed)
            return
        }
        ScannerTarget.Mints -> {
            onMint(trimmed)
            return
        }
        ScannerTarget.Auto -> Unit
    }
    TokenParser.extractToken(trimmed)?.let {
        onReceive(it)
        return
    }
    when (PaymentRequestDecoder.decode(trimmed, includeCashuPaymentRequests = true, preferCashuPaymentRequests = true)) {
        is PaymentRequestDecodeResult.Bolt11,
        is PaymentRequestDecodeResult.Bolt12,
        is PaymentRequestDecodeResult.CashuPaymentRequest,
        is PaymentRequestDecodeResult.LightningAddress,
        is PaymentRequestDecodeResult.Onchain -> onSend(trimmed)
        PaymentRequestDecodeResult.Unrecognized -> {
            if (trimmed.startsWith("https://", ignoreCase = true)) onMint(trimmed) else onSend(trimmed)
        }
    }
}
