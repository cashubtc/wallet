package com.cashu.me.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Immutable
data class CashuSpacing(
    val micro: Dp = 4.dp,
    val tight: Dp = 6.dp,
    val snug: Dp = 8.dp,
    val default: Dp = 12.dp,
    val comfortable: Dp = 16.dp,
    val loose: Dp = 20.dp,
    val section: Dp = 24.dp,
    val page: Dp = 28.dp,
)

val LocalCashuSpacing = compositionLocalOf { CashuSpacing() }
