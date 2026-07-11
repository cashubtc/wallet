package com.cashu.me.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cashu.me.Models.MintInfo
import com.cashu.me.ui.theme.CapsuleShape
import com.cashu.me.ui.theme.CashuTheme

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
        // Custom capsule (not AssistChip) so inner padding can be bumped 20%
        // over the stock M3 chip (32dp tall / 8dp side pad → 38.4 / 9.6).
        Surface(
            onClick = {
                if (mints.isEmpty()) onManage() else expanded = true
            },
            shape = CapsuleShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.defaultMinSize(minHeight = MintChipMinHeight),
        ) {
            Row(
                modifier = Modifier.padding(MintChipPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(MintChipIconGap),
            ) {
                if (activeMint != null) {
                    MintAvatar(mint = activeMint, size = MintChipAvatarSize)
                } else {
                    Icon(
                        imageVector = Icons.Outlined.AccountBalance,
                        contentDescription = null,
                        modifier = Modifier.size(MintChipAvatarSize),
                    )
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
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

// Stock M3 AssistChip is 32dp tall / 20dp icon. Half-step up from that for a
// slightly larger tappable mint switcher (was +6dp, now +3dp).
private val MintChipMinHeight = 35.dp
private val MintChipPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
private val MintChipIconGap = 10.dp
private val MintChipAvatarSize = 22.dp
