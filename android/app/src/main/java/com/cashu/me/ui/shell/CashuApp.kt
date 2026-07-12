package com.cashu.me.ui.shell

import androidx.activity.compose.BackHandler
import androidx.activity.compose.ReportDrawnWhen
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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.StateFlow
import com.cashu.me.App.AppContainer
import com.cashu.me.Core.Navigation.CashuRoute
import com.cashu.me.Core.PaymentRequestDecodeResult
import com.cashu.me.Core.PaymentRequestDecoder
import com.cashu.me.Core.TokenParser
import com.cashu.me.Views.Components.ScannerView
import com.cashu.me.Views.Send.ContactlessPayView
import com.cashu.me.ui.components.InlineNotice
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.navigation.Routes
import com.cashu.me.ui.navigation.TopTab
import com.cashu.me.ui.navigation.cashuRequestDetailRouteFor
import com.cashu.me.ui.navigation.navigateToTab
import com.cashu.me.ui.navigation.shellBackAction
import com.cashu.me.ui.onboarding.OnboardingScreen
import com.cashu.me.ui.receive.ReceiveEcashDetailScreen
import com.cashu.me.ui.receive.ReceiveEcashScreen
import com.cashu.me.ui.receive.ReceiveLightningScreen
import com.cashu.me.ui.send.SendEcashScreen
import com.cashu.me.ui.send.UnifiedSendScreen
import com.cashu.me.ui.security.AppLockGate
import com.cashu.me.ui.security.PrivacyCover
import com.cashu.me.ui.security.SecureWindowEffect
import com.cashu.me.ui.theme.CashuTheme

/**
 * Top-level entry. Replaces `App.ContentView.CashuWalletApp`.
 *
 * Gating order matches iOS:
 *   - `!isInitialized`  → centered spinner
 *   - `needsOnboarding` → full-screen onboarding (no bottom nav)
 *   - otherwise         → 3-tab `WalletScaffold` over a `NavHost`
 *
 * Scanner and Contactless render as full-screen overlays driven by shell state;
 * the money flows (Send / Send Ecash / Receive Ecash / Receive Lightning) are
 * native modal bottom sheets hosted by [WalletFlowSheetHost] (iOS sheet parity).
 */
@Composable
fun CashuApp(containerFlow: StateFlow<AppContainer?>) {
    CashuTheme {
        val container by containerFlow.collectAsState()
        if (container == null) {
            LoadingScreen()
        } else {
            CashuAppContent(container = checkNotNull(container))
        }
    }
}

@Composable
private fun CashuAppContent(container: AppContainer) {
    val walletState by container.walletManager.state.collectAsState()
    val settings by container.settingsManager.state.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current
    val isAuthenticated = walletState.isInitialized && !walletState.needsOnboarding
    val isRuntimeReady = isAuthenticated && walletState.isRuntimeReady
    // StartupTimingMetric's full-display boundary: cached balance and
    // transaction history have both been decoded into WalletState.
    ReportDrawnWhen { walletState.isInitialized }
    SecureWindowEffect(enabled = settings.appLockEnabled)

    LaunchedEffect(Unit) {
        container.walletManager.initialize()
    }
    LaunchedEffect(isAuthenticated) {
        if (isAuthenticated) {
            container.appLockManager.startAuthenticatedSession()
        } else {
            container.appLockManager.endAuthenticatedSession()
        }
    }

    LaunchedEffect(isRuntimeReady) {
        if (isRuntimeReady) {
            container.cashuRequestListener.start()
            val settings = container.settingsManager.state.value
            if (settings.checkPendingOnStartup && settings.checkSentTokens) {
                container.walletManager.checkAllPendingTokens()
            }
        } else {
            container.cashuRequestListener.stop()
        }
    }
    DisposableEffect(lifecycleOwner, isRuntimeReady) {
        val observer = LifecycleEventObserver { _, event ->
            if (!isRuntimeReady) return@LifecycleEventObserver
            when (event) {
                Lifecycle.Event.ON_START,
                Lifecycle.Event.ON_RESUME -> {
                    container.appLockManager.appBecameActive()
                    container.cashuRequestListener.start()
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> {
                    container.appLockManager.appResignedActive()
                    if (event == Lifecycle.Event.ON_STOP) {
                        container.cashuRequestListener.stop()
                    }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            container.cashuRequestListener.stop()
        }
    }

    // Root gating cross-fades (fade-through) instead of hard-cutting.
    val gate = when {
        !walletState.isInitialized -> AppGate.Loading
        walletState.needsOnboarding -> AppGate.Onboarding
        else -> AppGate.Shell
    }
    Box(modifier = Modifier.fillMaxSize()) {
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
                AppGate.Shell -> AuthenticatedShell(
                    container = container,
                    isRuntimeReady = walletState.isRuntimeReady,
                    runtimeError = walletState.errorMessage,
                )
            }
        }
        // Covers pushed nav destinations and the base shell; money-flow sheets
        // mount their own host below since ModalBottomSheet renders in a
        // separate Android Window this one can't reach.
        SnackbarHost(
            hostState = container.snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

private enum class AppGate { Loading, Onboarding, Shell }

@Composable
private fun AuthenticatedShell(
    container: AppContainer,
    isRuntimeReady: Boolean,
    runtimeError: String?,
) {
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
    var receiveDetailDismissLocked by remember { mutableStateOf(false) }

    // A fresh flow starts unlocked, whatever the last one left behind.
    LaunchedEffect(activeFlow) { flowDismissLocked = false }
    LaunchedEffect(receiveTokenDetail) { receiveDetailDismissLocked = false }

    val pendingDeepLink by container.navigationManager.pendingDeepLink.collectAsState()
    val connectivityState by container.connectivityObserver.state.collectAsState()
    val appLockState by container.appLockManager.state.collectAsState()

    LaunchedEffect(pendingDeepLink, isRuntimeReady) {
        // Keep external actions queued until the CDK repository is installed.
        // Consuming the link earlier would compose a payment screen that can
        // only fail with "Wallet repository is not initialized."
        if (!isRuntimeReady) return@LaunchedEffect
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
            CashuRoute.Settings -> navController.navigate(Routes.SETTINGS)
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
        // Remember the last payload so exit animates with content intact.
        var lastReceiveTokenDetail by remember { mutableStateOf("") }
        if (receiveTokenDetail != null) lastReceiveTokenDetail = receiveTokenDetail!!
        AnimatedVisibility(
            visible = receiveTokenDetail != null,
            enter = overlayEnter,
            exit = overlayExit,
        ) {
            ReceiveEcashDetailScreen(
                walletManager = container.walletManager,
                settingsManager = container.settingsManager,
                priceService = container.priceService,
                payload = lastReceiveTokenDetail,
                onDone = { receiveTokenDetail = null },
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
        BackHandler(enabled = !appLockState.isLocked && (receiveTokenDetail != null || activeScannerTarget != null || showContactless)) {
            when (shellBackAction(receiveTokenDetail != null, activeScannerTarget != null, showContactless)) {
                com.cashu.me.ui.navigation.ShellBackAction.CloseReceiveDetail -> {
                    // Never abandon a redeem in flight.
                    if (!receiveDetailDismissLocked) receiveTokenDetail = null
                }
                com.cashu.me.ui.navigation.ShellBackAction.CloseScanner -> scannerTarget = null
                com.cashu.me.ui.navigation.ShellBackAction.CloseContactless -> showContactless = false
                null -> Unit
            }
        }
        if (!isRuntimeReady) {
            WalletRuntimeGate(
                errorMessage = runtimeError,
                onRetry = {
                    container.walletManager.launch {
                        container.walletManager.initialize()
                    }
                },
            )
        }
        // Security surfaces always remain above the runtime gate so cached
        // wallet content is never exposed while App Lock is active.
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
        snackbarHostState = container.snackbarHostState,
    ) { flow, close ->
        when (flow) {
            WalletFlow.ReceiveEcash -> ReceiveEcashScreen(
                walletManager = container.walletManager,
                settingsManager = container.settingsManager,
                nostrService = container.nostrService,
                cashuRequestStore = container.cashuRequestStore,
                onOpenRequest = { id ->
                    close()
                    // Fresh (just-created, actively waiting) → arms the full-screen
                    // takeover on the first payment; history entries pass fresh=false.
                    navController.navigate(cashuRequestDetailRouteFor(id, fresh = true))
                },
                onClose = close,
                // Universal scanner (Send parity): auto-routes whatever it reads.
                // Camera overlays render in the activity window, underneath this
                // sheet's dialog window — the sheet must yield before scanning.
                onScan = {
                    close()
                    scannerTarget = ScannerTarget.Auto
                },
                // A pasted/scanned token opens the full-screen claim page — same
                // destination Send bounces a token to (iOS ReceiveTokenDetailView).
                onOpenReceiveToken = { token ->
                    close()
                    receiveTokenDetail = token
                },
                // A payable pasted into Receive is really a Send — swap the sheet
                // content to the Send flow, pre-filled (inverse of onOpenReceiveToken).
                onSendPayable = { raw ->
                    pendingSendScan = raw
                    activeFlow = WalletFlow.Send
                },
                // Bitcoin opens the mint's Lightning / on-chain receive dialog.
                onReceiveBitcoin = { activeFlow = WalletFlow.ReceiveLightning },
                prefilledPayload = pendingReceiveScan,
                onPrefilledConsumed = { pendingReceiveScan = null },
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WalletRuntimeGate(
    errorMessage: String?,
    onRetry: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f))
            // This full-screen surface deliberately consumes taps so cached
            // wallet content remains visible but cannot start CDK operations.
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CashuTheme.spacing.section),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        ) {
            if (errorMessage == null) {
                LoadingIndicator()
                Text(
                    text = "Preparing wallet…",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                InlineNotice(
                    text = "Wallet couldn't finish preparing.",
                    detail = errorMessage,
                )
                PrimaryButton(
                    text = "Retry",
                    onClick = onRetry,
                )
            }
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
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        LoadingIndicator()
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
