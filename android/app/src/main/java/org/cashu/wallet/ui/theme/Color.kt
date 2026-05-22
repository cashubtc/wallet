package org.cashu.wallet.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// Semantic state hues — the ONLY hardcoded colors in the system, per the Semantic-Only Rule.
// Everything else flows through MaterialTheme.colorScheme.
val ReceivedGreen = Color(0xFF34C759)
val PendingOrange = Color(0xFFFF9500)
val ErrorRed = Color(0xFFFF3B30)

internal val LightScheme: ColorScheme = lightColorScheme(
    primary = Color(0xFF000000),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFEAEAEC),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF636366),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEFEFF2),
    onSecondaryContainer = Color(0xFF1C1C1E),
    tertiary = Color(0xFF3A3A3C),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF000000),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF000000),
    surfaceVariant = Color(0xFFF2F2F7),
    onSurfaceVariant = Color(0xFF636366),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFBFD),
    surfaceContainer = Color(0xFFF6F6F9),
    surfaceContainerHigh = Color(0xFFF1F1F5),
    surfaceContainerHighest = Color(0xFFECECF0),
    outline = Color(0xFFD1D1D6),
    outlineVariant = Color(0xFFE5E5EA),
    error = ErrorRed,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE5E3),
    onErrorContainer = Color(0xFF6B0000),
    scrim = Color(0x66000000),
)

internal val DarkScheme: ColorScheme = darkColorScheme(
    primary = Color(0xFFFFFFFF),
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF2C2C2E),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFFC7C7CC),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF2C2C2E),
    onSecondaryContainer = Color(0xFFEAEAEC),
    tertiary = Color(0xFFC7C7CC),
    onTertiary = Color(0xFF000000),
    background = Color(0xFF000000),
    onBackground = Color(0xFFFFFFFF),
    surface = Color(0xFF000000),
    onSurface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFF1C1C1E),
    onSurfaceVariant = Color(0xFF8E8E93),
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF101012),
    surfaceContainer = Color(0xFF1C1C1E),
    surfaceContainerHigh = Color(0xFF2C2C2E),
    surfaceContainerHighest = Color(0xFF38383A),
    outline = Color(0xFF38383A),
    outlineVariant = Color(0xFF2C2C2E),
    error = ErrorRed,
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFF5C0000),
    onErrorContainer = Color(0xFFFFD9D6),
    scrim = Color(0x99000000),
)

// Semantic extensions for state hues that don't fit into MaterialColorScheme.
// Accessed via LocalCashuColors.current.
@Immutable
data class CashuColors(
    val received: Color,
    val pending: Color,
    val receivedContainer: Color,
    val pendingContainer: Color,
    val canvasDivider: Color,
)

internal val LightCashuColors = CashuColors(
    received = ReceivedGreen,
    pending = PendingOrange,
    receivedContainer = ReceivedGreen.copy(alpha = 0.12f),
    pendingContainer = PendingOrange.copy(alpha = 0.10f),
    canvasDivider = Color(0xFFE5E5EA),
)

internal val DarkCashuColors = CashuColors(
    received = ReceivedGreen,
    pending = PendingOrange,
    receivedContainer = ReceivedGreen.copy(alpha = 0.16f),
    pendingContainer = PendingOrange.copy(alpha = 0.14f),
    canvasDivider = Color(0xFF2C2C2E),
)

val LocalCashuColors = compositionLocalOf { LightCashuColors }
