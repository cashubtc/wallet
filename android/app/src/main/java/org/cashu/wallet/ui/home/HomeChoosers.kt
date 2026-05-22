package org.cashu.wallet.ui.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.Money
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.runtime.Composable
import org.cashu.wallet.ui.components.ChooserOption
import org.cashu.wallet.ui.components.ChooserSheet

enum class ReceiveAction { Ecash, Bitcoin }
enum class SendAction { Ecash, Bitcoin, Contactless }

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
                icon = Icons.Outlined.Money,
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

@Composable
fun SendChooserSheet(
    showContactless: Boolean,
    onSelect: (SendAction) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = buildList {
        add(
            ChooserOption(
                id = SendAction.Ecash.name,
                label = "Ecash",
                icon = Icons.Outlined.Money,
                supporting = "Generate a Cashu token to share",
            )
        )
        add(
            ChooserOption(
                id = SendAction.Bitcoin.name,
                label = "Bitcoin",
                icon = Icons.Outlined.CurrencyBitcoin,
                supporting = "Pay a Lightning invoice or on-chain address",
            )
        )
        if (showContactless) {
            add(
                ChooserOption(
                    id = SendAction.Contactless.name,
                    label = "Contactless",
                    icon = Icons.Outlined.Nfc,
                    supporting = "Tap a payment tag",
                )
            )
        }
    }
    ChooserSheet(
        title = "Send",
        options = options,
        onSelect = { option -> onSelect(SendAction.valueOf(option.id)) },
        onDismiss = onDismiss,
    )
}
