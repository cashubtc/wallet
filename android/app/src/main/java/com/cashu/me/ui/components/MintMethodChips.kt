package com.cashu.me.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.ui.theme.CashuTheme

/** Small capsule pills describing what a mint supports — Lightning, Bitcoin (onchain), Ecash.
 *  Vertical padding is intentionally micro (4dp): these are inline-with-text mini-pills,
 *  not interactive M3 chips. The 4dp keeps the row body breathable on cramped mint rows. */
@Composable
fun MintMethodChips(
    mint: MintInfo,
    modifier: Modifier = Modifier,
) {
    val methods = remember(mint.supportedMintMethods, mint.supportedMeltMethods) {
        (mint.supportedMintMethods + mint.supportedMeltMethods).distinct().sortedBy { it.sortOrder }
    }
    if (methods.isEmpty()) return
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.tight),
    ) {
        methods.forEach { method ->
            val (label, tint) = methodAppearance(method)
            MethodPill(label = label, tint = tint)
        }
    }
}

@Composable
private fun MethodPill(label: String, tint: Color) {
    Text(
        text = label,
        color = tint,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(percent = 50))
            .padding(horizontal = CashuTheme.spacing.snug, vertical = CashuTheme.spacing.micro),
    )
}

@Composable
private fun methodAppearance(method: PaymentMethodKind): Pair<String, Color> = when (method) {
    PaymentMethodKind.Bolt11 -> "Lightning" to MaterialTheme.colorScheme.onSurface
    PaymentMethodKind.Bolt12 -> "Lightning offers" to MaterialTheme.colorScheme.onSurface
    PaymentMethodKind.Onchain -> "Bitcoin" to CashuTheme.colors.pending
}
