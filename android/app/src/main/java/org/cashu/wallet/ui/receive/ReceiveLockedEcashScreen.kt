package org.cashu.wallet.ui.receive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import org.cashu.wallet.Core.LockedReceiveRequest
import org.cashu.wallet.Core.NostrService
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.ui.components.EmptyState
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.QrCard
import org.cashu.wallet.ui.components.shareText
import org.cashu.wallet.ui.theme.CashuTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiveLockedEcashScreen(
    settingsManager: SettingsManager,
    nostrService: NostrService,
    onClose: () -> Unit,
) {
    val settings by settingsManager.state.collectAsState()
    val nostrState by nostrService.state.collectAsState()
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var nonce by remember { mutableIntStateOf(0) }
    val encoded = remember(nostrState.publicKeyHex, settings.nostrRelays, nonce) {
        LockedReceiveRequest.build(settingsManager, nostrService)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Receive Locked Ecash", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { encoded?.let { context.shareText(it, subject = "Cashu locked ecash request") } },
                        enabled = encoded != null,
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CashuTheme.spacing.comfortable),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
        ) {
            Spacer(Modifier.height(CashuTheme.spacing.snug))
            if (encoded == null) {
                EmptyState(
                    icon = Icons.Outlined.Lock,
                    title = "Couldn't create a request",
                    supporting = "This needs your wallet set up with a Nostr relay. Check Settings > Nostr, then try again.",
                    actionLabel = "Try again",
                    onAction = { nonce++ },
                )
            } else {
                QrCard(
                    content = encoded,
                    modifier = Modifier.fillMaxWidth(),
                    showQrControls = true,
                    shareSubject = "Cashu locked ecash request",
                )
                Text(
                    text = "Anyone who pays this request sends ecash locked to your wallet key.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                InlineNotice(text = "Share this request with people who should send ecash only this wallet can claim.")
                PrimaryButton(
                    text = "Copy request",
                    onClick = { clipboard.setText(AnnotatedString(encoded)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                GhostButton(
                    text = "Regenerate",
                    onClick = { nonce++ },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = encoded,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
}
