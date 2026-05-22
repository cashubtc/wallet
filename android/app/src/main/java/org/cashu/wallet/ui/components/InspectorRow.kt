package org.cashu.wallet.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

// Inspector leading icon stays at 18dp (a touch smaller than the 20dp body icon size)
// so the inspector reads as denser metadata, not list-row chrome.
private val InspectorLeadingIconSize = 18.dp
private val InspectorEditHintSize = 16.dp

/**
 * Two-column metadata row used inside Cashu Request / Transaction Detail inspector
 * groups. Optional leading icon, optional pencil affordance for editable rows
 * (which trigger a sub-sheet on tap).
 */
@Composable
fun InspectorRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    editable: Boolean = false,
    onClick: (() -> Unit)? = null,
    valueMonospaced: Boolean = false,
) {
    val rowMod = if (onClick != null && editable) {
        modifier.fillMaxWidth().clickable(onClick = onClick)
    } else {
        modifier.fillMaxWidth()
    }
    Row(
        modifier = rowMod.padding(
            horizontal = CashuTheme.spacing.comfortable,
            vertical = CashuTheme.spacing.default,
        ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(InspectorLeadingIconSize),
            )
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (valueMonospaced) {
                MaterialTheme.typography.bodyMedium.withMonoDigits()
            } else MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
            modifier = Modifier.weight(2f, fill = false),
        )
        if (editable) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = "Edit $label",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(InspectorEditHintSize),
            )
        }
    }
}

