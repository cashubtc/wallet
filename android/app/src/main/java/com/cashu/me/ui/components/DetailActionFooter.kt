package com.cashu.me.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.cashu.me.ui.theme.CashuTheme

/**
 * Pinned action area shared by history detail destinations.
 *
 * Keeping the final 16dp breathing room here (in addition to the owning
 * Scaffold's safe-area content padding) prevents otherwise-identical detail
 * screens from drifting vertically as their content grows or as gesture/
 * three-button navigation changes the bottom inset.
 */
@Composable
fun DetailActionFooter(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = CashuTheme.spacing.comfortable,
                end = CashuTheme.spacing.comfortable,
                bottom = CashuTheme.spacing.comfortable,
            ),
        content = content,
    )
}
