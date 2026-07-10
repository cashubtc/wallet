package org.cashu.wallet.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.unit.Density
import org.cashu.wallet.ui.theme.CashuTheme

fun ComposeContentTestRule.setCashuContent(
    darkTheme: Boolean = false,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    setContent {
        val density = LocalDensity.current
        CompositionLocalProvider(
            LocalDensity provides Density(density = density.density, fontScale = fontScale),
        ) {
            CashuTheme(darkTheme = darkTheme, content = content)
        }
    }
}
