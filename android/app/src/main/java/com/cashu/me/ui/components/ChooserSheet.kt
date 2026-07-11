package com.cashu.me.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cashu.me.ui.theme.CashuTheme

// iOS-parity: 36dp icon container on rounded-10 fill.
private val ChooserIconContainerSize = 36.dp
private val ChooserIconSize = 20.dp
private val ChooserLabelGap = 2.dp

data class ChooserOption(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val supporting: String? = null,
)

/** Bottom-sheet action chooser. Options appear with the sheet — no per-row cascade. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooserSheet(
    title: String,
    options: List<ChooserOption>,
    onSelect: (ChooserOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CashuTheme.spacing.comfortable)
                .padding(top = CashuTheme.spacing.default)
                .navigationBarsPadding(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(
                    horizontal = CashuTheme.spacing.snug,
                    vertical = CashuTheme.spacing.default,
                ),
            )
            options.forEach { option ->
                ChooserRow(option = option, onClick = { onSelect(option) })
            }
            Spacer(Modifier.height(CashuTheme.spacing.section))
        }
    }
}

@Composable
private fun ChooserRow(option: ChooserOption, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .padding(horizontal = CashuTheme.spacing.snug, vertical = 14.dp),
    ) {
        // iOS-parity: 36dp rounded-10 container on subtle fill.
        Box(
            modifier = Modifier
                .size(ChooserIconContainerSize)
                .background(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(10.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = option.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(ChooserIconSize),
            )
        }
        Spacer(Modifier.width(CashuTheme.spacing.comfortable))
        Column(verticalArrangement = Arrangement.spacedBy(ChooserLabelGap)) {
            // iOS .title3 ≈ 20sp Medium
            Text(
                text = option.label,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Medium,
                ),
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (option.supporting != null) {
                Text(
                    text = option.supporting,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
