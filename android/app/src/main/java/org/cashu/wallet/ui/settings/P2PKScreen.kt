package org.cashu.wallet.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cashu.wallet.Core.AppLockManager
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Models.P2PKKeyInfo
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.CashuTextField
import org.cashu.wallet.ui.components.EmptyState
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.QrCard
import org.cashu.wallet.ui.components.SectionHeader
import org.cashu.wallet.ui.components.ToggleRow
import org.cashu.wallet.ui.components.copyTextWithToast
import org.cashu.wallet.ui.navigation.SimpleBackAction
import org.cashu.wallet.ui.navigation.p2pkBackAction
import org.cashu.wallet.ui.security.rememberWalletAuthenticationLauncher
import org.cashu.wallet.ui.theme.CashuTheme

private sealed interface P2PKDetail {
    data object Primary : P2PKDetail
    data class Stored(val id: String) : P2PKDetail
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun P2PKScreen(
    settingsManager: SettingsManager,
    appLockManager: AppLockManager,
    onClose: () -> Unit,
) {
    val settings by settingsManager.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var showImport by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    var showExplainer by remember { mutableStateOf(false) }
    var detail by remember { mutableStateOf<P2PKDetail?>(null) }

    LaunchedEffect(detail, settings.p2pkKeys) {
        val selected = detail as? P2PKDetail.Stored ?: return@LaunchedEffect
        if (settings.p2pkKeys.none { it.id == selected.id }) detail = null
    }

    BackHandler(enabled = detail != null) {
        when (p2pkBackAction(showingDetail = detail != null)) {
            SimpleBackAction.CloseDetail -> detail = null
            else -> Unit
        }
    }

    val selectedDetail = detail
    if (selectedDetail != null) {
        P2PKKeyDetailScreen(
            detail = selectedDetail,
            settingsManager = settingsManager,
            appLockManager = appLockManager,
            onBack = { detail = null },
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Locked Ecash", style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showExplainer = true }) {
                            Icon(Icons.Outlined.HelpOutline, contentDescription = "Help")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
            ) {
                item("primary-header") {
                    SectionHeader("Your key")
                }
                item("primary-key") {
                    val primaryKey = settingsManager.primaryP2PKPublicKey()
                    if (primaryKey == null) {
                        EmptyState(
                            icon = Icons.Outlined.Key,
                            title = "Wallet key unavailable",
                            supporting = "Open the wallet once your seed and Nostr identity are ready.",
                        )
                    } else {
                        P2PKRow(
                            title = "Your key",
                            subtitle = if (settingsManager.primaryP2PKIsSeedBacked()) {
                                "Seed-backed and recoverable"
                            } else {
                                "Custom Nostr key"
                            },
                            publicKey = primaryKey,
                            leadingIcon = Icons.Outlined.Key,
                            onClick = { detail = P2PKDetail.Primary },
                            onCopy = { clipboard.copyTextWithToast(context, primaryKey) },
                        )
                    }
                }

                item("quick-lock-header") {
                    SectionHeader("Quick lock")
                }
                item("quick-lock-toggle") {
                    ToggleRow(
                        title = "Use your key in Send",
                        subtitle = "Show a quick fill for locking outgoing ecash to your key",
                        checked = settings.showP2PKButtonInDrawer,
                        onCheckedChange = settingsManager::setShowP2PKButtonInDrawer,
                    )
                }

                item("advanced-header") {
                    SectionHeader("Advanced keys")
                }
                if (settings.p2pkKeys.isEmpty()) {
                    item("empty-keys") {
                        EmptyState(
                            icon = Icons.Outlined.VpnKey,
                            title = "No device keys",
                            supporting = "Generate or import a key for device-local locked ecash experiments.",
                        )
                    }
                } else {
                    items(settings.p2pkKeys, key = { it.id }) { key ->
                        P2PKRow(
                            title = key.label.ifBlank { "P2PK key" },
                            subtitle = "${key.usedCount} uses",
                            publicKey = key.publicKey,
                            leadingIcon = Icons.Outlined.VpnKey,
                            onClick = { detail = P2PKDetail.Stored(key.id) },
                            onCopy = { clipboard.copyTextWithToast(context, key.publicKey) },
                        )
                        CanvasDivider(leadingInset = 16)
                    }
                }

                item("advanced-actions") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = CashuTheme.spacing.comfortable),
                        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
                    ) {
                        PrimaryButton(
                            text = "Generate device key",
                            onClick = {
                                runCatching { settingsManager.generateP2PKKey() }
                                    .onFailure { importError = it.message ?: "Could not generate key." }
                            },
                        )
                        GhostButton(
                            text = "Import nsec",
                            onClick = {
                                importError = null
                                showImport = true
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
                item("explainer") {
                    InlineNotice(
                        text = "Locked ecash uses P2PK so a token can be claimed only by a wallet holding the matching key.",
                    )
                }
                item("bottom") {
                    Spacer(Modifier.navigationBarsPadding())
                }
            }
        }
    }

    if (showImport) {
        var input by remember { mutableStateOf("") }
        AlertDialog(
            modifier = Modifier.imePadding(),
            onDismissRequest = {
                showImport = false
                importError = null
            },
            title = { Text("Import key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug)) {
                    CashuTextField(
                        value = input,
                        onValueChange = { input = it; importError = null },
                        label = "nsec1...",
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
                    runCatching { settingsManager.importP2PKNsec(input.trim()) }
                        .onSuccess {
                            showImport = false
                            importError = null
                        }
                        .onFailure { importError = it.message ?: "Could not import key." }
                }) { Text("Import") }
            },
            dismissButton = {
                TextButton(onClick = {
                    showImport = false
                    importError = null
                }) { Text("Cancel") }
            },
        )
    }

    if (showExplainer) {
        AlertDialog(
            onDismissRequest = { showExplainer = false },
            title = { Text("Locked ecash") },
            text = {
                Text(
                    "Share your public key or locked receive request when you want incoming ecash to be spendable only by this wallet. Your seed-backed key can be restored with your seed phrase.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = { showExplainer = false }) { Text("Done") }
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun P2PKKeyDetailScreen(
    detail: P2PKDetail,
    settingsManager: SettingsManager,
    appLockManager: AppLockManager,
    onBack: () -> Unit,
) {
    val settings by settingsManager.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val authenticate = rememberWalletAuthenticationLauncher(appLockManager)
    var privateRevealed by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var showRemove by remember { mutableStateOf(false) }

    val key = (detail as? P2PKDetail.Stored)?.let { selected ->
        settings.p2pkKeys.firstOrNull { it.id == selected.id }
    }
    val publicKey = when (detail) {
        P2PKDetail.Primary -> settingsManager.primaryP2PKPublicKey()
        is P2PKDetail.Stored -> key?.publicKey
    }
    val privateNsec = when (detail) {
        P2PKDetail.Primary -> settingsManager.primaryP2PKNsec()
        is P2PKDetail.Stored -> key?.let { settingsManager.p2pkNsec(it.id) }
    }
    val title = when (detail) {
        P2PKDetail.Primary -> "Your key"
        is P2PKDetail.Stored -> key?.label?.ifBlank { "P2PK key" } ?: "P2PK key"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (detail is P2PKDetail.Stored && key != null) {
                        IconButton(onClick = { showRename = true }) {
                            Icon(Icons.Outlined.Edit, contentDescription = "Rename")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            item("qr") {
                if (publicKey != null) {
                    QrCard(
                        content = publicKey,
                        modifier = Modifier.fillMaxWidth(),
                        sizeDp = 240,
                        showQrControls = false,
                        staticOnly = true,
                        shareSubject = "P2PK public key",
                    )
                }
            }
            item("public") {
                KeyValueRow(
                    label = "Public key",
                    value = publicKey ?: "-",
                    onCopy = publicKey?.let { { clipboard.copyTextWithToast(context, it) } },
                )
            }
            item("status") {
                KeyValueRow(
                    label = "Status",
                    value = when (detail) {
                        P2PKDetail.Primary -> if (settingsManager.primaryP2PKIsSeedBacked()) {
                            "Seed-backed"
                        } else {
                            "Custom Nostr key"
                        }
                        is P2PKDetail.Stored -> if (key?.used == true) "Used ${key.usedCount} times" else "Unused"
                    },
                    onCopy = null,
                    monospaced = false,
                )
            }
            if (detail is P2PKDetail.Stored && key != null) {
                item("usage") {
                    KeyValueRow(
                        label = "Used count",
                        value = key.usedCount.toString(),
                        onCopy = null,
                        monospaced = false,
                    )
                }
            }
            item("private") {
                PrivateKeyRow(
                    value = privateNsec,
                    revealed = privateRevealed,
                    onToggleReveal = {
                        if (privateRevealed) {
                            privateRevealed = false
                        } else {
                            authenticate("Reveal P2PK private key") {
                                privateRevealed = true
                            }
                        }
                    },
                    onCopy = {
                        privateNsec?.let { nsec ->
                            authenticate("Copy P2PK private key") {
                                clipboard.copyTextWithToast(context, nsec)
                            }
                        }
                    },
                )
            }
            if (detail is P2PKDetail.Stored && key != null) {
                item("remove") {
                    GhostButton(
                        text = "Remove key",
                        onClick = { showRemove = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            item("bottom") {
                Spacer(Modifier.navigationBarsPadding())
            }
        }
    }

    if (showRename && key != null) {
        var name by remember(key.id) { mutableStateOf(key.label) }
        AlertDialog(
            modifier = Modifier.imePadding(),
            onDismissRequest = { showRename = false },
            title = { Text("Rename key") },
            text = {
                CashuTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = "Name",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    settingsManager.setP2PKKeyLabel(key.id, name)
                    showRename = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            },
        )
    }

    if (showRemove && key != null) {
        AlertDialog(
            onDismissRequest = { showRemove = false },
            title = { Text("Remove key?") },
            text = {
                Text(
                    "Ecash locked to this key won't be redeemable without it.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    settingsManager.removeP2PKKey(key.id)
                    showRemove = false
                    onBack()
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRemove = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun P2PKRow(
    title: String,
    subtitle: String,
    publicKey: String,
    leadingIcon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = CashuTheme.spacing.comfortable,
                vertical = CashuTheme.spacing.default,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Icon(
            imageVector = leadingIcon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(CashuTheme.spacing.loose),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = publicKey,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = "Copy",
                modifier = Modifier.size(CashuTheme.spacing.loose),
            )
        }
    }
}

@Composable
private fun KeyValueRow(
    label: String,
    value: String,
    onCopy: (() -> Unit)?,
    monospaced: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = CashuTheme.spacing.comfortable,
                vertical = CashuTheme.spacing.default,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = if (monospaced) {
                    MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
                } else {
                    MaterialTheme.typography.bodyMedium
                },
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        if (onCopy != null) {
            IconButton(onClick = onCopy) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy")
            }
        }
    }
}

@Composable
private fun PrivateKeyRow(
    value: String?,
    revealed: Boolean,
    onToggleReveal: () -> Unit,
    onCopy: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = CashuTheme.spacing.comfortable,
                vertical = CashuTheme.spacing.default,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Icon(Icons.Outlined.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Private key",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = when {
                    value == null -> "-"
                    revealed -> value
                    else -> "••••••••••••"
                },
                style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        IconButton(onClick = onToggleReveal, enabled = value != null) {
            Icon(
                imageVector = if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                contentDescription = if (revealed) "Hide" else "Reveal",
            )
        }
        IconButton(onClick = onCopy, enabled = value != null) {
            Icon(Icons.Outlined.ContentCopy, contentDescription = "Copy private key")
        }
    }
}
