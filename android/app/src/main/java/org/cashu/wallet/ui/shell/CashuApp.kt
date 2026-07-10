package org.cashu.wallet.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import org.cashu.wallet.App.AppContainer
import org.cashu.wallet.Core.Navigation.CashuRoute
import org.cashu.wallet.Core.PaymentRequestDecodeResult
import org.cashu.wallet.Core.PaymentRequestDecoder
import org.cashu.wallet.Core.TokenParser
import org.cashu.wallet.Views.Components.ScannerView
import org.cashu.wallet.Views.Send.ContactlessPayView
import org.cashu.wallet.ui.onboarding.OnboardingScreen
import org.cashu.wallet.ui.navigation.TopTab
import org.cashu.wallet.ui.navigation.cashuRequestDetailRouteFor
import org.cashu.wallet.ui.navigation.navigateToTab
import org.cashu.wallet.ui.navigation.shellBackAction
import org.cashu.wallet.ui.receive.ReceiveEcashDetailScreen
import org.cashu.wallet.ui.receive.ReceiveEcashScreen
import org.cashu.wallet.ui.receive.ReceiveLightningScreen
import org.cashu.wallet.ui.send.SendEcashScreen
import org.cashu.wallet.ui.send.UnifiedSendScreen
import org.cashu.wallet.ui.security.AppLockGate
import org.cashu.wallet.ui.security.PrivacyCover
import org.cashu.wallet.ui.security.SecureWindowEffect
import org.cashu.wallet.ui.theme.CashuTheme

/**
 * Top-level entry. Replaces `App.ContentView.CashuWalletApp`.
 *
 * Gating order matches iOS:
 *   - `!isInitialized`  → centered spinner
 *   - `needsOnboarding` → full-screen onboarding (no bottom nav)
 *   - otherwise         → 4-tab `WalletScaffold` over a `NavHost`
 *
 * Scanner and Contactless render as full-screen overlays driven by shell state;
 * the money flows (Send / Send Ecash / Receive Ecash / Receive Lightning) are
 * native modal bottom sheets hosted by [WalletFlowSheetHost] (iOS sheet parity).
 */
@Composable
fun CashuApp(container: AppContainer) {
    CashuTheme {
        val walletState by container.walletManager.state.collectAsState()
        val settings by container.settingsManager.state.collectAsState()
        val lifecycleOwner = LocalLifecycleOwner.current
        val appScope = rememberCoroutineScope()
        val isAuthenticated = walletState.isInitialized && !walletState.needsOnboarding
        SecureWindowEffect(enabled = settings.appLockEnabled)

        LaunchedEffect(Unit) {
            container.walletManager.initialize()
        }
        LaunchedEffect(isAuthenticated) {
            if (isAuthenticated) {
                container.appLockManager.startAuthenticatedSession()
                container.npcService.appBecameActive()
                container.priceService.appBecameActive()
                val settings = container.settingsManager.state.value
                if (settings.checkPendingOnStartup && settings.checkSentTokens) {
                    container.walletManager.checkAllPendingTokens()
                }
                container.walletManager.syncPendingMintQuotes()
            } else {
                container.appLockManager.endAuthenticatedSession()
                container.npcService.appEnteredBackground()
                container.priceService.appEnteredBackground()
            }
        }
        LaunchedEffect(isAuthenticated, settings.enablePaymentRequests) {
            if (isAuthenticated && settings.enablePaymentRequests) {
                container.cashuRequestListener.start()
            } else {
                container.cashuRequestListener.stop()
            }
        }
        LaunchedEffect(isAuthenticated, settings.receivePaymentRequestsAutomatically) {
            if (isAuthenticated && settings.receivePaymentRequestsAutomatically) {
                container.cashuRequestListener.claimEligibleHeldPayments()
            }
        }
        DisposableEffect(lifecycleOwner, isAuthenticated) {
            val observer = LifecycleEventObserver { _, event ->
                if (!isAuthenticated) return@LifecycleEventObserver
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        container.appLockManager.appBecameActive()
                        container.cashuRequestListener.start()
                        container.npcService.appBecameActive()
                        container.priceService.appBecameActive()
                        appScope.launch {
                            val settings = container.settingsManager.state.value
                            if (settings.checkPendingOnStartup && settings.checkSentTokens) {
                                container.walletManager.checkAllPendingTokens()
                            }
                            container.walletManager.syncPendingMintQuotes()
                        }
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        container.appLockManager.appBecameActive()
                        container.cashuRequestListener.start()
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        container.appLockManager.appResignedActive()
                    }
                    Lifecycle.Event.ON_STOP -> {
                        container.appLockManager.appResignedActive()
                        container.cashuRequestListener.stop()
                        container.npcService.appEnteredBackground()
                        container.priceService.appEnteredBackground()
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                container.cashuRequestListener.stop()
                container.npcService.appEnteredBackground()
                container.priceService.appEnteredBackground()
            }
        }

        // Root gating cross-fades (fade-through) instead of hard-cutting.
        val gate = when {
            !walletState.isInitialized -> AppGate.Loading
            walletState.needsOnboarding -> AppGate.Onboarding
            else -> AppGate.Shell
        }
        AnimatedContent(
            targetState = gate,
            transitionSpec = {
                (fadeIn(spring(stiffness = Spring.StiffnessMedium)) +
                    scaleIn(initialScale = 0.98f, animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                    .togetherWith(fadeOut(spring(stiffness = Spring.StiffnessMedium)))
            },
            label = "app-gate",
        ) { target ->
            when (target) {
                AppGate.Loading -> LoadingScreen()
                AppGate.Onboarding -> OnboardingScreen(
                    walletManager = container.walletManager,
                    nostrMintBackupService = container.nostrMintBackupService,
                )
                AppGate.Shell -> AuthenticatedShell(container = container)
            }
        }
    }
}

private enum class AppGate { Loading, Onboarding, Shell }

@Composable
private fun AuthenticatedShell(container: AppContainer) {
    val navController = rememberNavController()
    var showContactless by remember { mutableStateOf(false) }
    var scannerTarget by remember { mutableStateOf<ScannerTarget?>(null) }
    // The active money flow, hosted in a modal bottom sheet (iOS WalletFlow sheets).
    var activeFlow by remember { mutableStateOf<WalletFlow?>(null) }
    var flowDismissLocked by remember { mutableStateOf(false) }
    var pendingReceiveScan by remember { mutableStateOf<String?>(null) }
    var pendingSendScan by remember { mutableStateOf<String?>(null) }
    var pendingMintScan by remember { mutableStateOf<String?>(null) }
    // Full-screen "Receive Ecash" page (iOS ReceiveTokenDetailView via
    // .fullScreenCover): every token that arrives from *outside* the paste
    // flow — scanner, cashu: deep link, token pasted into Send — lands here.
    // The Receive sheet's Review face survives only for the paste flow and
    // its in-sheet scan.
    var receiveTokenDetail by remember { mutableStateOf<String?>(null) }
    // Local presentation copy, like iOS ContentView.claimApproval. The
    // listener's StateFlow is only the one-shot offer; keeping a local item
    // lets the success terminal finish even after the pending record is claimed.
    var claimApproval by remember { mutableStateOf<org.cashu.wallet.Models.PendingReceiveToken?>(null) }
    var receiveDetailDismissLocked by remember { mutableStateOf(false) }

    // A fresh flow starts unlocked, whatever the last one left behind.
    LaunchedEffect(activeFlow) { flowDismissLocked = false }
    LaunchedEffect(receiveTokenDetail) { receiveDetailDismissLocked = false }

    val pendingDeepLink by container.navigationManager.pendingDeepLink.collectAsState()
    val connectivityState by container.connectivityObserver.state.collectAsState()
    val appLockState by container.appLockManager.state.collectAsState()
    val cashuRequestListenerState by container.cashuRequestListener.state.collectAsState()
    val walletState by container.walletManager.state.collectAsState()

    LaunchedEffect(
        cashuRequestListenerState.heldForApproval,
        receiveTokenDetail,
        claimApproval,
        walletState.pendingReceiveTokens,
    ) {
        val offered = cashuRequestListenerState.heldForApproval ?: return@LaunchedEffect
        val isStillPending = walletState.pendingReceiveTokens.any { it.tokenId == offered.tokenId }
        if (!isStillPending && claimApproval == null) {
            container.cashuRequestListener.dismissHeldPayment()
        } else if (claimApproval == null && receiveTokenDetail == null) {
            claimApproval = offered
        }
    }

    LaunchedEffect(pendingDeepLink) {
        val deepLink = pendingDeepLink ?: return@LaunchedEffect
        when (deepLink.route) {
            CashuRoute.Receive -> {
                val payload = deepLink.payload.orEmpty()
                if (payload.isNotBlank()) {
                    // Deep-linked token: full-screen claim page (iOS presents
                    // ReceiveTokenDetailView via .fullScreenCover from ContentView).
                    receiveTokenDetail = payload
                } else {
                    // Bare cashu: link with no token — open the paste sheet.
                    activeFlow = WalletFlow.ReceiveEcash
                }
            }
            CashuRoute.Send -> {
                pendingSendScan = deepLink.payload.orEmpty()
                activeFlow = WalletFlow.Send
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
    // The shell stays mounted; camera surfaces animate over it (slide-up + fade)
    // instead of replacing it with a one-frame cut.
    var lastScannerTarget by remember { mutableStateOf(ScannerTarget.Auto) }
    if (activeScannerTarget != null) lastScannerTarget = activeScannerTarget

    Box(modifier = Modifier.fillMaxSize()) {
        WalletScaffold(
            container = container,
            connectivityState = connectivityState,
            onScan = { scannerTarget = ScannerTarget.Auto },
            onReceiveEcash = { activeFlow = WalletFlow.ReceiveEcash },
            onReceiveLightning = { activeFlow = WalletFlow.ReceiveLightning },
            onSend = { activeFlow = WalletFlow.Send },
            pendingMintScan = pendingMintScan,
            onPendingMintScanConsumed = { pendingMintScan = null },
            // Pending "Receive later" tokens claim on the full-screen page.
            onClaimReceiveToken = { receiveTokenDetail = it },
            navController = navController,
        )
        AnimatedVisibility(
            visible = showContactless,
            enter = overlayEnter,
            exit = overlayExit,
        ) {
            ContactlessPayView(
                walletManager = container.walletManager,
                onClose = { showContactless = false },
                onLightningRequest = { invoice ->
                    pendingSendScan = invoice
                    showContactless = false
                    activeFlow = WalletFlow.Send
                },
            )
        }
        AnimatedVisibility(
            visible = activeScannerTarget != null,
            enter = overlayEnter,
            exit = overlayExit,
        ) {
            ScannerView(
                onClose = { scannerTarget = null },
                onScanned = { payload ->
                    scannerTarget = null
                    routeScannedPayload(
                        target = lastScannerTarget,
                        payload = payload,
                        // In-sheet scan (ScannerTarget.Receive): back to the
                        // sheet's Review face — the user is inside the paste flow.
                        onReceiveInSheet = {
                            pendingReceiveScan = it
                            activeFlow = WalletFlow.ReceiveEcash
                        },
                        // Main scan button: tokens read as a brand-new full
                        // screen, never the home sheet (iOS scanner parity).
                        onReceiveDetail = { receiveTokenDetail = it },
                        onSend = {
                            pendingSendScan = it
                            activeFlow = WalletFlow.Send
                        },
                        onMint = {
                            pendingMintScan = it
                            navController.navigateToTab(TopTab.Mints)
                        },
                    )
                },
            )
        }
        // Full-screen Receive Ecash claim page — rendered above the camera
        // overlays (scanner closes before routing, so no live camera shows
        // behind, matching the iOS fullScreenCover rationale).
        // Incoming NUT-18 payments that need consent reuse this same native
        // surface, but replace "Receive later" with an explicit Decline action.
        // An already-open external token stays in front; the held payment is
        // offered after it closes, matching iOS's present-if-idle behavior.
        val heldForApproval = claimApproval.takeIf { receiveTokenDetail == null }
        val activeReceivePayload = receiveTokenDetail ?: heldForApproval?.token
        fun dismissReceiveOverlay() {
            if (heldForApproval != null) {
                container.cashuRequestListener.dismissHeldPayment()
                claimApproval = null
            } else {
                receiveTokenDetail = null
            }
        }
        // Remember the last payload so exit animates with content intact.
        var lastReceiveTokenDetail by remember { mutableStateOf("") }
        if (activeReceivePayload != null) lastReceiveTokenDetail = activeReceivePayload
        AnimatedVisibility(
            visible = activeReceivePayload != null,
            enter = overlayEnter,
            exit = overlayExit,
        ) {
            ReceiveEcashDetailScreen(
                walletManager = container.walletManager,
                settingsManager = container.settingsManager,
                priceService = container.priceService,
                payload = lastReceiveTokenDetail,
                onDone = ::dismissReceiveOverlay,
                claimOverride = heldForApproval?.let { pending ->
                    { container.cashuRequestListener.claimHeldPayment(pending) }
                },
                secondaryActionTitle = if (heldForApproval != null) "Decline" else "Receive later",
                onSecondaryAction = heldForApproval?.let { pending ->
                    {
                        container.cashuRequestListener.declineHeldPayment(pending)
                        claimApproval = null
                    }
                },
                onDismissLockChanged = { receiveDetailDismissLocked = it },
            )
        }
        // System back (including predictive back) must dismiss the topmost overlay
        // instead of popping the NavHost — or exiting the app — underneath it.
        // Declared after WalletScaffold so this callback registers last on the
        // OnBackPressedDispatcher and takes precedence over NavHost back handling
        // while an overlay is visible. Receive detail renders above the scanner,
        // which renders above Contactless — dismissal order matches. (Flow
        // sheets live in their own window and handle back themselves.)
        BackHandler(enabled = !appLockState.isLocked && (activeReceivePayload != null || activeScannerTarget != null || showContactless)) {
            when (shellBackAction(activeReceivePayload != null, activeScannerTarget != null, showContactless)) {
                org.cashu.wallet.ui.navigation.ShellBackAction.CloseReceiveDetail -> {
                    // Never abandon a redeem in flight.
                    if (!receiveDetailDismissLocked) dismissReceiveOverlay()
                }
                org.cashu.wallet.ui.navigation.ShellBackAction.CloseScanner -> scannerTarget = null
                org.cashu.wallet.ui.navigation.ShellBackAction.CloseContactless -> showContactless = false
                null -> Unit
            }
        }
        if (appLockState.isObscured && !appLockState.isLocked) {
            PrivacyCover()
        }
        if (appLockState.isLocked) {
            AppLockGate(appLockManager = container.appLockManager)
        }
    }

    WalletFlowSheetHost(
        flow = activeFlow,
        dismissLocked = flowDismissLocked,
        onDismissed = { activeFlow = null },
    ) { flow, close ->
        when (flow) {
            WalletFlow.ReceiveEcash -> ReceiveEcashScreen(
                walletManager = container.walletManager,
                settingsManager = container.settingsManager,
                nostrService = container.nostrService,
                cashuRequestStore = container.cashuRequestStore,
                onOpenRequest = { id ->
                    close()
                    navController.navigate(cashuRequestDetailRouteFor(id))
                },
                onClose = close,
                // Camera overlays render in the activity window, underneath this
                // sheet's dialog window — the sheet must yield before scanning.
                onScan = {
                    close()
                    scannerTarget = ScannerTarget.Receive
                },
                prefilledPayload = pendingReceiveScan,
                onPrefilledConsumed = { pendingReceiveScan = null },
                onDismissLockChanged = { flowDismissLocked = it },
            )

            WalletFlow.ReceiveLightning -> ReceiveLightningScreen(
                walletManager = container.walletManager,
                settingsManager = container.settingsManager,
                onClose = close,
            )

            WalletFlow.Send -> UnifiedSendScreen(
                walletManager = container.walletManager,
                settingsManager = container.settingsManager,
                onClose = close,
                onScan = {
                    close()
                    scannerTarget = ScannerTarget.Auto
                },
                onContactless = {
                    close()
                    showContactless = true
                },
                onSendEcash = { activeFlow = WalletFlow.SendEcash },
                onOpenReceiveToken = { token ->
                    // A token pasted into Send is a receive: bounce it to the
                    // full-screen claim page (iOS SendRoute.receiveToken →
                    // fullScreenCover), closing the Send sheet.
                    close()
                    receiveTokenDetail = token
                },
                onOpenMints = {
                    close()
                    navController.navigateToTab(TopTab.Mints)
                },
                onReceive = { activeFlow = WalletFlow.ReceiveEcash },
                prefilledPayload = pendingSendScan,
                onPrefilledConsumed = { pendingSendScan = null },
                onDismissLockChanged = { flowDismissLocked = it },
            )

            WalletFlow.SendEcash -> SendEcashScreen(
                walletManager = container.walletManager,
                settingsManager = container.settingsManager,
                priceService = container.priceService,
                onBack = { activeFlow = WalletFlow.Send },
                onClose = close,
                onDismissLockChanged = { flowDismissLocked = it },
            )
        }
    }
}

// Camera-surface overlay motion: slide up over the shell, slide back down on close.
private val overlayEnter = slideInVertically(
    spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
) { it / 5 } + fadeIn(spring(stiffness = Spring.StiffnessMedium))
private val overlayExit = slideOutVertically(
    spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold),
) { it / 5 } + fadeOut(spring(stiffness = Spring.StiffnessMedium))

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LoadingIndicator()
            Text(
                text = "Loading Wallet…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
        }
    }
}

internal enum class ScannerTarget { Auto, Receive, Send, Mints }

private fun routeScannedPayload(
    target: ScannerTarget,
    payload: String,
    onReceiveInSheet: (String) -> Unit,
    onReceiveDetail: (String) -> Unit,
    onSend: (String) -> Unit,
    onMint: (String) -> Unit,
) {
    val trimmed = payload.trim()
    when (target) {
        ScannerTarget.Receive -> {
            onReceiveInSheet(TokenParser.extractToken(trimmed) ?: trimmed)
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
        onReceiveDetail(it)
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
