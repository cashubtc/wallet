package org.cashu.wallet.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.UnfoldMore
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cashu.wallet.Models.MintInfo

@Composable
fun MintChip(
    activeMint: MintInfo?,
    mints: List<MintInfo>,
    onSelect: (MintInfo) -> Unit,
    onManage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val label = activeMint?.name ?: "No mint"
    AssistChip(
        onClick = {
            if (mints.isEmpty()) onManage() else expanded = true
        },
        modifier = modifier,
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Outlined.AccountBalance,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        trailingIcon = {
            Icon(
                imageVector = Icons.Outlined.UnfoldMore,
                contentDescription = null,
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
    )
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = { expanded = false },
    ) {
        mints.forEach { mint ->
            val isActive = mint.url == activeMint?.url
            DropdownMenuItem(
                text = { Text(mint.name) },
                onClick = {
                    expanded = false
                    if (!isActive) onSelect(mint)
                },
                trailingIcon = if (isActive) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = "Active",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else null,
            )
        }
        if (mints.isNotEmpty()) {
            DropdownMenuItem(
                text = { Text("Manage mints") },
                onClick = {
                    expanded = false
                    onManage()
                },
            )
        }
    }
}
