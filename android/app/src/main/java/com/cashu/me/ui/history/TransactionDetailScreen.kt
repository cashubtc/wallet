package com.cashu.me.ui.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.Core.OnchainExplorer
import com.cashu.me.Core.SettingsManager
import com.cashu.me.Core.TransactionDisplay
import com.cashu.me.Core.WalletManager
import com.cashu.me.Models.TransactionKind
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.Models.TransactionType
import com.cashu.me.Models.WalletTransaction
import com.cashu.me.ui.components.AmountText
import com.cashu.me.ui.components.CanvasDivider
import com.cashu.me.ui.components.DetailActionFooter
import com.cashu.me.ui.components.EmptyState
import com.cashu.me.ui.components.InspectorRow
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.components.QrCard
import com.cashu.me.ui.components.ToolbarIcon
import com.cashu.me.ui.components.shareText
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.theme.withMonoDigits

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    walletManager: WalletManager,
    settingsManager: SettingsManager,
    transactionId: String,
    onClose: () -> Unit,
    onClaimReceiveToken: ((String) -> Unit)? = null,
    snackbarHostState: SnackbarHostState? = null,
) {
    val walletState by walletManager.state.collectAsState()
    val settings by settingsManager.state.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val formatter = remember { AmountFormatter() }

    val transaction = walletState.transactions.firstOrNull { it.id == transactionId }
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    val showsQr = transaction?.let { TransactionDisplay.showsQr(it) } == true
    val qrContent = transaction?.let { TransactionDisplay.qrContent(it) }
    val copyableContent = transaction?.let { TransactionDisplay.copyableContent(it) }
    val title = transaction?.let { TransactionDisplay.title(it) } ?: ""

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        ToolbarIcon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    // Share rides the top bar only while the artifact is live.
                    if (showsQr && qrContent != null) {
                        IconButton(onClick = {
                            context.shareText(qrContent, subject = title)
                        }) {
                            ToolbarIcon(Icons.Outlined.IosShare, contentDescription = "Share")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        if (transaction == null) {
            EmptyState(
                icon = Icons.Outlined.Close,
                title = "Transaction not found",
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        val explorerUrl = remember(transaction) { transaction.explorerUrl() }
        val pendingReceiveToken = transaction.token?.takeIf {
            transaction.isPendingToken &&
                transaction.type == TransactionType.Incoming &&
                transaction.status == TransactionStatus.Pending
        }
        val hasPrimaryAction =
            (pendingReceiveToken != null && onClaimReceiveToken != null) || copyableContent != null

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = CashuTheme.spacing.comfortable),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.comfortable),
            ) {
                Spacer(Modifier.height(CashuTheme.spacing.snug))
                // Hero state slot: live request → QR; completed → 64dp green check;
                // failed → 64dp red X; pending with no QR → no glyph. State detail
                // lives in the monochrome Status row below.
                when {
                    showsQr && qrContent != null -> QrCard(
                        content = qrContent,
                        staticOnly = transaction.kind != TransactionKind.Ecash,
                        shareSubject = title,
                        snackbarHostState = snackbarHostState,
                    )
                    transaction.status == TransactionStatus.Completed -> Icon(
                        imageVector = Icons.Filled.CheckCircle,
                        contentDescription = "Completed",
                        tint = CashuTheme.colors.received,
                        modifier = Modifier
                            .padding(top = CashuTheme.spacing.comfortable)
                            .size(COMPLETED_HERO_GLYPH_SIZE),
                    )
                    transaction.status == TransactionStatus.Failed -> Icon(
                        imageVector = Icons.Filled.Cancel,
                        contentDescription = "Failed",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(FAILED_HERO_GLYPH_SIZE),
                    )
                    else -> Unit
                }
                HeroAmount(
                    transaction = transaction,
                    formatter = formatter,
                    useBitcoinSymbol = settings.useBitcoinSymbol,
                    compact = showsQr,
                )
                Column(modifier = Modifier.fillMaxWidth()) {
                    val fields = remember(transaction) { TransactionDisplay.detailFields(transaction) }
                    fields.forEachIndexed { index, field ->
                        InspectorRow(
                            label = field.label,
                            value = field.value,
                            valueMonospaced = field.value.length > 24 ||
                                field.label in MonospacedLabels,
                        )
                        if (index != fields.lastIndex) CanvasDivider(leadingInset = 16.dp)
                    }
                }
                Spacer(Modifier.height(CashuTheme.spacing.snug))
            }

            if (explorerUrl != null || hasPrimaryAction) {
                DetailActionFooter {
                    if (explorerUrl != null) {
                        ExplorerLinkRow(url = explorerUrl, onOpen = { context.openInBrowser(it) })
                        if (hasPrimaryAction) Spacer(Modifier.height(CashuTheme.spacing.snug))
                    }
                    if (pendingReceiveToken != null && onClaimReceiveToken != null) {
                        PrimaryButton(
                            text = "Receive",
                            onClick = { onClaimReceiveToken(pendingReceiveToken) },
                        )
                    } else if (copyableContent != null) {
                        PrimaryButton(
                            text = if (copied) "Copied" else "Copy",
                            onClick = {
                                clipboard.setText(AnnotatedString(copyableContent))
                                copied = true
                            },
                        )
                    }
                }
            }
        }
    }
}

// Inline link glyph next to the "View in block explorer" label.
private val EXPLORER_GLYPH_SIZE = 18.dp

// Historical success gets a more generous 96dp hero; failure stays restrained.
private val COMPLETED_HERO_GLYPH_SIZE = 96.dp
private val FAILED_HERO_GLYPH_SIZE = 64.dp

private val MonospacedLabels = setOf("Request", "Address", "Payment Proof", "Transaction ID", "Quote ID", "Mint")

// Crisp primary amount hero — direction already lives in the screen title, so
// the historical detail keeps the amount itself quiet and unsigned like iOS.
@Composable
private fun HeroAmount(
    transaction: WalletTransaction,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
    compact: Boolean,
) {
    val formatted = formatter.formatWalletSats(transaction.amount, useBitcoinSymbol)
    AmountText(
        text = formatted,
        style = (if (compact) MaterialTheme.typography.headlineLarge else MaterialTheme.typography.displayMedium)
            .copy(fontWeight = FontWeight.Bold)
            .withMonoDigits(),
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.padding(vertical = 5.dp),
    )
}

@Composable
private fun ExplorerLinkRow(url: String, onOpen: (String) -> Unit) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = CashuTheme.spacing.comfortable,
                vertical = CashuTheme.spacing.snug,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
    ) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(EXPLORER_GLYPH_SIZE),
        )
        Text(
            text = "View in block explorer",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onOpen(url) }) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.OpenInNew,
                contentDescription = "Open",
            )
        }
    }
}

private fun WalletTransaction.explorerUrl(): String? {
    if (kind != TransactionKind.Onchain) return null
    return preimage?.let {
        OnchainExplorer.transactionWebUrl(txid = it, address = invoice, mintUrl = mintUrl)
    } ?: invoice?.let {
        OnchainExplorer.addressWebUrl(address = it, mintUrl = mintUrl)
    }
}

private fun Context.openInBrowser(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    startActivity(intent)
}
