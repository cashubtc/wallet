package org.cashu.wallet.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cashu.wallet.ui.theme.CashuTheme

/**
 * Single hairline used to separate rows on canvas screens (History, Settings root, Mints).
 * 28dp leading inset aligns with the icon column.
 */
@Composable
fun CanvasDivider(
    modifier: Modifier = Modifier,
    leadingInset: Int = 28,
) {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = CashuTheme.colors.canvasDivider,
        modifier = modifier.padding(start = leadingInset.dp),
    )
}

/**
 * Tighter divider used inside inspector groups (Cashu Request, Transaction Detail).
 */
@Composable
fun InspectorDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(
        thickness = 0.5.dp,
        color = CashuTheme.colors.canvasDivider,
        modifier = modifier.padding(horizontal = 8.dp),
    )
}
