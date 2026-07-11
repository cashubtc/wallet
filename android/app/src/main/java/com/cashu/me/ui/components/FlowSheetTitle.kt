package com.cashu.me.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Centered sheet title for the flow bottom sheets (Send / Receive) when there
 * is no leading back / trailing action — same chrome as [SheetHeader] /
 * iOS `.headline`, tight under the ModalBottomSheet drag handle.
 */
@Composable
fun FlowSheetTitle(
    title: String,
    modifier: Modifier = Modifier,
) {
    SheetHeader(title = title, modifier = modifier)
}
