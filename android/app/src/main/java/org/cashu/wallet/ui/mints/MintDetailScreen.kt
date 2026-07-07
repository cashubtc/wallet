package org.cashu.wallet.ui.mints

import android.content.Intent
import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.cashu.wallet.Core.externalTargetWithHttpsFallback
import org.cashu.wallet.Core.mintCapabilitySummary
import org.cashu.wallet.Core.mintContactTarget
import org.cashu.wallet.Core.mintPaymentMethodSettingLabel
import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Core.shortenMintUrl
import org.cashu.wallet.Models.MintContactInfo
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.MintPaymentMethodSetting
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.DestructiveTextButton
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.InspectorRow
import org.cashu.wallet.ui.components.MintAvatar
import org.cashu.wallet.ui.components.MintMethodChips
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.SectionHeader
import org.cashu.wallet.ui.components.copyTextWithToast
import org.cashu.wallet.ui.components.shareText
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

private enum class MintConnectionState { Checking, Online, Offline }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MintDetailScreen(
    walletManager: WalletManager,
    mintUrl: String,
    onClose: () -> Unit,
) {
    val walletState by walletManager.state.collectAsStateWithLifecycle()
    val mint = walletState.mints.firstOrNull { it.url == mintUrl }
    val isActive = walletState.activeMint?.url == mintUrl
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var confirmingRemove by remember { mutableStateOf(false) }
    var connectionState by remember(mintUrl) { mutableStateOf(MintConnectionState.Checking) }
    var showFullDescription by remember(mintUrl) { mutableStateOf(false) }
    var copiedUrl by remember { mutableStateOf(false) }

    LaunchedEffect(mintUrl, mint?.url) {
        val current = mint ?: return@LaunchedEffect
        connectionState = MintConnectionState.Checking
        val before = current.lastUpdatedEpochMillis
        val refreshed = runCatching { walletManager.refreshMintInfo(current) }.getOrNull()
        connectionState = if (refreshed != null && refreshed.lastUpdatedEpochMillis > before) {
            MintConnectionState.Online
        } else {
            MintConnectionState.Offline
        }
    }

    LaunchedEffect(copiedUrl) {
        if (copiedUrl) {
            delay(1_500)
            copiedUrl = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(mint?.name ?: "Mint", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { mint?.let { context.shareText(it.url, subject = "Cashu mint") } },
                        enabled = mint != null,
                    ) {
                        Icon(Icons.Outlined.IosShare, contentDescription = "Share")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (mint == null) {
            EmptyMintFallback(padding = padding, onClose = onClose)
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            HeaderBlock(mint = mint, isActive = isActive)

            SectionHeader("Connection")
            InspectorRow(
                label = "Status",
                value = when (connectionState) {
                    MintConnectionState.Checking -> "Checking"
                    MintConnectionState.Online -> "Online"
                    MintConnectionState.Offline -> "Offline"
                },
            )
            CanvasDivider(leadingInset = 16)
            InspectorRow(
                label = "Last updated",
                value = formatTimestamp(mint.lastUpdatedEpochMillis),
            )

            if (!mint.description.isNullOrBlank() || !mint.descriptionLong.isNullOrBlank() || !mint.motd.isNullOrBlank()) {
                SectionHeader("About")
                mint.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = CashuTheme.spacing.comfortable),
                    )
                }
                mint.descriptionLong?.takeIf { it.isNotBlank() }?.let { longDescription ->
                    val clipped = longDescription.length > 280 && !showFullDescription
                    Text(
                        text = if (clipped) longDescription.take(280).trimEnd() + "..." else longDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = CashuTheme.spacing.comfortable),
                    )
                    if (longDescription.length > 280) {
                        GhostButton(
                            text = if (showFullDescription) "Show less" else "Read more",
                            onClick = { showFullDescription = !showFullDescription },
                            modifier = Modifier.padding(horizontal = CashuTheme.spacing.comfortable),
                        )
                    }
                }
                mint.motd?.takeIf { it.isNotBlank() }?.let {
                    InlineNotice(
                        text = it,
                        modifier = Modifier.padding(horizontal = CashuTheme.spacing.comfortable),
                    )
                }
            }

            SectionHeader("Identity")
            Column(modifier = Modifier.fillMaxWidth()) {
                InspectorRow(
                    label = "URL",
                    value = shortenMintUrl(mint.url),
                    valueMonospaced = true,
                )
                CanvasDivider(leadingInset = 16)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.copyTextWithToast(context, mint.url)
                            copiedUrl = true
                        }
                        .padding(
                            horizontal = CashuTheme.spacing.comfortable,
                            vertical = CashuTheme.spacing.default,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(COPY_ROW_ICON_SIZE),
                    )
                    Text(
                        text = "Copy full URL",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                if (copiedUrl) {
                    InlineNotice(
                        text = "Mint URL copied.",
                        modifier = Modifier.padding(horizontal = CashuTheme.spacing.comfortable),
                    )
                }
                mint.pubkey?.takeIf { it.isNotBlank() }?.let {
                    CanvasDivider(leadingInset = 16)
                    InspectorRow(
                        label = "Public key",
                        value = it,
                        valueMonospaced = true,
                    )
                }
                if (mint.urls.isNotEmpty()) {
                    CanvasDivider(leadingInset = 16)
                    InspectorRow(
                        label = "Endpoints",
                        value = mint.urls.joinToString(),
                    )
                }
            }

            SectionHeader("Capabilities")
            InspectorRow(
                label = "Summary",
                value = mintCapabilitySummary(mint),
            )
            CanvasDivider(leadingInset = 16)
            InspectorRow(
                label = "Units",
                value = mint.units.joinToString(", ").ifBlank { "sat" },
            )

            SectionHeader("NUT support")
            NutRows(mint = mint)

            SectionHeader("Payment methods")
            Column(modifier = Modifier.fillMaxWidth()) {
                PaymentMethodRows(
                    label = "Receive",
                    settings = mint.mintMethodSettings,
                    fallback = mint.supportedMintMethods.map {
                        MintPaymentMethodSetting(method = it, unit = "sat")
                    },
                )
                CanvasDivider(leadingInset = 16)
                PaymentMethodRows(
                    label = "Send",
                    settings = mint.meltMethodSettings,
                    fallback = mint.supportedMeltMethods.map {
                        MintPaymentMethodSetting(method = it, unit = "sat")
                    },
                )
                mint.onchainMintConfirmations?.let {
                    CanvasDivider(leadingInset = 16)
                    InspectorRow(
                        label = "On-chain confirmations",
                        value = it.toString(),
                        valueMonospaced = true,
                    )
                }
            }

            SectionHeader("Wallet")
            InspectorRow(
                label = "Balance on this mint",
                value = "${mint.balance} sat",
                valueMonospaced = true,
            )
            // Per-unit balances for non-sat units, loaded on demand.
            val nonSatUnits = remember(mint.units) {
                mint.units.filter { !it.equals("sat", ignoreCase = true) }.sorted()
            }
            var unitBalances by remember(mint.url) { mutableStateOf<Map<String, Long>>(emptyMap()) }
            var loadedUnits by remember(mint.url) { mutableStateOf<Set<String>>(emptySet()) }
            LaunchedEffect(mint.url, nonSatUnits) {
                nonSatUnits.forEach { unit ->
                    walletManager.unitBalanceIfExists(mint.url, unit)?.let { balance ->
                        unitBalances = unitBalances + (unit to balance)
                    }
                    loadedUnits = loadedUnits + unit
                }
            }
            nonSatUnits.forEach { unit ->
                CanvasDivider(leadingInset = 16)
                InspectorRow(
                    label = "Balance (${unit.uppercase()})",
                    value = when {
                        unit in unitBalances -> CurrencyAmount(
                            unitBalances.getValue(unit),
                            CurrencyRegistry.currencyForMintUnit(unit),
                        ).formatted()
                        unit in loadedUnits -> "Not created"
                        else -> "..."
                    },
                    valueMonospaced = true,
                )
            }

            if (mint.contacts.isNotEmpty() || !mint.softwareName.isNullOrBlank() || !mint.tosUrl.isNullOrBlank()) {
                SectionHeader("Information")
                if (!mint.softwareName.isNullOrBlank() || !mint.softwareVersion.isNullOrBlank()) {
                    InspectorRow(
                        label = "Software",
                        value = listOfNotNull(mint.softwareName, mint.softwareVersion).joinToString(" "),
                    )
                }
                mint.tosUrl?.takeIf { it.isNotBlank() }?.let { tos ->
                    CanvasDivider(leadingInset = 16)
                    ClickableInfoRow(
                        label = "Terms",
                        value = tos,
                        onClick = { openExternalOrCopy(context, clipboard, tos) },
                    )
                }
                mint.contacts.forEach { contact ->
                    CanvasDivider(leadingInset = 16)
                    ClickableInfoRow(
                        label = contact.method.replaceFirstChar { it.uppercase() },
                        value = contact.info,
                        onClick = { openContact(context, clipboard, contact) },
                    )
                }
            }

            Spacer(Modifier.height(CashuTheme.spacing.comfortable))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = CashuTheme.spacing.comfortable),
                verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
            ) {
                PrimaryButton(
                    text = if (isActive) "Active mint" else "Set as active mint",
                    onClick = {
                        if (!isActive) walletManager.launch { walletManager.setActiveMint(mint) }
                    },
                    enabled = !isActive,
                )
                DestructiveTextButton(
                    text = "Remove mint",
                    onClick = { confirmingRemove = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(CashuTheme.spacing.section).navigationBarsPadding())
        }
    }

    if (confirmingRemove) {
        AlertDialog(
            onDismissRequest = { confirmingRemove = false },
            title = { Text("Remove ${mint?.name ?: "mint"}?") },
            text = {
                Text(
                    "Any unspent ecash on this mint will need to be restored from your seed phrase.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingRemove = false
                    mint?.let { walletManager.launch { walletManager.removeMint(it) } }
                    onClose()
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingRemove = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun HeaderBlock(mint: MintInfo, isActive: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = CashuTheme.spacing.comfortable,
                vertical = CashuTheme.spacing.comfortable,
            ),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.comfortable),
        ) {
            Box {
                MintAvatar(mint = mint, size = 72)
                if (isActive) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(CashuTheme.spacing.comfortable)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Check,
                            contentDescription = "Active",
                            tint = CashuTheme.colors.received,
                            modifier = Modifier.size(CashuTheme.spacing.default),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = mint.name,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = shortenMintUrl(mint.url),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                AmountText(
                    text = "${mint.balance} sat",
                    style = MaterialTheme.typography.bodyMedium.withMonoDigits(),
                )
            }
        }
        MintMethodChips(mint = mint)
    }
}

@Composable
private fun NutRows(mint: MintInfo) {
    val rows = listOf(
        "NUT-04" to ("Mint quotes" to mint.nuts.nut04),
        "NUT-05" to ("Melt quotes" to mint.nuts.nut05),
        "NUT-07" to ("Token state check" to mint.nuts.nut07),
        "NUT-08" to ("Lightning fee return" to mint.nuts.nut08),
        "NUT-09" to ("Restore signatures" to mint.nuts.nut09),
        "NUT-10" to ("Spending conditions" to mint.nuts.nut10),
        "NUT-11" to ("P2PK locking" to mint.nuts.nut11),
        "NUT-12" to ("DLEQ proofs" to mint.nuts.nut12),
        "NUT-14" to ("HTLC" to mint.nuts.nut14),
        "NUT-20" to ("WebSockets" to mint.nuts.nut20),
    )
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { index, row ->
            val (nut, detail) = row
            val (label, supported) = detail
            if (index > 0) CanvasDivider(leadingInset = 16)
            InspectorRow(
                label = nut,
                value = if (supported) "$label supported" else "Not supported",
            )
        }
    }
}

@Composable
private fun PaymentMethodRows(
    label: String,
    settings: List<MintPaymentMethodSetting>,
    fallback: List<MintPaymentMethodSetting>,
) {
    val rows = settings.ifEmpty { fallback }
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.ifEmpty { listOf(MintPaymentMethodSetting(method = org.cashu.wallet.Models.PaymentMethodKind.Bolt11, unit = "sat")) }
            .forEachIndexed { index, setting ->
                if (index > 0) CanvasDivider(leadingInset = 16)
                InspectorRow(
                    label = "$label ${setting.method.displayName}",
                    value = mintPaymentMethodSettingLabel(setting),
                )
            }
    }
}

@Composable
private fun ClickableInfoRow(
    label: String,
    value: String,
    onClick: () -> Unit,
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
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
            )
        }
        Icon(
            imageVector = Icons.Outlined.ContentCopy,
            contentDescription = "Open or copy",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(COPY_ROW_ICON_SIZE),
        )
    }
}

private fun capabilitySummary(mint: MintInfo): String {
    val capabilities = buildList {
        if (mint.supportedMintMethods.any { it != org.cashu.wallet.Models.PaymentMethodKind.Onchain } ||
            mint.supportedMeltMethods.any { it != org.cashu.wallet.Models.PaymentMethodKind.Onchain }
        ) {
            add("Lightning")
        }
        if (mint.supportedMintMethods.contains(org.cashu.wallet.Models.PaymentMethodKind.Onchain) ||
            mint.supportedMeltMethods.contains(org.cashu.wallet.Models.PaymentMethodKind.Onchain)
        ) {
            add("On-chain")
        }
        if (mint.nuts.nut10 || mint.nuts.nut11) add("Locked ecash")
        if (mint.nuts.nut14) add("HTLC")
        if (mint.nuts.nut20) add("WebSockets")
    }
    return capabilities.distinct().joinToString().ifBlank { "Basic ecash" }
}

private fun openContact(context: Context, clipboard: ClipboardManager, contact: MintContactInfo) {
    val info = contact.info.trim()
    val target = mintContactTarget(contact)
    if (target != null && runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }.isSuccess
    ) {
        return
    }
    clipboard.copyTextWithToast(context, info)
}

private fun openExternalOrCopy(context: Context, clipboard: ClipboardManager, value: String) {
    val target = externalTargetWithHttpsFallback(value)
    if (runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)))
        }.isSuccess
    ) {
        return
    }
    clipboard.copyTextWithToast(context, value)
}

private fun formatTimestamp(epochMillis: Long): String {
    val seconds = ((System.currentTimeMillis() - epochMillis) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "Just now"
        seconds < 3_600 -> "${seconds / 60} min ago"
        seconds < 86_400 -> "${seconds / 3_600} hr ago"
        else -> "${seconds / 86_400} days ago"
    }
}

@Composable
private fun EmptyMintFallback(padding: PaddingValues, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(CashuTheme.spacing.section),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "Mint not found",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(CashuTheme.spacing.comfortable))
        GhostButton(text = "Back to mints", onClick = onClose)
    }
}

// Inline copy-row glyph (smaller than the body 20dp).
private val COPY_ROW_ICON_SIZE = 18.dp
