package com.cashu.me.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.cashu.me.Core.AppLockManager
import com.cashu.me.Core.Services.ContactlessErrors
import com.cashu.me.Core.Services.ContactlessLimits
import com.cashu.me.Core.Services.ContactlessSpendPolicy
import com.cashu.me.ui.components.InlineNotice
import com.cashu.me.ui.components.NoticeSeverity
import com.cashu.me.ui.components.ToolbarIcon
import com.cashu.me.ui.security.findFragmentActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactlessSettingsScreen(appLockManager: AppLockManager, onClose: () -> Unit) {
    val context = LocalContext.current
    val policy = remember { ContactlessSpendPolicy.get(context) }
    val scope = rememberCoroutineScope()
    var perTap by remember { mutableStateOf("1000") }
    var daily by remember { mutableStateOf("10000") }
    var ready by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val limits = perTap.toLongOrNull()?.let { tap -> daily.toLongOrNull()?.let { ContactlessLimits(tap, it) } }
    LaunchedEffect(Unit) {
        runCatching { policy.state() }.onSuccess {
            perTap = it.limits.perTap.toString(); daily = it.limits.daily.toString(); ready = true
        }.onFailure { error = it.message }
    }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Contactless") }, navigationIcon = {
            IconButton(onClick = onClose) {
                ToolbarIcon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
            }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(value = perTap, onValueChange = { perTap = it; saved = false },
                label = { Text("Per-tap limit (sats)") }, singleLine = true, enabled = !saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = daily, onValueChange = { daily = it; saved = false },
                label = { Text("Daily limit (sats)") }, singleLine = true, enabled = !saving,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Text("Payments above the per-tap limit require device authentication. Set it to 0 to authenticate every tap. The daily limit stops further contactless payments.")
            Button(enabled = ready && !saving && limits?.isValid == true, onClick = {
                val selected = limits ?: return@Button
                saving = true
                scope.launch {
                    try {
                        check(appLockManager.authenticateContactless(context.findFragmentActivity(), "Change contactless limits")) {
                            ContactlessErrors.Authentication
                        }
                        policy.saveLimits(selected)
                        saved = true; error = null
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message }
                    finally { saving = false }
                }
            }) { Text(if (saved) "Saved" else "Save limits") }
            error?.let { InlineNotice(text = it, severity = NoticeSeverity.Error) }
            Text("Limits include fees. Interrupted payments also count toward the daily limit. The daily limit applies across wallets on this device and resets at midnight UTC.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
