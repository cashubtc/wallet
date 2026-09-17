import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

#if os(iOS)
/// Holds a UIKit background-task assertion across an async wallet-DB write so iOS grants a
/// short grace window to finish instead of suspending the app mid-write. A SQLite lock held
/// across suspension is the classic trigger for a `0xdead10cc` termination (which testers
/// experience as a crash). The assertion is always ended via `defer`, including on throw.
@MainActor
func withBackgroundWriteAssertion<T>(
    _ name: String,
    _ work: () async throws -> T
) async rethrows -> T {
    let app = UIApplication.shared
    var taskId: UIBackgroundTaskIdentifier = .invalid
    taskId = app.beginBackgroundTask(withName: name) {
        if taskId != .invalid {
            app.endBackgroundTask(taskId)
            taskId = .invalid
        }
    }
    defer {
        if taskId != .invalid {
            app.endBackgroundTask(taskId)
            taskId = .invalid
        }
    }
    return try await work()
}
#else
/// macOS has no app suspension, so there is no assertion to take: a background
/// process keeps running and the wallet DB write finishes on its own. The
/// wrapper stays so the shared call sites read identically on both platforms.
@MainActor
func withBackgroundWriteAssertion<T>(
    _ name: String,
    _ work: () async throws -> T
) async rethrows -> T {
    try await work()
}
#endif

@main
struct CashuWalletApp: App {
    #if os(macOS)
    // The wallet lives in a menu bar panel rather than a scene, so the delegate
    // owns the window. See MacMenuBarController for why a panel and not a
    // MenuBarExtra.
    @NSApplicationDelegateAdaptor(MacMenuBarAppDelegate.self) private var appDelegate
    #endif

    init() {
        // Repair stdout/stderr before anything can touch the CDK FFI, so a broken
        // stderr can't turn a Rust log write into a bogus "failed printing to stderr"
        // panic that masks the real error. See AppLogger for the full rationale.
        AppLogger.redirectStandardStreamsIfNeeded()
        if IntegrationTestConfig.shouldDisableAnimations {
            UIView.setAnimationsEnabled(false)
        }
        #if DEBUG
        // A fresh UI-test wallet must not inherit App Lock from a previous
        // test. Reset before either settings or the lock manager is created;
        // persistence relaunches omit RESET_WALLET and keep the saved setting.
        // `App.init` runs before `AppRootView` is built, which is what keeps
        // that ordering true now that the managers are owned by the root view.
        if IntegrationTestConfig.isEnabled && IntegrationTestConfig.shouldResetWallet {
            SettingsStore.shared.appLockEnabled = false
        }
        #endif
    }

    var body: some Scene {
        #if os(macOS)
        // `Settings` is an inert placeholder: `App` requires a scene, and with
        // LSUIElement there is no menu to reach it from. Every visible surface
        // is presented by the delegate's panel.
        Settings { EmptyView() }
        #else
        WindowGroup {
            #if DEBUG
            if IntegrationTestConfig.shouldShowComponentCatalog {
                ComponentCatalogRootView()
            } else {
                AppRootView()
            }
            #else
            AppRootView()
            #endif
        }
        #endif
    }
}

#if DEBUG
/// Stands in for `AppRootView` in catalog mode. It owns its own `WalletManager`
/// because the managers moved onto the root view; the two modes are mutually
/// exclusive, so only one is ever constructed.
private struct ComponentCatalogRootView: View {
    @StateObject private var walletManager = WalletManager()

    var body: some View {
        ComponentCatalogView(
            page: .init(rawValue: IntegrationTestConfig.componentCatalogPage)
        )
        .environmentObject(walletManager)
    }
}
#endif

/// The real app root, shared by the iOS scene and the macOS menu bar panel.
///
/// It owns the app-wide managers because on macOS there is no `WindowGroup` to
/// hang them off — the panel hosts this view directly, and a single owner is
/// what keeps the two platforms running the same object graph rather than two
/// subtly different ones.
struct AppRootView: View {
    @StateObject private var walletManager = WalletManager()
    @StateObject private var navigationManager = NavigationManager()
    @StateObject private var appLockManager = AppLockManager.shared

    #if os(iOS)
    @Environment(\.scenePhase) private var scenePhase
    #endif

    var body: some View {
        ZStack {
            ContentView()
                .environmentObject(walletManager)
                .environmentObject(navigationManager)
                .environmentObject(appLockManager)
                .task { await startUp() }
                .onOpenURL { url in
                    navigationManager.handleDeepLink(url: url)
                }

            #if os(macOS)
            // The iOS build covers presented screens from a dedicated UIWindow
            // above the scene (see AppLockWindow.swift). AppKit has no
            // equivalent here — the wallet is one NSPanel — so the Mac keeps the
            // in-tree overlay. Known gap: a macOS sheet is its own window, so
            // this does not cover one that is already open.

            // App-switcher privacy cover (no lock yet). Sits above sheets so
            // backgrounding mid-presentation never leaks content.
            if appLockManager.isObscured && !appLockManager.isLocked {
                PrivacyCoverView()
            }

            // Lock gate. Window-level so it covers ContentView's full-screen
            // covers and MainTabView's sheets too.
            if appLockManager.isLocked {
                AppLockView()
                    .environmentObject(appLockManager)
            }
            #endif
        }
        #if os(macOS)
        .animation(.easeInOut(duration: 0.2), value: appLockManager.isLocked)
        #else
        .background { AppLockWindowBridge(manager: appLockManager).frame(width: 0, height: 0) }
        #endif
        .transaction { transaction in
            if IntegrationTestConfig.shouldDisableAnimations {
                transaction.disablesAnimations = true
            }
        }
        #if os(iOS)
        .onChange(of: scenePhase) { _, newPhase in
            guard IntegrationTestConfig.shouldRunPaymentServices else { return }
            switch newPhase {
            case .active:
                becameActive()
            case .inactive:
                // The app-switcher snapshot is taken here, before `.background`.
                appLockManager.appResignedActive()
            case .background:
                appLockManager.appResignedActive()
                resignedActive()
            @unknown default:
                break
            }
        }
        #else
        // macOS has no scene phase here — the panel is an AppKit window, not a
        // SwiftUI scene. MacMenuBarController posts these instead, on panel
        // open and close, so both platforms run the same two code paths.
        .onReceive(NotificationCenter.default.publisher(for: .cashuMenuBarPanelDidOpen)) { _ in
            guard IntegrationTestConfig.shouldRunPaymentServices else { return }
            becameActive()
        }
        .onReceive(NotificationCenter.default.publisher(for: .cashuMenuBarPanelDidClose)) { _ in
            guard IntegrationTestConfig.shouldRunPaymentServices else { return }
            appLockManager.appResignedActive()
            resignedActive()
        }
        // `onOpenURL` needs a SwiftUI scene to deliver into, and the panel is an
        // AppKit window. The delegate's URL handler re-posts here instead so
        // `cashu:` links still route through the one deep-link entry point.
        .onReceive(NotificationCenter.default.publisher(for: .cashuMenuBarDidReceiveURL)) { note in
            guard let url = note.object as? URL else { return }
            navigationManager.handleDeepLink(url: url)
        }
        #endif
    }

    private func startUp() async {
        if !IntegrationTestConfig.shouldUseDeterministicUIRuntime {
            SentryService.initialize()
        }
        await walletManager.initialize()
        guard IntegrationTestConfig.shouldRunPaymentServices else { return }
        CashuRequestListener.shared.attach(walletManager: walletManager)
        CashuRequestListener.shared.requestStart()
        if SettingsManager.shared.checkSentTokens {
            await walletManager.checkAllPendingTokens()
        }
    }

    private func becameActive() {
        appLockManager.appBecameActive()
        CashuRequestListener.shared.requestStart()
        if SettingsManager.shared.checkSentTokens {
            Task { await walletManager.checkAllPendingTokens() }
        }
        Task { await walletManager.syncPendingMintQuotesIfStale() }
        Task { await walletManager.syncPendingMeltQuotes() }
        Task { await NWCManager.shared.startIfEnabled() }
        // Recover enabled services and re-arm polling after backgrounding.
        if !IntegrationTestConfig.isEnabled {
            Task { await NPCService.shared.initializeIfEnabled() }
        }
        if !IntegrationTestConfig.isEnabled && PriceService.shared.isEnabled {
            PriceService.shared.startAutoRefresh()
        }
        walletManager.startPendingQuoteForegroundPolling()
    }

    private func resignedActive() {
        CashuRequestListener.shared.requestStop()
        // Quiesce the timers so no fresh mint network + wallet-DB write kicks
        // off during the brief background-transition window before suspension.
        NPCService.shared.stopBackgroundRefresh()
        PriceService.shared.stopAutoRefresh()
        walletManager.stopPendingQuoteForegroundPolling()
    }
}
