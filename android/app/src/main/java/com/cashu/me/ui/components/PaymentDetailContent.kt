package com.cashu.me.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cashu.me.ui.theme.CashuTheme

internal val LocalCompactPaymentDetails = compositionLocalOf { false }

/**
 * Fits the QR around the actual receipt text, with scrolling for oversized content.
 *
 * @param stableSizeKey when set, the QR keeps the size it settled at for this key:
 *   details that change in place afterwards (a status value, a notice) scroll
 *   instead of shrinking the code someone may be scanning. A new key, container
 *   size or font scale measures again (iOS `PaymentDetailContent` parity).
 */
@Composable
fun PaymentDetailContent(
    modifier: Modifier = Modifier,
    stableSizeKey: Any? = null,
    hero: @Composable (Dp) -> Unit,
    details: @Composable ColumnScope.() -> Unit,
) {
    var detailsHeight by remember { mutableIntStateOf(0) }
    var sizeLatch by remember { mutableStateOf<Any?>(null) }
    val density = LocalDensity.current
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val latchKey = stableSizeKey?.let { listOf(it, maxWidth, maxHeight, density.fontScale) }
        val compact = maxHeight < 600.dp
        val qrSize = minOf(280.dp, maxWidth - 64.dp,
            maxHeight - with(density) { detailsHeight.toDp() } - 64.dp).coerceAtLeast(120.dp)
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = CashuTheme.spacing.comfortable, vertical = CashuTheme.spacing.snug),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.comfortable),
        ) {
            hero(qrSize)
            CompositionLocalProvider(LocalCompactPaymentDetails provides compact) {
                Column(
                    modifier = Modifier.fillMaxWidth().onSizeChanged { size ->
                        if (latchKey != null && latchKey == sizeLatch && detailsHeight > 0) return@onSizeChanged
                        detailsHeight = size.height
                        if (size.height > 0) sizeLatch = latchKey
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.comfortable),
                    content = details,
                )
            }
        }
    }
}
