package org.cashu.wallet.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cashu.wallet.Core.NostrService
import org.cashu.wallet.Core.NostrSignerType
import org.cashu.wallet.Core.AppLockManager
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.CashuTextField
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.InspectorRow
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.SectionHeader
import org.cashu.wallet.ui.security.rememberWalletAuthenticationLauncher
import org.cashu.wallet.ui.theme.CashuTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NostrScreen(
    nostrService: NostrService,
    settingsManager: SettingsManager,
    appLockManager: AppLockManager,
    onClose: () -> Unit,
) {
    val nostrState by nostrService.state.collectAsState()
    val settings by settingsManager.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val authenticate = rememberWalletAuthenticationLauncher(appLockManager)
    var nsecSheetOpen by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var addRelayOpen by remember { mutableStateOf(false) }
    var addRelayError by remember { mutableStateOf<String?>(null) }
    var showGenerateConfirm by remember { mutableStateOf(false) }
    var showResetConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nostr", style = MaterialTheme.typography.titleMedium) },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            NostrKeyStatusPanel(
                signerType = nostrState.signerType,
                initialized = nostrState.isInitialized,
                npub = nostrState.npub,
            )

            SectionHeader("Signer")
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = CashuTheme.spacing.comfortable)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    NostrSignerType.entries.forEachIndexed { index, kind ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(
                                index = index,
                                count = NostrSignerType.entries.size,
                            ),
                            selected = kind == nostrState.signerType,
                            onClick = {
                                runCatching { nostrService.switchSignerType(kind) }
                            },
                        ) { Text(kind.displayName) }
                    }
                }
                Spacer(Modifier.height(CashuTheme.spacing.snug))
                Text(
                    text = when (nostrState.signerType) {
                        NostrSignerType.Seed -> "Keys are derived from your wallet seed."
                        NostrSignerType.PrivateKey -> "Custom key stored in secure storage."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            SectionHeader("Public identity")
            InspectorRow(
                label = "npub",
                value = nostrState.npub.ifBlank { "—" },
                valueMonospaced = true,
                onClick = { clipboard.setText(AnnotatedString(nostrState.npub)) },
                editable = nostrState.npub.isNotBlank(),
            )
            CanvasDivider(leadingInset = 16)
            InspectorRow(
                label = "hex",
                value = nostrState.publicKeyHex.ifBlank { "—" },
                valueMonospaced = true,
                onClick = { clipboard.setText(AnnotatedString(nostrState.publicKeyHex)) },
                editable = nostrState.publicKeyHex.isNotBlank(),
            )

            SectionHeader("Private key")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = CashuTheme.spacing.comfortable,
                        vertical = CashuTheme.spacing.snug,
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
            ) {
                Text(
                    text = "•".repeat(12),
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                IconButton(
                    onClick = {
                        authenticate("Reveal your Nostr private key") {
                            nsecSheetOpen = true
                        }
                    },
                    enabled = nostrState.nsec.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Visibility,
                        contentDescription = "Reveal",
                    )
                }
                IconButton(
                    onClick = {
                        authenticate("Copy your Nostr private key") {
                            clipboard.setText(AnnotatedString(nostrState.nsec))
                        }
                    },
                    enabled = nostrState.nsec.isNotBlank(),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = "Copy nsec",
                    )
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = CashuTheme.spacing.comfortable),
                verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
            ) {
                PrimaryButton(
                    text = "Generate new key",
                    onClick = { showGenerateConfirm = true },
                )
                GhostButton(
                    text = "Import nsec…",
                    onClick = { showImport = true },
                    modifier = Modifier.fillMaxWidth(),
                )
                GhostButton(
                    text = "Reset to wallet seed",
                    onClick = { showResetConfirm = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = nostrState.signerType != NostrSignerType.Seed,
                )
            }

            SectionHeader("Relays")
            if (settings.nostrRelays.isEmpty()) {
                Text(
                    text = "Using defaults (relay.damus.io, nos.lol, primal.net, 8333.space).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(
                        horizontal = CashuTheme.spacing.comfortable,
                        vertical = CashuTheme.spacing.snug,
                    ),
                )
            } else {
                Column(modifier = Modifier.fillMaxWidth()) {
                    settings.nostrRelays.forEachIndexed { index, relay ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(
                                    horizontal = CashuTheme.spacing.comfortable,
                                    vertical = CashuTheme.spacing.default,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = relay,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.MiddleEllipsis,
                            )
                            IconButton(onClick = { settingsManager.removeRelay(relay) }) {
                                Icon(
                                    imageVector = Icons.Outlined.Close,
                                    contentDescription = "Remove relay",
                                    modifier = Modifier.size(CashuTheme.spacing.loose),
                                )
                            }
                        }
                        if (index != settings.nostrRelays.lastIndex) CanvasDivider(leadingInset = 16)
                    }
                }
            }
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = CashuTheme.spacing.comfortable),
                verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
            ) {
                PrimaryButton(
                    text = "Add relay…",
                    onClick = { addRelayOpen = true },
                )
                GhostButton(
                    text = "Reset to defaults",
                    onClick = { settingsManager.resetNostrRelaysToDefault() },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(CashuTheme.spacing.section))
        }
    }

    if (nsecSheetOpen) {
        var copied by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { nsecSheetOpen = false },
            title = { Text("Nostr private key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug)) {
                    InlineNotice(
                        text = "Anyone with this nsec can act as your wallet's Nostr identity. Only share it with software you trust.",
                    )
                    Text(
                        text = nostrState.nsec,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    authenticate("Copy your Nostr private key") {
                        clipboard.setText(AnnotatedString(nostrState.nsec))
                        copied = true
                    }
                }) {
                    Text(if (copied) "Copied" else "Copy")
                }
            },
            dismissButton = {
                TextButton(onClick = { nsecSheetOpen = false }) { Text("Done") }
            },
        )
    }

    if (showImport) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImport = false; importError = null },
            title = { Text("Import nsec") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug)) {
                    CashuTextField(
                        value = input,
                        onValueChange = { input = it; importError = null },
                        label = "nsec1…",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (importError != null) {
                        InlineNotice(text = importError!!)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { nostrService.importNsec(input.trim()) }
                        .onSuccess { showImport = false; importError = null }
                        .onFailure { importError = it.message ?: "Could not import." }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = { showImport = false; importError = null }) { Text("Cancel") }
            },
        )
    }

    if (showGenerateConfirm) {
        AlertDialog(
            onDismissRequest = { showGenerateConfirm = false },
            title = { Text("Generate new key") },
            text = {
                Text(
                    "Replace your current Nostr identity with a freshly generated key? Your old public key will stop working for messages and contacts.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showGenerateConfirm = false
                    runCatching { nostrService.generateRandomKeypair() }
                }) { Text("Generate") }
            },
            dismissButton = {
                TextButton(onClick = { showGenerateConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (showResetConfirm) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text("Reset to wallet seed") },
            text = {
                Text(
                    "Replace your Nostr identity with one derived from your wallet seed. Your current custom key will be removed.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showResetConfirm = false
                    runCatching { nostrService.resetToSeedKey() }
                }) {
                    Text("Reset", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) { Text("Cancel") }
            },
        )
    }

    if (addRelayOpen) {
        var input by remember { mutableStateOf("wss://") }
        AlertDialog(
            onDismissRequest = { addRelayOpen = false; addRelayError = null },
            title = { Text("Add relay") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug)) {
                    CashuTextField(
                        value = input,
                        onValueChange = { input = it; addRelayError = null },
                        label = "wss:// URL",
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    if (addRelayError != null) {
                        InlineNotice(text = addRelayError!!)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { settingsManager.addRelay(input.trim()) }
                        .onSuccess { addRelayOpen = false; addRelayError = null }
                        .onFailure { addRelayError = it.message ?: "Invalid relay URL." }
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { addRelayOpen = false; addRelayError = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun NostrKeyStatusPanel(
    signerType: NostrSignerType,
    initialized: Boolean,
    npub: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(CashuTheme.spacing.comfortable)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(CashuTheme.spacing.comfortable),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Icon(
            imageVector = if (initialized) Icons.Outlined.Security else Icons.Outlined.Key,
            contentDescription = null,
            tint = if (initialized) CashuTheme.colors.received else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(28.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (initialized) "Nostr identity ready" else "Nostr identity unavailable",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = when {
                    !initialized -> "Unlock or restore the wallet seed to initialize Nostr."
                    signerType == NostrSignerType.Seed -> "Using the wallet-seed key"
                    else -> "Using a custom nsec"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (npub.isNotBlank()) {
                Text(
                    text = npub.take(16) + "…" + npub.takeLast(8),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
            }
        }
    }
}
