package com.cashu.me.ui.receive

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.Core.NPCPaymentReceipt
import com.cashu.me.Core.NPCService
import com.cashu.me.Core.SettingsManager
import com.cashu.me.ui.settings.QrDetailSheet
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** The same focused receive session is reachable from settings and Receive Bitcoin. */
@Composable
fun LightningAddressReceiveSheet(
    npcService: NPCService,
    settingsManager: SettingsManager,
    onDismiss: () -> Unit,
    onPaymentReceived: ((NPCPaymentReceipt) -> Unit)? = null,
) {
    val npc by npcService.state.collectAsState()
    val settings by settingsManager.state.collectAsState()
    val address = remember { npc.lightningAddress }
    val openedAt = remember { System.currentTimeMillis() }
    var receipt by remember { mutableStateOf<NPCPaymentReceipt?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val formatter = remember { AmountFormatter() }

    LaunchedEffect(npcService, lifecycleOwner, receipt != null) {
        if (receipt != null) return@LaunchedEffect
        // Subscribe before the immediate catch-up, including fast local claims.
        launch(start = CoroutineStart.UNDISPATCHED) {
            npcService.receivedPayments.collect { payment ->
                if (receipt == null && payment.belongsToReceiveSession(address, openedAt)) receipt = payment
            }
        }
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            npcService.monitorPayments(address, openedAt)
        }
    }
    LaunchedEffect(npc.isEnabled, npc.lightningAddress) {
        if (!npc.isEnabled || npc.lightningAddress != address) onDismiss()
    }
    val pending = npc.pendingPaidQuotes.lastOrNull { quote ->
        NPCPaymentReceipt(quote.id, address, quote.amount, quote.paidAtEpochSeconds)
            .belongsToReceiveSession(address, openedAt)
    }
    val statusMessage = when {
        !settings.checkIncomingInvoices -> "Payment checks are off in Privacy settings."
        npc.errorMessage != null -> npc.errorMessage
        !npc.automaticClaim && pending != null ->
            "Payment detected: ${formatter.formatWalletSats(pending.amount, settings.useBitcoinSymbol)}. Auto-claim is off."
        !npc.automaticClaim -> "Auto-claim is off. Enable it in Lightning settings to add payments to your wallet."
        else -> null
    }
    QrDetailSheet(
        title = "Lightning Address",
        content = address,
        onDismiss = onDismiss,
        receivedAmount = receipt?.let { formatter.formatWalletSats(it.amount, settings.useBitcoinSymbol) },
        statusMessage = statusMessage,
        showsContent = false,
        onPaymentReceived = onPaymentReceived?.let { receive ->
            { receipt?.let(receive); Unit }
        },
    )
}
