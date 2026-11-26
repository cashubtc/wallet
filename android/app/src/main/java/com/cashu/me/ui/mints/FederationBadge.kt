package com.cashu.me.ui.mints

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cashu.me.Models.FederationMintMetadata
import com.cashu.me.ui.theme.CashuTheme

/** "Federation · 3 of 5" with a people glyph, shared by the mint list and detail (iOS parity). */
@Composable
internal fun FederationBadge(
    federation: FederationMintMetadata,
    modifier: Modifier = Modifier,
    textStyle: TextStyle = MaterialTheme.typography.labelSmall,
    iconSize: Dp = 14.dp,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight),
    ) {
        Icon(
            imageVector = Icons.Outlined.Groups,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(iconSize),
        )
        Text(
            text = federation.summaryLabel,
            style = textStyle,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
        )
    }
}
