package com.cashu.me.ui.receive.nfc

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.togetherWith
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Nfc
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.cashu.me.Core.NfcReceive.CashuNfcHostApduService
import com.cashu.me.Core.NfcReceive.NfcReceiveCoordinator
import com.cashu.me.Core.NfcReceive.NfcReceivePhase
import com.cashu.me.Models.CashuRequest
import com.cashu.me.ui.components.PaymentStatusPhase
import com.cashu.me.ui.components.PaymentStatusScreen
import com.cashu.me.ui.components.SpinnerRing
import com.cashu.me.ui.theme.CashuTheme

@Composable
fun NfcReceiveLifecycle(
    coordinator: NfcReceiveCoordinator,
    request: CashuRequest,
    settlementMintUrl: String?,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(activity, lifecycle, coordinator, request, settlementMintUrl) {
        val adapter = NfcAdapter.getDefaultAdapter(context)
        val component = ComponentName(context, CashuNfcHostApduService::class.java)
        fun resume() {
            coordinator.activate(request, settlementMintUrl)
            if (coordinator.isAdvertising && activity != null && adapter?.isEnabled == true) {
                runCatching { CardEmulation.getInstance(adapter).setPreferredService(activity, component) }
                if (Build.VERSION.SDK_INT >= 35) {
                    runCatching {
                        adapter.setDiscoveryTechnology(activity, NfcAdapter.FLAG_READER_DISABLE, NfcAdapter.FLAG_LISTEN_KEEP)
                    }
                }
            }
        }
        fun pause() {
            if (activity != null && adapter != null) {
                runCatching { CardEmulation.getInstance(adapter).unsetPreferredService(activity) }
                if (Build.VERSION.SDK_INT >= 35) runCatching { adapter.resetDiscoveryTechnology(activity) }
            }
            coordinator.deactivate()
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resume()
                Lifecycle.Event.ON_PAUSE -> pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resume()
        onDispose {
            lifecycle.removeObserver(observer)
            pause()
        }
    }
}

@Composable
fun NfcReceiveIndicator(coordinator: NfcReceiveCoordinator, modifier: Modifier = Modifier) {
    val state by coordinator.state.collectAsState()
    val active = state.phase in setOf(
        NfcReceivePhase.Waiting,
        NfcReceivePhase.Connected,
        NfcReceivePhase.Receiving,
        NfcReceivePhase.Validating,
        NfcReceivePhase.Redeeming,
        NfcReceivePhase.Converting,
    )
    val indicatorColor by animateColorAsState(
        targetValue = if (active) CashuTheme.colors.pendingContainer else MaterialTheme.colorScheme.surfaceContainer,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "nfc-indicator-color",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
            .background(indicatorColor, MaterialTheme.shapes.small)
            .padding(horizontal = CashuTheme.spacing.default, vertical = CashuTheme.spacing.default),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
    ) {
        Icon(
            imageVector = Icons.Outlined.Nfc,
            contentDescription = null,
            tint = if (active) CashuTheme.colors.pending else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        AnimatedContent(
            targetState = state.phase to (state.message ?: if (active) "Another wallet can use Send → Tap" else "Open this request with NFC enabled"),
            transitionSpec = {
                fadeIn(spring(stiffness = Spring.StiffnessMedium)) togetherWith
                    fadeOut(spring(stiffness = Spring.StiffnessMedium))
            },
            label = "nfc-indicator-content",
            modifier = Modifier.weight(1f),
        ) { (_, line) ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun NfcReceiveOverlay(coordinator: NfcReceiveCoordinator) {
    val state by coordinator.state.collectAsState()
    val visible = state.phase in setOf(
        NfcReceivePhase.Connected,
        NfcReceivePhase.Receiving,
        NfcReceivePhase.Validating,
        NfcReceivePhase.Redeeming,
        NfcReceivePhase.Converting,
        NfcReceivePhase.Success,
        NfcReceivePhase.Failure,
    )
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(state.phase) {
        if (state.phase == NfcReceivePhase.Connected) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(spring(stiffness = Spring.StiffnessMedium)),
        exit = fadeOut(spring(stiffness = Spring.StiffnessMedium)),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            when (state.phase) {
                NfcReceivePhase.Success -> PaymentStatusScreen(
                    phase = PaymentStatusPhase.Success,
                    title = "Payment received",
                    detail = state.amount?.let { "$it sat added to your wallet" } ?: "Ecash added to your wallet",
                    onDone = coordinator::clearResult,
                )
                NfcReceivePhase.Failure -> PaymentStatusScreen(
                    phase = PaymentStatusPhase.Failure,
                    title = "Payment failed",
                    detail = state.message ?: "The payment could not be received.",
                    doneLabel = "Try again",
                    onDone = coordinator::clearResult,
                )
                NfcReceivePhase.Validating, NfcReceivePhase.Redeeming, NfcReceivePhase.Converting -> PaymentStatusScreen(
                    phase = PaymentStatusPhase.Processing,
                    title = when (state.phase) {
                        NfcReceivePhase.Validating -> "Checking payment"
                        NfcReceivePhase.Redeeming -> "Securing ecash"
                        else -> "Moving to your active mint"
                    },
                    detail = "Transfer complete — you can move the phones apart.",
                )
                else -> NfcTransferScreen()
            }
        }
    }
}

@Composable
private fun NfcTransferScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = CashuTheme.spacing.page),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                SpinnerRing(size = 64.dp, color = CashuTheme.colors.pending)
                Spacer(Modifier.height(CashuTheme.spacing.section))
                Text(
                    text = "Keep phones together",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(CashuTheme.spacing.snug))
                Text(
                    text = "Do not move either phone until the transfer finishes.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
