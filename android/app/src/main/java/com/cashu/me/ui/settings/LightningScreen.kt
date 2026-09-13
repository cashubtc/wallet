package com.cashu.me.ui.settings

import android.content.ClipData

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.cashu.me.Core.NPCService
import com.cashu.me.Core.NPCState
import com.cashu.me.Core.SettingsManager
import com.cashu.me.Core.WalletManager
import com.cashu.me.Core.WalletState
import com.cashu.me.ui.components.InlineNotice
import com.cashu.me.ui.components.LocalConfirmationToastController
import com.cashu.me.ui.components.MintPickerSheet
import com.cashu.me.ui.components.NavRow
import com.cashu.me.ui.components.NoticeSeverity
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.components.SectionHeader
import com.cashu.me.ui.components.SettingsFooterText
import com.cashu.me.ui.components.ToggleRow
import com.cashu.me.ui.components.ToolbarIcon
import com.cashu.me.ui.components.formatRelativeRecency
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.withSlashedZero
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

internal enum class LightningAddressSetupStatus {
    Ready,
    Empty,
    SettingUp,
    NeedsRecovery,
}

internal fun lightningAddressSetupStatus(
    walletState: WalletState,
    npcState: NPCState,
): LightningAddressSetupStatus = when {
    npcState.lightningAddress.isNotBlank() -> LightningAddressSetupStatus.Ready
    !npcState.isEnabled -> LightningAddressSetupStatus.Empty
    !walletState.isRuntimeReady && walletState.startupFailure == null ->
        LightningAddressSetupStatus.SettingUp
    else -> LightningAddressSetupStatus.NeedsRecovery
}

internal object LightningAddressSettingsCopy {
    const val EnableTitle = "Enable Lightning Address"
    const val EnableSubtitle =
        "Receive Lightning payments to your wallet using a Lightning address."
    const val AutomaticClaimTitle = "Auto-claim payments"
    const val PreferencesFooter = "Incoming payments are minted as ecash at your chosen mint."
    const val ReceivingMintTitle = "Receiving mint"
    const val SelectMintFallback = "Select a mint"
    const val CheckPaymentsTitle = "Check for payments"
    const val NeverCheckedCaption = "Not checked yet"
    const val ChecksOffFooter =
        "To check for payments, allow incoming invoice checks in Privacy settings."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightningScreen(
    walletManager: WalletManager,
    npcService: NPCService,
    settingsManager: SettingsManager,
    onClose: () -> Unit,
) {
    val walletState by walletManager.state.collectAsState()
    val npcState by npcService.state.collectAsState()
    val settings by settingsManager.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    val toast = LocalConfirmationToastController.current
    LaunchedEffect(npcService) { npcService.initializeIfEnabled() }

    var mintPickerOpen by remember { mutableStateOf(false) }
    var addressQrOpen by remember { mutableStateOf(false) }
    var retryingSetup by remember { mutableStateOf(false) }
    var setupRecoveryError by remember { mutableStateOf<String?>(null) }

    // The address (and everything downstream of it) only shows while the
    // feature is on — the key derivation runs at startup regardless, so the
    // blank-address check alone would leave a live, copyable address on a
    // disabled feature (iOS gates identically).
    val addressReady = npcState.isEnabled && npcState.lightningAddress.isNotBlank()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lightning", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        ToolbarIcon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            SectionHeader("Lightning address")
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
            ) {
                ToggleRow(
                    title = LightningAddressSettingsCopy.EnableTitle,
                    checked = npcState.isEnabled,
                    onCheckedChange = { npcService.setEnabled(it) },
                )
                if (addressReady) {
                    LightningAddressRow(
                        address = npcState.lightningAddress,
                        onCopy = {
                            scope.launch {
                                clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Lightning Address", npcState.lightningAddress)))
                                toast?.show("Copied Lightning address")
                            }
                        },
                        onShowQr = { addressQrOpen = true },
                    )
                }
                // Status footer slot — mutually exclusive, mirroring iOS: help
                // copy while off, the error when one is set, setup feedback
                // while the address is still being derived.
                when {
                    !npcState.isEnabled ->
                        SettingsFooterText(LightningAddressSettingsCopy.EnableSubtitle)
                    npcState.errorMessage != null -> InlineNotice(
                        text = npcState.errorMessage!!,
                        modifier = Modifier.padding(
                            horizontal = CashuTheme.spacing.comfortable,
                            vertical = CashuTheme.spacing.snug,
                        ),
                        severity = NoticeSeverity.Error,
                    )
                    npcState.lightningAddress.isBlank() -> LightningAddressSetupFeedback(
                        status = lightningAddressSetupStatus(walletState, npcState),
                        retrying = retryingSetup,
                        recoveryError = setupRecoveryError,
                        onRetry = {
                            scope.launch {
                                retryingSetup = true
                                setupRecoveryError = null
                                try {
                                    walletManager.retryLightningAddressSetup()
                                } catch (cancellation: CancellationException) {
                                    throw cancellation
                                } catch (_: Throwable) {
                                    setupRecoveryError =
                                        "Lightning address setup couldn't finish. Try again or restart the app."
                                } finally {
                                    retryingSetup = false
                                }
                            }
                        },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
                verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
            ) {
                if (addressReady) {
                    SectionHeader("Preferences")
                    ToggleRow(
                        title = LightningAddressSettingsCopy.AutomaticClaimTitle,
                        checked = npcState.automaticClaim,
                        onCheckedChange = { npcService.setAutomaticClaim(it) },
                    )
                    if (walletState.mints.isNotEmpty()) {
                        NavRow(
                            title = LightningAddressSettingsCopy.ReceivingMintTitle,
                            trailingValue = walletState.mints
                                .firstOrNull { it.url == npcState.selectedMintUrl }?.name
                                ?: LightningAddressSettingsCopy.SelectMintFallback,
                            onClick = { mintPickerOpen = true },
                        )
                    }
                    SettingsFooterText(LightningAddressSettingsCopy.PreferencesFooter)
                    CheckForPaymentsRow(
                        checking = npcState.isCheckingPayments,
                        lastCheckEpochMillis = npcState.lastCheckEpochMillis,
                        allowed = settings.checkIncomingInvoices,
                        onClick = { npcService.checkAndClaimPayments() },
                    )
                    if (!settings.checkIncomingInvoices) {
                        SettingsFooterText(LightningAddressSettingsCopy.ChecksOffFooter)
                    }
                }
            }
        }
    }

    if (mintPickerOpen) {
        MintPickerSheet(
            mints = walletState.mints,
            activeMintUrl = npcState.selectedMintUrl,
            onSelect = { mint ->
                mint?.let { npcService.changeMint(it.url) }
                mintPickerOpen = false
            },
            onDismiss = { mintPickerOpen = false },
            // Titled after the row that opened it, so the sheet reads as a
            // continuation of the tap rather than a new context.
            title = LightningAddressSettingsCopy.ReceivingMintTitle,
        )
    }

    if (addressQrOpen) {
        com.cashu.me.ui.receive.LightningAddressReceiveModal(
            npcService = npcService,
            settingsManager = settingsManager,
            onDismiss = { addressQrOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun LightningAddressSetupFeedback(
    status: LightningAddressSetupStatus,
    retrying: Boolean,
    recoveryError: String?,
    onRetry: () -> Unit,
) {
    val contentModifier = Modifier.padding(
        horizontal = CashuTheme.spacing.comfortable,
        vertical = CashuTheme.spacing.snug,
    )
    when (status) {
        LightningAddressSetupStatus.Ready -> Unit
        LightningAddressSetupStatus.Empty -> Text(
            text = "No Lightning address configured. Enable below to receive at an @ address.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = contentModifier,
        )
        LightningAddressSetupStatus.SettingUp -> Row(
            modifier = contentModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        ) {
            LoadingIndicator(
                modifier = Modifier.size(CashuTheme.spacing.loose),
            )
            Text(
                text = "Setting up Lightning address…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LightningAddressSetupStatus.NeedsRecovery -> Column(
            modifier = contentModifier,
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            InlineNotice(
                text = recoveryError
                    ?: "Wallet not fully initialized. Try setup again to finish your Lightning address.",
                severity = NoticeSeverity.Error,
            )
            PrimaryButton(
                text = "Try setup again",
                onClick = onRetry,
                loading = retrying,
                enabled = !retrying,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun LightningAddressRow(
    address: String,
    onCopy: () -> Unit,
    onShowQr: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = CashuTheme.spacing.comfortable,
                end = CashuTheme.spacing.snug,
                top = CashuTheme.spacing.snug,
                bottom = CashuTheme.spacing.snug,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f)
                .heightIn(min = 48.dp)
                .clickable(onClick = onShowQr)
                .semantics { contentDescription = "Lightning address: $address" }
                .padding(end = CashuTheme.spacing.snug),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = address,
                style = MaterialTheme.typography.bodyLarge.copy(fontFamily = CashuTheme.fonts.mono).withSlashedZero(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copy Lightning address",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onShowQr) {
            Icon(
                imageVector = Icons.Outlined.QrCode2,
                contentDescription = "Show Lightning address QR code",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Quiet manual check row (iOS `checkForPaymentsRow` parity): an icon slot that
 * swaps to a spinner while a check runs, with the persisted recency as the
 * caption. Dimmed and inert while the Privacy invoice-check toggle is off —
 * the footer under it says why.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CheckForPaymentsRow(
    checking: Boolean,
    lastCheckEpochMillis: Long?,
    allowed: Boolean,
    onClick: () -> Unit,
) {
    val caption = lastCheckEpochMillis
        ?.let { "Last checked ${formatRelativeRecency(it)}" }
        ?: LightningAddressSettingsCopy.NeverCheckedCaption
    ListItem(
        modifier = Modifier
            .alpha(if (allowed) 1f else 0.5f)
            .clickable(enabled = allowed && !checking, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                modifier = Modifier.size(CashuTheme.spacing.loose),
                contentAlignment = Alignment.Center,
            ) {
                if (checking) {
                    LoadingIndicator(
                        modifier = Modifier.size(CashuTheme.spacing.loose),
                    )
                } else {
                    Icon(
                        imageVector = Icons.Outlined.Refresh,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        headlineContent = { Text(LightningAddressSettingsCopy.CheckPaymentsTitle) },
        supportingContent = {
            Text(
                text = caption,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}
