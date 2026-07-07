package org.cashu.wallet.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Restore
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.ui.components.NavRow

/**
 * Settings → Backup & Restore (iOS BackupSettingsSection): seed-phrase backup
 * and the restore wizard behind one root row.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    walletManager: WalletManager,
    onOpenBackup: () -> Unit,
    onClose: () -> Unit,
) {
    var confirmRestore by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Backup & Restore", style = MaterialTheme.typography.titleMedium) },
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
                .padding(padding),
        ) {
            NavRow(
                title = "Backup seed phrase",
                subtitle = "View and copy your 12 recovery words.",
                leadingIcon = Icons.Outlined.VpnKey,
                onClick = onOpenBackup,
            )
            NavRow(
                title = "Restore",
                subtitle = "Restore a wallet and recover ecash from mints.",
                leadingIcon = Icons.Outlined.Restore,
                onClick = { confirmRestore = true },
            )
        }
    }

    if (confirmRestore) {
        AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text("Open Restore Wizard") },
            text = {
                Text(
                    "This will open the restore flow used during onboarding.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    walletManager.reopenOnboarding()
                }) { Text("Open") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRestore = false }) { Text("Cancel") }
            },
        )
    }
}
