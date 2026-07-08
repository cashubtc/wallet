package org.cashu.wallet.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.cashu.wallet.ui.theme.CashuTheme

// M3 empty-state icon is 48dp (component-level, not on the spacing scale).
private val EmptyStateIconSize = 48.dp
private const val EmptyStateActionWidthFraction = 0.7f

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.comfortable),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(EmptyStateIconSize),
        )
        Spacer(Modifier.height(CashuTheme.spacing.comfortable))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (supporting != null) {
            Spacer(Modifier.height(CashuTheme.spacing.snug))
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(CashuTheme.spacing.section))
            PrimaryButton(
                text = actionLabel,
                onClick = onAction,
                // Deliberately narrower than a full-width CTA: the empty-state
                // action is an invitation, not the screen's primary commit.
                modifier = Modifier.fillMaxWidth(EmptyStateActionWidthFraction),
            )
        }
    }
}
