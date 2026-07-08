package org.cashu.wallet.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

object CashuTheme {
    val colors: CashuColors
        @Composable get() = LocalCashuColors.current
    val spacing: CashuSpacing
        @Composable get() = LocalCashuSpacing.current
}

/**
 * Android-first theme: Material 3 Expressive with Material You dynamic color.
 *
 * - Android 12+: the color scheme comes from the user's wallpaper
 *   (dynamic color) — the most Android-native identity an app can have.
 * - Android 8–11: falls back to the M3 baseline scheme (violet family,
 *   close kin to the Cashu purple).
 * - Motion: [MotionScheme.expressive] — spring physics drive all component
 *   motion (sheets, switches, indicators) instead of hand-tuned tweens.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CashuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }
    val cashuColors = if (darkTheme) DarkCashuColors else LightCashuColors

    // Status/navigation bar tinting. enableEdgeToEdge() makes the bars transparent;
    // here we only flip the icon-tint (light vs dark) based on background luminance.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            val lightSurface = colorScheme.background.luminance() > 0.5f
            controller.isAppearanceLightStatusBars = lightSurface
            controller.isAppearanceLightNavigationBars = lightSurface
        }
    }

    CompositionLocalProvider(
        LocalCashuColors provides cashuColors,
        LocalCashuSpacing provides CashuSpacing(),
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = MotionScheme.expressive(),
            shapes = CashuShapes,
            typography = CashuTypography,
            content = content,
        )
    }
}
