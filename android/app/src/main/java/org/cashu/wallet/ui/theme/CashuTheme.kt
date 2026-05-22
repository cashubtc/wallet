package org.cashu.wallet.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

object CashuTheme {
    val colors: CashuColors
        @Composable get() = LocalCashuColors.current
    val spacing: CashuSpacing
        @Composable get() = LocalCashuSpacing.current
}

@Composable
fun CashuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkScheme else LightScheme
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
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = CashuShapes,
            typography = CashuTypography,
            content = content,
        )
    }
}
