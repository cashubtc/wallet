package com.cashu.me.ui.mints

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ContentCopy
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.cashu.me.Core.Protocols.CurrencyAmount
import com.cashu.me.Core.Protocols.CurrencyRegistry
import com.cashu.me.Core.WalletManager
import com.cashu.me.Core.shortenMintUrl
import com.cashu.me.Models.MintInfo
import com.cashu.me.ui.components.AmountText
import com.cashu.me.ui.components.CanvasDivider
import com.cashu.me.ui.components.DestructiveTextButton
import com.cashu.me.ui.components.GhostButton
import com.cashu.me.ui.components.IconSwap
import com.cashu.me.ui.components.InspectorRow
import com.cashu.me.ui.components.MintAvatar
import com.cashu.me.ui.components.MintMethodChips
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.components.SectionHeader
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.withMonoDigits

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MintDetailScreen(
    walletManager: WalletManager,
    mintUrl: String,
    onClose: () -> Unit,
) {
    val walletState by walletManager.state.collectAsState()
    val mint = walletState.mints.firstOrNull { it.url == mintUrl }
    val isActive = walletState.activeMint?.url == mintUrl
    val clipboard = LocalClipboardManager.current
    var confirmingRemove by remember { mutableStateOf(false) }

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

            if (!mint.description.isNullOrBlank()) {
                SectionHeader("About")
                // iOS MintDetailView "Read more": long descriptions clamp to
                // three lines and the reflow animates (easeInOut 0.2 → spring).
                var aboutExpanded by remember(mint.url) { mutableStateOf(false) }
                // Sticky: once the collapsed layout reports overflow, keep the
                // toggle even while expanded (no overflow in that state).
                var aboutOverflows by remember(mint.url) { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(spring(stiffness = Spring.StiffnessMediumLow)),
                ) {
                    Text(
                        text = mint.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = if (aboutExpanded) Int.MAX_VALUE else ABOUT_COLLAPSED_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { aboutOverflows = aboutOverflows || it.hasVisualOverflow },
                        modifier = Modifier.padding(horizontal = CashuTheme.spacing.comfortable),
                    )
                    if (aboutOverflows) {
                        GhostButton(
                            text = if (aboutExpanded) "Show less" else "Read more",
                            onClick = { aboutExpanded = !aboutExpanded },
                            modifier = Modifier.padding(horizontal = CashuTheme.spacing.default),
                        )
                    }
                }
            }

            SectionHeader("Identity")
            Column(modifier = Modifier.fillMaxWidth()) {
                InspectorRow(
                    label = "URL",
                    value = shortenMintUrl(mint.url),
                    valueMonospaced = true,
                )
                CanvasDivider(leadingInset = 16.dp)
                // Copy-confirm morph (iOS: doc.on.doc ↔ checkmark via
                // .contentTransition(.symbolEffect(.replace)) + snappy 0.18).
                var copiedUrl by remember(mint.url) { mutableStateOf(false) }
                LaunchedEffect(copiedUrl) {
                    if (copiedUrl) {
                        delay(COPY_CONFIRM_RESET_MS)
                        copiedUrl = false
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            clipboard.setText(AnnotatedString(mint.url))
                            copiedUrl = true
                        }
                        .padding(
                            horizontal = CashuTheme.spacing.comfortable,
                            vertical = CashuTheme.spacing.default,
                        ),
                ) {
                    IconSwap(
                        icon = if (copiedUrl) Icons.Outlined.Check else Icons.Outlined.ContentCopy,
                        contentDescription = null,
                        tint = if (copiedUrl) CashuTheme.colors.received
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(COPY_ROW_ICON_SIZE),
                    )
                    Text(
                        text = if (copiedUrl) "Copied" else "Copy full URL",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            SectionHeader("Payment methods")
            Column(modifier = Modifier.fillMaxWidth()) {
                InspectorRow(
                    label = "Receive",
                    value = mint.supportedMintMethods.joinToString { it.displayName }.ifBlank { "None" },
                )
                CanvasDivider(leadingInset = 16.dp)
                InspectorRow(
                    label = "Send",
                    value = mint.supportedMeltMethods.joinToString { it.displayName }.ifBlank { "None" },
                )
                mint.onchainMintConfirmations?.let {
                    CanvasDivider(leadingInset = 16.dp)
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
            LaunchedEffect(mint.url, nonSatUnits) {
                nonSatUnits.forEach { unit ->
                    walletManager.unitBalance(mint.url, unit)?.let { balance ->
                        unitBalances = unitBalances + (unit to balance)
                    }
                }
            }
            nonSatUnits.forEach { unit ->
                CanvasDivider(leadingInset = 16.dp)
                InspectorRow(
                    label = "Balance (${unit.uppercase()})",
                    value = unitBalances[unit]?.let {
                        CurrencyAmount(it, CurrencyRegistry.currencyForMintUnit(unit)).formatted()
                    } ?: "…",
                    valueMonospaced = true,
                )
            }
            CanvasDivider(leadingInset = 16.dp)
            InspectorRow(
                label = "Units",
                value = mint.units.joinToString(", ").ifBlank { "sat" },
            )

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
            Spacer(Modifier.height(CashuTheme.spacing.section))
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
                MintAvatar(mint = mint, size = 72.dp)
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

// iOS parity: collapsed "About" clamp and the copy-confirm reset delay.
private const val ABOUT_COLLAPSED_LINES = 3
private const val COPY_CONFIRM_RESET_MS = 2_000L
