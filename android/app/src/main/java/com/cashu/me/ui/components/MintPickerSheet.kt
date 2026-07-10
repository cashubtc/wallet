package com.cashu.me.ui.components

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cashu.me.Models.MintInfo
import com.cashu.me.ui.theme.CashuTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MintPickerSheet(
    mints: List<MintInfo>,
    activeMintUrl: String?,
    onSelect: (MintInfo) -> Unit,
    onDismiss: () -> Unit,
    title: String = "Choose mint",
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CashuTheme.spacing.comfortable)
                .navigationBarsPadding(),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = CashuTheme.spacing.snug,
                    vertical = CashuTheme.spacing.default,
                ),
            )
            mints.forEach { mint ->
                val isActive = mint.url == activeMintUrl
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(mint) }
                        .padding(
                            horizontal = CashuTheme.spacing.snug,
                            vertical = CashuTheme.spacing.default,
                        ),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(mint.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            mint.url,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    if (isActive) {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Active",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(CashuTheme.spacing.loose),
                        )
                    }
                }
            }
            Spacer(Modifier.height(CashuTheme.spacing.snug))
        }
    }
}
