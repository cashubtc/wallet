package org.cashu.wallet.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.AssistChip
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
import org.cashu.wallet.ui.theme.CapsuleShape
import org.cashu.wallet.ui.theme.CashuTheme

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
    // Box anchors the DropdownMenu to the chip; as a bare sibling its position
    // would be at the mercy of whatever parent layout hosts the chip.
    Box(modifier = modifier) {
        AssistChip(
            onClick = {
                if (mints.isEmpty()) onManage() else expanded = true
            },
            shape = CapsuleShape,
            label = {
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            leadingIcon = {
                if (activeMint != null) {
                    MintAvatar(mint = activeMint, size = MintChipAvatarSize)
                } else {
                    Icon(
                        imageVector = Icons.Outlined.AccountBalance,
                        contentDescription = null,
                        modifier = Modifier.size(CashuTheme.spacing.loose),
                    )
                }
            },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            // Menus default to extraSmall (4dp) corners; the app is rounded
            // everywhere else, so lift menus onto the large shape token.
            shape = MaterialTheme.shapes.large,
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
                                modifier = Modifier.size(CashuTheme.spacing.loose),
                            )
                        }
                    } else null,
                )
            }
            if (mints.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text("Add Mint") },
                    onClick = {
                        expanded = false
                        onManage()
                    },
                )
            }
        }
    }
}

// iOS mint chip icon is 20×20 inside the capsule.
private val MintChipAvatarSize = 20.dp
