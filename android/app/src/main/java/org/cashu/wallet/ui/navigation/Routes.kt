package org.cashu.wallet.ui.navigation

/**
 * Compose Navigation routes.
 *
 * Top-level tab routes are bare strings used as `NavController` `startDestination`s and tab keys.
 * Pushed detail destinations take typed arguments embedded in the route path.
 *
 * Send/Receive flows and Scanner/Contactless are intentionally NOT routes:
 * flows are modal bottom sheets (`ui.shell.WalletFlowSheetHost`) and camera
 * surfaces are shell overlays (`ui.shell.CashuApp`).
 */
object Routes {
    // Top-level tabs
    const val HOME = "home"
    const val HISTORY = "history"
    const val MINTS = "mints"
    const val SETTINGS = "settings"

    // With arguments
    const val MINT_DETAIL = "mints/{mintUrl}"
    const val TRANSACTION_DETAIL = "history/transaction/{transactionId}"
    const val CASHU_REQUEST_DETAIL = "request/{requestId}"

    // Settings sub-screens
    const val SETTINGS_BACKUP_RESTORE = "settings/backup-restore"
    const val SETTINGS_BACKUP = "settings/backup"
    const val SETTINGS_LIGHTNING = "settings/lightning"
    const val SETTINGS_P2PK = "settings/p2pk"
    const val SETTINGS_P2PK_ADVANCED = "settings/p2pk/advanced"
    const val SETTINGS_P2PK_KEY = "settings/p2pk/key/{keyId}"
    const val SETTINGS_NOSTR = "settings/nostr"
    const val SETTINGS_NWC = "settings/nostr/wallet-connect"
    const val SETTINGS_PRIVACY = "settings/privacy"

    /** Top tabs in display order. */
    val TopTabs: List<TopTab> = listOf(
        TopTab.Home,
        TopTab.History,
        TopTab.Mints,
        TopTab.Settings,
    )
}

enum class TopTab(val route: String, val label: String) {
    Home(Routes.HOME, "Wallet"),
    History(Routes.HISTORY, "History"),
    Mints(Routes.MINTS, "Mints"),
    Settings(Routes.SETTINGS, "Settings"),
}
