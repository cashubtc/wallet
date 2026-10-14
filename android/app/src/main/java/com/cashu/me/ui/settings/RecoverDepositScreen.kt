package com.cashu.me.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.cashu.me.Core.DepositRecoveryRequest
import com.cashu.me.Core.DepositRecoveryResult
import com.cashu.me.Core.DepositRecoveryStatus
import com.cashu.me.Core.WalletManager
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.ui.components.InlineNoticeHost
import com.cashu.me.ui.components.NoticeSeverity
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.components.ToolbarIcon
import com.cashu.me.ui.theme.CashuTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoverDepositScreen(walletManager: WalletManager, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val walletState by walletManager.state.collectAsState()
    var mintUrl by rememberSaveable { mutableStateOf("") }
    var quoteId by rememberSaveable { mutableStateOf("") }
    var methodRaw by rememberSaveable { mutableStateOf("bolt11") }
    val method = PaymentMethodKind.fromRaw(methodRaw) ?: PaymentMethodKind.Bolt11
    var unit by rememberSaveable { mutableStateOf("sat") }
    var searchLimit by rememberSaveable { mutableStateOf(1_000) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<DepositRecoveryResult?>(null) }
    var approval by remember { mutableStateOf<DepositRecoveryRequest?>(null) }
    fun clearResult() { error = null; result = null }

    BackHandler(enabled = working) { /* Keep the approved operation visible until it finishes. */ }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Recover a deposit", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onClose, enabled = !working) {
                    ToolbarIcon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
            .padding(CashuTheme.spacing.comfortable), verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default)) {
            Text("Recover a paid deposit that was never added to your balance. You need the mint URL and quote ID saved by the original wallet. An invoice, payment hash, or Bitcoin address alone is not enough.")
            Text("Uses this wallet's recovery phrase. Restore the original phrase first if this deposit belongs to another wallet.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(value = mintUrl, onValueChange = { mintUrl = it; clearResult() }, label = { Text("Mint URL") },
                singleLine = true, enabled = !working, modifier = Modifier.fillMaxWidth().testTag("depositRecoveryMintURL"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri))
            OutlinedTextField(value = quoteId, onValueChange = { quoteId = it; clearResult() }, label = { Text("Quote ID") },
                singleLine = true, enabled = !working, modifier = Modifier.fillMaxWidth().testTag("depositRecoveryQuoteID"),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false))
            RecoveryChoice("Payment method", method.friendlyTitle, PaymentMethodKind.entries.map { it.friendlyTitle }, !working) {
                methodRaw = PaymentMethodKind.entries[it].rawValue; clearResult()
            }
            RecoveryChoice("Currency", if (unit == "sat") "Sats" else unit.uppercase(),
                DepositRecoveryRequest.units.map { if (it == "sat") "Sats" else it.uppercase() }, !working) {
                unit = DepositRecoveryRequest.units[it]; clearResult()
            }
            RecoveryChoice("Search depth", if (searchLimit == 1_000) "Up to 1,000 deposits" else "Up to 10,000 deposits",
                listOf("Up to 1,000 deposits", "Up to 10,000 deposits"), !working) {
                searchLimit = DepositRecoveryRequest.searchLimits[it].toInt(); clearResult()
            }
            Text("Try the deeper search if this wallet created many deposits before you lost its data. Recovery does not find unknown quote IDs.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            InlineNoticeHost(text = error, severity = NoticeSeverity.Error)
            InlineNoticeHost(text = result?.message, severity = if (result?.status == DepositRecoveryStatus.Recovered) NoticeSeverity.Success else NoticeSeverity.Info,
                modifier = Modifier.testTag("depositRecoveryResult"))
            PrimaryButton(text = if (working) "Recovering…" else "Recover deposit", loading = working,
                enabled = walletState.isRuntimeReady && mintUrl.isNotBlank() && quoteId.isNotBlank(),
                modifier = Modifier.testTag("recoverDepositButton"), onClick = {
                    clearResult()
                    runCatching { DepositRecoveryRequest(mintUrl, quoteId, method, unit, searchLimit.toUInt()).validated() }
                        .onSuccess { approval = it }.onFailure { error = it.message }
                })
        }
    }
    approval?.let { request ->
        AlertDialog(onDismissRequest = { approval = null }, title = { Text("Recover this deposit?") },
            text = { Text("Use this wallet's recovery phrase to restore ecash at this mint, find the deposit key, and claim available funds. Pending deposits will continue recovering in the background.") },
            dismissButton = { TextButton(onClick = { approval = null }) { Text("Cancel") } },
            confirmButton = { TextButton(onClick = {
                approval = null
                working = true
                scope.launch {
                    try { result = walletManager.recoverDeposit(request) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (invalid: IllegalArgumentException) { error = invalid.message }
                    catch (_: Throwable) { error = "Recovery could not finish. Check the mint and deposit details, then retry. Any saved deposit will remain available for recovery." }
                    finally { working = false }
                }
            }) { Text("Recover deposit") } })
    }
}

@Composable
private fun RecoveryChoice(label: String, value: String, options: List<String>, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Text("$label: $value")
        }
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(index) })
            }
        }
    }
}
