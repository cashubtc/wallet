package org.cashu.wallet.App

import android.content.Context
import org.cashu.wallet.Core.CDK.CdkWalletGatewayImpl
import org.cashu.wallet.Core.AppLockManager
import org.cashu.wallet.Core.CashuRequestListener
import org.cashu.wallet.Core.CashuRequestStore
import org.cashu.wallet.Core.MintDiscoveryManager
import org.cashu.wallet.Core.NPCService
import org.cashu.wallet.Core.Navigation.NavigationManager
import org.cashu.wallet.Core.NostrService
import org.cashu.wallet.Core.Platform.AndroidConnectivityObserver
import org.cashu.wallet.Core.Platform.AndroidSecureStorage
import org.cashu.wallet.Core.Platform.WalletDatabasePathManager
import org.cashu.wallet.Core.PriceService
import org.cashu.wallet.Core.PrimaryP2PKKey
import org.cashu.wallet.Core.SentryService
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.SettingsStore
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Core.WalletStore

class AppContainer(context: Context) {
    private val appContext = context.applicationContext
    val secureStorage = AndroidSecureStorage(appContext)
    val walletStore = WalletStore(appContext)
    val cashuRequestStore = CashuRequestStore(walletStore)
    val settingsStore = SettingsStore(appContext)
    val settingsManager = SettingsManager(settingsStore, secureStorage)
    val appLockManager = AppLockManager(appContext, settingsManager)
    val sentryService = SentryService(appContext, settingsStore)
    val nostrService = NostrService(secureStorage, settingsStore)
    val navigationManager = NavigationManager()
    val connectivityObserver = AndroidConnectivityObserver(appContext)
    val walletDatabasePathManager = WalletDatabasePathManager(appContext)
    val cdkGateway = CdkWalletGatewayImpl()
    val npcService = NPCService(appContext, nostrService, settingsManager)
    val walletManager = WalletManager(
        secureStorage = secureStorage,
        walletStore = walletStore,
        settingsManager = settingsManager,
        nostrService = nostrService,
        npcService = npcService,
        databasePathManager = walletDatabasePathManager,
        gateway = cdkGateway,
    )
    val priceService = PriceService(settingsStore)
    val mintDiscoveryManager = MintDiscoveryManager(settingsManager, cdkGateway)
    val cashuRequestListener = CashuRequestListener(
        context = appContext,
        nostrService = nostrService,
        settingsManager = settingsManager,
        walletManager = walletManager,
        cashuRequestStore = cashuRequestStore,
    )

    init {
        npcService.quoteClaimHandler = walletManager
        settingsManager.sentryService = sentryService
        // Seed-derived primary P2PK key (iOS primaryP2PKPublicKey/PrivateKeyHex):
        // included in the signing set so ecash locked to the wallet's own key
        // (e.g. NPC locked quotes, locked receive requests) is redeemable.
        settingsManager.primaryP2PKKeyProvider = provider@{
            val privateKeyHex = nostrService.seedDerivedPrivateKeyHex() ?: return@provider null
            val publicKeyHex = nostrService.seedDerivedPublicKeyHex()
                .takeIf { it.length == 64 } ?: return@provider null
            PrimaryP2PKKey(publicKey = "02$publicKeyHex", privateKeyHex = privateKeyHex)
        }
    }
}
