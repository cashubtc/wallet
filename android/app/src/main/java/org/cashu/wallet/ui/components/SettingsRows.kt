package org.cashu.wallet.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import org.cashu.wallet.ui.theme.CashuTheme

/**
 * Settings rows on M3 [ListItem] — native geometry, states, and typography.
 * Pass `tint = colorScheme.error` for destructive rows.
 */
@Composable
fun NavRow(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    subtitle: String? = null,
    enabled: Boolean = true,
    tint: Color? = null,
    showChevron: Boolean = true,
    trailingValue: String? = null,
    trailingIcon: ImageVector? = null,
) {
    val titleColor = tint ?: MaterialTheme.colorScheme.onSurface
    val iconColor = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    val chevronColor = tint ?: MaterialTheme.colorScheme.onSurfaceVariant
    ListItem(
        modifier = modifier.clickable(enabled = enabled, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        supportingContent = subtitle?.let {
            { Text(text = it) }
        },
        leadingContent = leadingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = iconColor,
                    modifier = Modifier.size(CashuTheme.spacing.section),
                )
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (trailingValue != null) {
                    Text(
                        text = trailingValue,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(CashuTheme.spacing.tight))
                }
                when {
                    trailingIcon != null -> Icon(
                        imageVector = trailingIcon,
                        contentDescription = null,
                        tint = chevronColor,
                        modifier = Modifier.size(CashuTheme.spacing.loose),
                    )
                    showChevron -> Icon(
                        imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                        contentDescription = null,
                        tint = chevronColor,
                        modifier = Modifier.size(CashuTheme.spacing.loose),
                    )
                }
            }
        },
    ) {
        Text(text = title, color = titleColor)
    }
}

/** Settings list row with a trailing Switch, on M3 [ListItem]. */
@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
) {
    ListItem(
        modifier = modifier
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(enabled = enabled) { onCheckedChange(!checked) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        supportingContent = subtitle?.let {
            { Text(text = it) }
        },
        leadingContent = leadingIcon?.let {
            {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(CashuTheme.spacing.section),
                )
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
            )
        },
    ) {
        Text(text = title)
    }
}
