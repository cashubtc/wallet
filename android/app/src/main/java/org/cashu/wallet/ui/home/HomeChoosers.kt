package org.cashu.wallet.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.Payments
import androidx.compose.runtime.Composable
import org.cashu.wallet.ui.components.ChooserOption
import org.cashu.wallet.ui.components.ChooserSheet

enum class ReceiveAction { Ecash, Bitcoin }

// Receive keeps the compact chooser (iOS WalletActionSheetView). Send has no
// chooser — it opens the unified send surface directly.
@Composable
fun ReceiveChooserSheet(
    onSelect: (ReceiveAction) -> Unit,
    onDismiss: () -> Unit,
) {
    ChooserSheet(
        title = "Receive",
        options = listOf(
            ChooserOption(
                id = ReceiveAction.Ecash.name,
                label = "Ecash",
                icon = Icons.Outlined.Payments,
                supporting = "Redeem a Cashu token or create a request",
            ),
            ChooserOption(
                id = ReceiveAction.Bitcoin.name,
                label = "Bitcoin",
                icon = Icons.Outlined.CurrencyBitcoin,
                supporting = "Lightning invoice or on-chain address",
            ),
        ),
        onSelect = { option -> onSelect(ReceiveAction.valueOf(option.id)) },
        onDismiss = onDismiss,
    )
}
