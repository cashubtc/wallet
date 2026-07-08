package org.cashu.wallet.ui.history

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.OnchainExplorer
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.TransactionDisplay
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Models.TransactionKind
import org.cashu.wallet.Models.TransactionStatus
import org.cashu.wallet.Models.TransactionType
import org.cashu.wallet.Models.WalletTransaction
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.EmptyState
import org.cashu.wallet.ui.components.InspectorRow
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.QrCard
import org.cashu.wallet.ui.components.SectionHeader
import org.cashu.wallet.ui.components.shareText
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDetailScreen(
    walletManager: WalletManager,
    settingsManager: SettingsManager,
    transactionId: String,
    onClose: () -> Unit,
    onClaimReceiveToken: ((String) -> Unit)? = null,
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
            TopAppBar(
                title = { Text(title, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    // Share rides the top bar only while the artifact is live.
                    if (showsQr && qrContent != null) {
                        IconButton(onClick = {
                            context.shareText(qrContent, subject = title)
                        }) {
                            Icon(Icons.Outlined.IosShare, contentDescription = "Share")
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

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState()),
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
                )
                transaction.status == TransactionStatus.Completed -> Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = "Completed",
                    tint = CashuTheme.colors.received,
                    modifier = Modifier.size(HERO_GLYPH_SIZE),
                )
                transaction.status == TransactionStatus.Failed -> Icon(
                    imageVector = Icons.Filled.Cancel,
                    contentDescription = "Failed",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(HERO_GLYPH_SIZE),
                )
                else -> Unit
            }
            HeroAmount(
                transaction = transaction,
                formatter = formatter,
                useBitcoinSymbol = settings.useBitcoinSymbol,
            )
            SectionHeader("Details")
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

            val explorerUrl = remember(transaction) { transaction.explorerUrl() }
            if (explorerUrl != null) {
                Spacer(Modifier.height(CashuTheme.spacing.snug))
                ExplorerLinkRow(url = explorerUrl, onOpen = { context.openInBrowser(it) })
            }

            // A saved "Receive later" token is still claimable: surface the
            // Receive CTA that opens the full-screen claim page (iOS: Home/
            // History pending rows present ReceiveTokenDetailView).
            val pendingReceiveToken = transaction.token?.takeIf {
                transaction.isPendingToken &&
                    transaction.type == TransactionType.Incoming &&
                    transaction.status == TransactionStatus.Pending
            }
            if (pendingReceiveToken != null && onClaimReceiveToken != null) {
                Spacer(Modifier.height(CashuTheme.spacing.snug))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CashuTheme.spacing.comfortable),
                ) {
                    PrimaryButton(
                        text = "Receive",
                        onClick = { onClaimReceiveToken(pendingReceiveToken) },
                    )
                }
            }

            if (copyableContent != null) {
                Spacer(Modifier.height(CashuTheme.spacing.snug))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = CashuTheme.spacing.comfortable),
                ) {
                    PrimaryButton(
                        text = if (copied) "Copied" else "Copy ${TransactionDisplay.qrLabel(transaction).lowercase()}",
                        onClick = {
                            clipboard.setText(AnnotatedString(copyableContent))
                            copied = true
                        },
                    )
                }
            }
            Spacer(Modifier.height(CashuTheme.spacing.section))
        }
    }
}

// Inline link glyph next to the "View in block explorer" label.
private val EXPLORER_GLYPH_SIZE = 18.dp

// 64dp terminal hero glyph, matching PaymentStatusScreen.
private val HERO_GLYPH_SIZE = 64.dp

private val MonospacedLabels = setOf("Request", "Address", "Payment Proof", "Transaction ID", "Quote ID", "Mint")

// Crisp primary amount hero — the hero glyph above carries state colour; the
// +/− sign stays a settled-ledger signal (pending renders bare).
@Composable
private fun HeroAmount(
    transaction: WalletTransaction,
    formatter: AmountFormatter,
    useBitcoinSymbol: Boolean,
) {
    val formatted = formatter.formatWalletSats(transaction.amount, useBitcoinSymbol)
    val text = if (transaction.status == TransactionStatus.Pending) {
        formatted
    } else {
        "${if (transaction.type == TransactionType.Incoming) "+" else "−"}$formatted"
    }
    AmountText(
        text = text,
        style = MaterialTheme.typography.displayMedium.withMonoDigits(),
        color = MaterialTheme.colorScheme.onSurface,
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
