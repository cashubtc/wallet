package com.cashu.me.App

import android.content.Context
import com.cashu.me.Core.CDK.CdkWalletGatewayImpl
import com.cashu.me.Core.AppLockManager
import com.cashu.me.Core.CashuRequestListener
import com.cashu.me.Core.CashuRequestStore
import com.cashu.me.Core.MintDiscoveryManager
import com.cashu.me.Core.NPCService
import com.cashu.me.Core.Navigation.NavigationManager
import com.cashu.me.Core.NostrMintBackupService
import com.cashu.me.Core.NostrService
import com.cashu.me.Core.Platform.AndroidConnectivityObserver
import com.cashu.me.Core.Platform.AndroidSecureStorage
import com.cashu.me.Core.Platform.WalletDatabasePathManager
import com.cashu.me.Core.PriceService
import com.cashu.me.Core.PrimaryP2PKKey
import com.cashu.me.Core.SentryService
import com.cashu.me.Core.SettingsManager
import com.cashu.me.Core.SettingsStore
import com.cashu.me.Core.WalletManager
import com.cashu.me.Core.WalletStore

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
    val nostrMintBackupService = NostrMintBackupService(settingsManager, settingsStore, cdkGateway)
    val walletManager = WalletManager(
        secureStorage = secureStorage,
        walletStore = walletStore,
        settingsManager = settingsManager,
        nostrService = nostrService,
        npcService = npcService,
        nostrMintBackupService = nostrMintBackupService,
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
