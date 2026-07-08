package org.cashu.wallet.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

// Semantic state hues. Received/pending/error communicate payment state and are
// deliberately stable across dynamic-color themes so state never shifts with the
// user's wallpaper. Everything else flows through MaterialTheme.colorScheme
// (Material You dynamic color on Android 12+, M3 baseline below).
val ReceivedGreen = Color(0xFF2E7D32)
val PendingOrange = Color(0xFFEF6C00)
val ErrorRed = Color(0xFFC62828)

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
    canvasDivider = Color(0xFFE0E0E0),
)

internal val DarkCashuColors = CashuColors(
    received = Color(0xFF81C784),
    pending = Color(0xFFFFB74D),
    receivedContainer = ReceivedGreen.copy(alpha = 0.20f),
    pendingContainer = PendingOrange.copy(alpha = 0.18f),
    canvasDivider = Color(0xFF3A3A3A),
)

val LocalCashuColors = compositionLocalOf { LightCashuColors }
