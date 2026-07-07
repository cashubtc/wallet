package org.cashu.wallet.performance

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BaselineProfileCoverageTest {
    @Test
    fun baselineProfileCoversCoreUserJourneys() {
        val profile = File("src/main/baselineProfiles/baseline-prof.txt").readText()

        val requiredDescriptors = listOf(
            "Lorg/cashu/wallet/App/MainActivity;",
            "Lorg/cashu/wallet/ui/shell/CashuAppKt;",
            "Lorg/cashu/wallet/ui/home/HomeScreenKt;",
            "Lorg/cashu/wallet/ui/settings/SettingsScreenKt;",
            "Lorg/cashu/wallet/ui/send/UnifiedSendScreenKt;",
            "Lorg/cashu/wallet/ui/send/SendEcashScreenKt;",
            "Lorg/cashu/wallet/ui/receive/ReceiveEcashScreenKt;",
            "Lorg/cashu/wallet/ui/receive/ReceiveLightningScreenKt;",
            "Lorg/cashu/wallet/Views/Components/ScannerViewKt;",
            "Lorg/cashu/wallet/ui/history/HistoryScreenKt;",
            "Lorg/cashu/wallet/ui/mints/MintsScreenKt;",
            "Lorg/cashu/wallet/ui/components/TransactionRowKt;",
        )

        requiredDescriptors.forEach { descriptor ->
            assertTrue("Missing baseline profile descriptor $descriptor", descriptor in profile)
        }
    }

    @Test
    fun startupProfileCoversColdStartShell() {
        val profile = File("src/main/baselineProfiles/startup-prof.txt").readText()

        val requiredDescriptors = listOf(
            "Lorg/cashu/wallet/App/CashuWalletApplication;",
            "Lorg/cashu/wallet/App/MainActivity;",
            "Lorg/cashu/wallet/Core/Wallet/WalletManager;",
            "Lorg/cashu/wallet/ui/shell/CashuAppKt;",
            "Lorg/cashu/wallet/ui/shell/WalletScaffoldKt;",
            "Lorg/cashu/wallet/ui/home/HomeScreenKt;",
            "Lorg/cashu/wallet/ui/theme/CashuThemeKt;",
        )

        requiredDescriptors.forEach { descriptor ->
            assertTrue("Missing startup profile descriptor $descriptor", descriptor in profile)
        }
    }
}
