package org.cashu.wallet.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.cashu.wallet.ui.theme.CashuTheme

private val ChooserIconSize = 24.dp
private val ChooserLabelGap = 2.dp

data class ChooserOption(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val supporting: String? = null,
)

/**
 * Bottom-sheet action chooser with the iOS cascade-in animation.
 * Each option fades + slides in from the leading edge with a 70ms stagger.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChooserSheet(
    title: String,
    options: List<ChooserOption>,
    onSelect: (ChooserOption) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var revealed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(40)
        revealed = true
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        dragHandle = null,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CashuTheme.spacing.comfortable)
                .padding(top = CashuTheme.spacing.snug)
                .navigationBarsPadding(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(
                    horizontal = CashuTheme.spacing.snug,
                    vertical = CashuTheme.spacing.default,
                ),
            )
            options.forEachIndexed { index, option ->
                AnimatedVisibility(
                    visible = revealed,
                    enter = fadeIn(tween(durationMillis = 220, delayMillis = index * 70)) +
                            slideInHorizontally(tween(durationMillis = 280, delayMillis = index * 70)) { -it / 8 },
                ) {
                    ChooserRow(option = option, onClick = { onSelect(option) })
                }
            }
            Spacer(Modifier.height(CashuTheme.spacing.snug))
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
            .padding(horizontal = CashuTheme.spacing.snug, vertical = CashuTheme.spacing.default),
    ) {
        Icon(
            imageVector = option.icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.size(ChooserIconSize),
        )
        Spacer(Modifier.width(CashuTheme.spacing.comfortable))
        Column(verticalArrangement = Arrangement.spacedBy(ChooserLabelGap)) {
            Text(
                text = option.label,
                style = MaterialTheme.typography.bodyLarge,
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
