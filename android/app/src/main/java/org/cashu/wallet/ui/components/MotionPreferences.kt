package org.cashu.wallet.ui.components

import android.animation.ValueAnimator
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

@Composable
fun rememberReduceMotionEnabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        val durationScale = runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f,
            )
        }.getOrDefault(1f)
        reduceMotionEnabled(
            animatorsEnabled = ValueAnimator.areAnimatorsEnabled(),
            animatorDurationScale = durationScale,
        )
    }
}

internal fun reduceMotionEnabled(
    animatorsEnabled: Boolean,
    animatorDurationScale: Float,
): Boolean = !animatorsEnabled || animatorDurationScale <= 0f
