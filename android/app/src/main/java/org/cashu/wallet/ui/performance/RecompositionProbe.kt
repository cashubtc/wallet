package org.cashu.wallet.ui.performance

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import org.cashu.wallet.BuildConfig
import org.cashu.wallet.Core.AppLogger

@Composable
fun DebugRecompositionProbe(label: String) {
    if (!BuildConfig.DEBUG) return
    val count = remember(label) { intArrayOf(0) }
    SideEffect {
        count[0] += 1
        if (count[0] == 1 || count[0] % 10 == 0) {
            AppLogger.ui.debug("Recomposition count for $label: ${count[0]}")
        }
    }
}
