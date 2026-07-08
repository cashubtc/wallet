package org.cashu.wallet.App

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.metrics.performance.JankStats
import org.cashu.wallet.BuildConfig
import org.cashu.wallet.Core.AppLogger
import org.cashu.wallet.ui.shell.CashuApp

class MainActivity : FragmentActivity() {
    private var jankStats: JankStats? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        installDebugJankStats()
        val container = (application as CashuWalletApplication).container
        handleIntent(intent)
        setContent {
            CashuApp(container = container)
        }
    }

    override fun onResume() {
        super.onResume()
        jankStats?.isTrackingEnabled = true
    }

    override fun onPause() {
        jankStats?.isTrackingEnabled = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            (application as CashuWalletApplication).container.navigationManager.handleDeepLink(intent.dataString)
        }
    }

    private fun installDebugJankStats() {
        if (!BuildConfig.DEBUG) return
        jankStats = JankStats.createAndTrack(window) { frameData ->
            if (frameData.isJank) {
                AppLogger.ui.debug("Jank frame observed: duration=${frameData.frameDurationUiNanos}ns")
            }
        }
    }
}
