package com.cashu.me.ui.receive

import android.content.ClipData
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cashu.me.ui.components.LocalConfirmationToastController
import com.cashu.me.ui.components.PaymentStatusPhase
import com.cashu.me.ui.components.PaymentStatusScreen
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.components.QrCard
import com.cashu.me.ui.components.SecondaryButton
import com.cashu.me.ui.components.SheetHeader
import com.cashu.me.ui.components.shareText
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.rememberReducedMotion
import com.cashu.me.ui.theme.withSlashedZero

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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/** A native modal owns one focused receive session, from QR through receipt. */
@Composable
fun LightningAddressReceiveModal(
    npcService: NPCService,
    settingsManager: SettingsManager,
    onDismiss: () -> Unit,
) {
    LightningAddressModal(onDismiss) {
        LightningAddressReceiveSession(npcService, settingsManager, onDismiss)
    }
}

@Composable
internal fun LightningAddressModal(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.safeDrawingPadding()) { content() }
        }
    }
}

@Composable
private fun LightningAddressReceiveSession(
    npcService: NPCService,
    settingsManager: SettingsManager,
    onDismiss: () -> Unit,
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
    LightningAddressReceiveContent(
        address = address,
        onDismiss = onDismiss,
        receivedAmount = receipt?.let { formatter.formatWalletSats(it.amount, settings.useBitcoinSymbol) },
        statusMessage = statusMessage,
    )
}

/** Fixed full-screen layout. Only the QR/receipt content crossfades on payment. */
@Composable
internal fun LightningAddressReceiveContent(
    address: String,
    onDismiss: () -> Unit,
    receivedAmount: String? = null,
    statusMessage: String? = null,
) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val toast = LocalConfirmationToastController.current
    val reducedMotion = rememberReducedMotion()
    Column(Modifier.fillMaxSize().testTag("lightning-address-modal-content")) {
        SheetHeader(
            title = "Lightning Address",
            navigationIcon = Icons.Outlined.Close,
            navigationContentDescription = "Close",
            onNavigationClick = onDismiss,
        )
        AnimatedContent(
            targetState = receivedAmount,
            contentKey = { it != null },
            modifier = Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(tween(if (reducedMotion) 0 else 200)) togetherWith
                    fadeOut(tween(if (reducedMotion) 0 else 150))).using(null)
            },
            label = "lightning-address-receipt",
        ) { amount ->
            if (amount != null) {
                PaymentStatusScreen(
                    phase = PaymentStatusPhase.Success,
                    title = "Payment Received!",
                    onDone = onDismiss,
                    successAmount = amount,
                    modifier = Modifier.testTag("lightning-address-payment-received"),
                )
            } else {
                Column(Modifier.fillMaxSize()) {
                    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                        val qrSize = minOf(280.dp, maxWidth - 48.dp)
                        Column(
                            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight)
                                .padding(CashuTheme.spacing.comfortable),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            QrCard(address, size = qrSize, staticOnly = true,
                                shareSubject = "Lightning Address", confirmationMessage = "Copied lightning address")
                            Spacer(Modifier.height(CashuTheme.spacing.comfortable))
                            Text(
                                text = address,
                                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = CashuTheme.fonts.mono).withSlashedZero(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            if (statusMessage != null) {
                                Spacer(Modifier.height(CashuTheme.spacing.comfortable))
                                Text(statusMessage, style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = CashuTheme.spacing.comfortable)
                            .padding(bottom = CashuTheme.spacing.comfortable),
                        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
                    ) {
                        SecondaryButton("Copy", onClick = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Lightning Address", address)))
                                toast?.show("Copied lightning address")
                            }
                        }, modifier = Modifier.weight(1f))
                        PrimaryButton("Share", onClick = { context.shareText(address, "Lightning Address") },
                            colors = ButtonDefaults.buttonColors(), modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
