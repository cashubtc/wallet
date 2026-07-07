package org.cashu.wallet.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cashu.wallet.Core.NPCService
import org.cashu.wallet.Core.NPCState
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.InspectorRow
import org.cashu.wallet.ui.components.MintPickerSheet
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.QrCard
import org.cashu.wallet.ui.components.SectionHeader
import org.cashu.wallet.ui.components.ToggleRow
import org.cashu.wallet.ui.components.copyTextWithToast
import org.cashu.wallet.ui.components.formatRelativeTimestamp
import org.cashu.wallet.ui.theme.CashuTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LightningScreen(
    walletManager: WalletManager,
    npcService: NPCService,
    onClose: () -> Unit,
) {
    val walletState by walletManager.state.collectAsStateWithLifecycle()
    val npcState by npcService.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var mintPickerOpen by remember { mutableStateOf(false) }
    var addressQrOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lightning", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
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
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            SectionHeader("Lightning address")
            ToggleRow(
                title = "Enable Lightning Address",
                subtitle = "Receive Lightning payments at a Nostr-backed @ address",
                checked = npcState.isEnabled,
                onCheckedChange = { npcService.setEnabled(it) },
            )
            if (!npcState.isEnabled) {
                Text(
                    text = "Receive Lightning payments to your wallet using a Lightning address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = CashuTheme.spacing.comfortable,
                        vertical = CashuTheme.spacing.snug,
                    ),
                )
            } else if (npcState.isInitialized && npcState.lightningAddress.isNotBlank()) {
                CanvasDivider(leadingInset = 16)
                LightningAddressRow(
                    address = npcState.lightningAddress,
                    statusColor = npcStatusColor(npcState),
                    onShowQr = { addressQrOpen = true },
                )
                CanvasDivider(leadingInset = 16)
                Column(
                    modifier = Modifier.fillMaxWidth().padding(
                        horizontal = CashuTheme.spacing.comfortable,
                        vertical = CashuTheme.spacing.snug,
                    ),
                ) {
                    GhostButton(
                        text = "Copy address",
                        onClick = {
                            clipboard.copyTextWithToast(context, npcState.lightningAddress)
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (npcState.errorMessage != null) {
                    InlineNotice(
                        text = npcState.errorMessage!!,
                        modifier = Modifier.padding(horizontal = CashuTheme.spacing.comfortable),
                    )
                }
            } else {
                CanvasDivider(leadingInset = 16)
                Text(
                    text = "Wallet not fully initialized. Restart the app or restore the wallet seed before enabling Lightning Address.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = CashuTheme.spacing.comfortable,
                        vertical = CashuTheme.spacing.snug,
                    ),
                )
            }

            if (npcState.isEnabled && npcState.isInitialized) {
                SectionHeader("Preferences")
                ToggleRow(
                    title = "Auto-claim payments",
                    subtitle = "Mint paid quotes as ecash at the selected mint",
                    checked = npcState.automaticClaim,
                    onCheckedChange = { npcService.setAutomaticClaim(it) },
                )
                CanvasDivider(leadingInset = 16)
                val mintLabel = walletState.mints.firstOrNull { it.url == npcState.selectedMintUrl }?.name
                    ?: walletState.activeMint?.name
                    ?: "Select a mint"
                InspectorRow(
                    label = "Receiving mint",
                    value = mintLabel,
                    editable = walletState.mints.isNotEmpty(),
                    onClick = { if (walletState.mints.isNotEmpty()) mintPickerOpen = true },
                )
                Text(
                    text = "Incoming payments are minted as ecash at your chosen mint.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = CashuTheme.spacing.comfortable,
                        vertical = CashuTheme.spacing.snug,
                    ),
                )

                SectionHeader("Payments")
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = CashuTheme.spacing.comfortable),
                    verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
                ) {
                    PrimaryButton(
                        text = if (npcState.isCheckingPayments) "Checking…" else "Check for payments",
                        onClick = { npcService.checkAndClaimPayments() },
                        enabled = !npcState.isCheckingPayments,
                        loading = npcState.isCheckingPayments,
                    )
                    npcState.lastCheckEpochMillis?.let { lastCheck ->
                        Text(
                            text = "Last checked ${formatRelativeTimestamp(lastCheck)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (mintPickerOpen) {
        MintPickerSheet(
            mints = walletState.mints,
            activeMintUrl = npcState.selectedMintUrl ?: walletState.activeMint?.url,
            onSelect = { mint ->
                npcService.changeMint(mint.url)
                mintPickerOpen = false
            },
            onDismiss = { mintPickerOpen = false },
            title = "Mint for Lightning",
        )
    }

    if (addressQrOpen) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { addressQrOpen = false },
            sheetState = sheetState,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(
                    horizontal = CashuTheme.spacing.comfortable,
                    vertical = CashuTheme.spacing.snug,
                ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
            ) {
                Text(
                    text = "Lightning Address",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                QrCard(
                    content = npcState.lightningAddress,
                    shareSubject = "Lightning address",
                    staticOnly = true,
                )
                Text(
                    text = npcState.lightningAddress,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(CashuTheme.spacing.snug))
            }
        }
    }
}

@Composable
private fun LightningAddressRow(
    address: String,
    statusColor: Color,
    onShowQr: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onShowQr)
            .padding(
                horizontal = CashuTheme.spacing.comfortable,
                vertical = CashuTheme.spacing.default,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Box(
            modifier = Modifier
                .size(CashuTheme.spacing.snug)
                .clip(CircleShape)
                .background(statusColor),
        )
        Text(
            text = address,
            style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
        )
        Icon(
            imageVector = Icons.Outlined.QrCode2,
            contentDescription = "Show QR",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(CashuTheme.spacing.loose),
        )
    }
}

@Composable
private fun npcStatusColor(state: NPCState): Color {
    return when {
        state.errorMessage != null -> MaterialTheme.colorScheme.error
        state.isConnected -> CashuTheme.colors.received
        else -> CashuTheme.colors.pending
    }
}
