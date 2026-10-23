package com.cashu.me.ui.send

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cashu.me.Core.Protocols.CurrencyAmount
import com.cashu.me.Core.Protocols.CurrencyRegistry
import com.cashu.me.Core.PendingSendRecovery
import com.cashu.me.Core.SendRecoveryOutcome
import com.cashu.me.Core.WalletManager
import com.cashu.me.ui.components.QrCard
import com.cashu.me.ui.components.shareText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PendingSendRecoveryContent(
    initial: PendingSendRecovery,
    walletManager: WalletManager,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    onWorkingChanged: (Boolean) -> Unit = {},
) {
    var recovery by remember(initial) { mutableStateOf(initial) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var reclaimId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(working) { onWorkingChanged(working) }
    DisposableEffect(Unit) { onDispose { onWorkingChanged(false) } }
    androidx.activity.compose.BackHandler(enabled = working) {}
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun resolve(id: String? = null) {
        working = true
        message = null
        scope.launch {
            try {
                if (id != null) {
                    try {
                        walletManager.reclaimPendingSend(id, recovery.mintUrl, recovery.unit)
                        recovery = PendingSendRecovery(recovery.mintUrl, recovery.unit, recovery.operationIds,
                            recovery.sends.filterNot { it.id == id }, SendRecoveryOutcome.Unverified)
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { message = "Couldn't reclaim the ecash. Checking its status." }
                }
                recovery = walletManager.checkSendRecovery(recovery)
                if (recovery.outcome in listOf(SendRecoveryOutcome.Completed, SendRecoveryOutcome.Returned)) message = null
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "Couldn't verify the send. Check your connection and try checking again."
            } finally { working = false }
        }
    }
    LaunchedEffect(initial) { if (initial.outcome == SendRecoveryOutcome.Unverified) resolve() }
    Column(
        modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            when (recovery.outcome) {
                SendRecoveryOutcome.Pending -> "Pending ecash"
                SendRecoveryOutcome.Unverified -> "Check send status"
                SendRecoveryOutcome.Completed -> "Payment complete"
                SendRecoveryOutcome.Returned -> "Send resolved"
            }, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() },
        )
        Text(recovery.message.orEmpty())
        Text(recovery.mintUrl, style = MaterialTheme.typography.bodySmall)
        recovery.sends.forEach { send ->
            Text(CurrencyAmount(send.amount, CurrencyRegistry.currencyForMintUnit(recovery.unit)).formatted(), style = MaterialTheme.typography.titleMedium)
            if (send.token != null) {
                QrCard(content = send.token, size = 240.dp, showQrControls = true)
                TextButton(onClick = { context.shareText(send.token, subject = "Cashu token") }) { Text("Share ecash") }
            } else {
                Text("The token preview is unavailable. You can still check its status or reclaim it.")
            }
            OutlinedButton(onClick = { reclaimId = send.id }, enabled = !working, modifier = Modifier.testTag("cashu.send.reclaim")) {
                Text("Reclaim ecash")
            }
        }
        message?.let { Text(it) }
        if (working) CircularProgressIndicator()
        if (recovery.outcome in listOf(SendRecoveryOutcome.Pending, SendRecoveryOutcome.Unverified)) {
            Button(onClick = { resolve() }, enabled = !working, modifier = Modifier.testTag("cashu.send.check-recovery")) {
                Text("Check status")
            }
        }
        TextButton(onClick = onDone, enabled = !working) { Text("Done") }
    }
    reclaimId?.let { id ->
        AlertDialog(
            onDismissRequest = { reclaimId = null },
            title = { Text("Reclaim this ecash?") },
            text = { Text("If it hasn't been claimed, the ecash will return to your wallet and the recipient can no longer use this token. Mint fees may apply.") },
            confirmButton = { TextButton(onClick = { reclaimId = null; resolve(id) }) { Text("Reclaim") } },
            dismissButton = { TextButton(onClick = { reclaimId = null }) { Text("Cancel") } },
        )
    }
}
