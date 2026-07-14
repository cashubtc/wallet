package com.cashu.me.ui.settings

import androidx.biometric.BiometricManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import com.cashu.me.Core.AppLockManager
import com.cashu.me.Core.SettingsManager
import com.cashu.me.ui.components.ToggleRow
import com.cashu.me.ui.components.ToolbarIcon
import com.cashu.me.ui.theme.CashuTheme

/**
 * Settings → Backup & Security → App Lock (iOS SecuritySettingsSection).
 * Enabling first confirms with a live auth and reverts to off on failure.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockScreen(
    settingsManager: SettingsManager,
    appLockManager: AppLockManager,
    onClose: () -> Unit,
) {
    val settings by settingsManager.state.collectAsState()
    val lockState by appLockManager.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val biometryNoun = remember(context) { biometryLabel(context) }
    var authError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        appLockManager.refreshAvailability()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Lock", style = MaterialTheme.typography.titleMedium) },
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
        ) {
            ToggleRow(
                title = "Require $biometryNoun",
                subtitle = "Ask for $biometryNoun when opening the wallet.",
                checked = settings.appLockEnabled,
                onCheckedChange = { enable ->
                    if (!enable) {
                        settingsManager.setAppLockEnabled(false)
                        appLockManager.setEnabled(false)
                        return@ToggleRow
                    }
                    scope.launch {
                        val activity = context.findFragmentActivity()
                        val ok = appLockManager.authenticate(
                            activity = activity,
                            reason = "Confirm to enable App Lock",
                        )
                        if (ok) {
                            settingsManager.setAppLockEnabled(true)
                            appLockManager.setEnabled(true)
                        } else {
                            authError = "Authentication failed. App Lock was not enabled."
                        }
                    }
                },
                enabled = lockState.isAvailable || settings.appLockEnabled,
            )

            Text(
                text = if (!lockState.isAvailable) {
                    "Set a device screen lock in system Settings to use App Lock."
                } else {
                    "Your seed phrase always requires authentication to reveal, even when App Lock is off."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = CashuTheme.spacing.comfortable,
                        vertical = CashuTheme.spacing.comfortable,
                    ),
            )
        }
    }

    authError?.let { message ->
        AlertDialog(
            onDismissRequest = { authError = null },
            title = { Text("Couldn't Enable App Lock") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { authError = null }) { Text("OK") }
            },
        )
    }
}

private fun biometryLabel(context: android.content.Context): String {
    val manager = BiometricManager.from(context)
    val bio = manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
    return when (bio) {
        BiometricManager.BIOMETRIC_SUCCESS -> "biometrics"
        else -> "your screen lock"
    }
}

private fun android.content.Context.findFragmentActivity(): FragmentActivity? {
    var current: android.content.Context? = this
    while (current is android.content.ContextWrapper) {
        if (current is FragmentActivity) return current
        current = current.baseContext
    }
    return null
}
