import SwiftUI

@main
struct CashuWalletApp: App {
    @StateObject private var walletManager = WalletManager()
    @StateObject private var navigationManager = NavigationManager()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(walletManager)
                .environmentObject(navigationManager)
                .task {
                    await walletManager.initialize()
                    let settingsStore = SettingsStore.shared
                    let shouldCheckPending = settingsStore.checkPendingOnStartup
                    let shouldTrackSentTokens = settingsStore.checkSentTokens
                    if shouldCheckPending && shouldTrackSentTokens {
                        try? await Task.sleep(nanoseconds: 1_500_000_000)
                        await walletManager.checkAllPendingTokens()
                    }
                    CashuRequestListener.shared.attach(walletManager: walletManager)
                    await CashuRequestListener.shared.start()
                }
                .onChange(of: scenePhase) { _, newPhase in
                    switch newPhase {
                    case .active:
                        Task { await CashuRequestListener.shared.start() }
                    case .background:
                        Task { await CashuRequestListener.shared.stop() }
                    default:
                        break
                    }
                }
                .onOpenURL { url in
                    navigationManager.handleDeepLink(url: url)
                }
        }
    }
}
