package com.cashu.me.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cashu.me.ui.theme.CashuTheme

/**
 * Left-aligned sheet title for the flow bottom sheets (Send / Receive) — bigger
 * [MaterialTheme.typography.titleLarge], no close button. Used under a
 * handle-less [androidx.compose.material3.ModalBottomSheet].
 *
 * Padding: comfortable outer inset, then snug + default around the label.
 */
@Composable
fun FlowSheetTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = CashuTheme.spacing.comfortable)
            .padding(top = CashuTheme.spacing.default)
            .padding(
                horizontal = CashuTheme.spacing.snug,
                vertical = CashuTheme.spacing.default,
            ),
    )
}
