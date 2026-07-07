package org.cashu.wallet.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cashu.wallet.ui.theme.CashuTheme

// 56dp is the M3 CTA height; the 14dp internal vertical padding centers the
// labelLarge baseline inside that height and doesn't belong on the spacing scale.
private val ButtonMinHeight = 56.dp
private val ButtonContentVertical = 14.dp
private val ButtonProgressSize = 20.dp

/**
 * The Singular Button: every full-width CTA — primary and secondary — is the same
 * tonal capsule (the M3 translation of the iOS glass capsule). Hierarchy comes
 * from order, copy, and disabled state; there is deliberately no bolder filled
 * variant and no outline variant. Labels are text-only (Iconless-CTA Rule).
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = ButtonMinHeight),
        enabled = enabled && !loading,
        shape = MaterialTheme.shapes.extraLarge,
        contentPadding = PaddingValues(horizontal = CashuTheme.spacing.section, vertical = ButtonContentVertical),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(ButtonProgressSize),
                    strokeWidth = 2.dp,
                    color = LocalContentColor.current,
                )
            } else {
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/** Inline non-emphasized action (Copy, Paste, Restore from seed, etc.). */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}

/** Destructive inline action (Delete Wallet, Remove Mint). */
@Composable
fun DestructiveTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    TextButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.textButtonColors(
            contentColor = MaterialTheme.colorScheme.error,
        ),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelLarge)
    }
}
