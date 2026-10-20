package com.cashu.me.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import com.cashu.me.Core.DepositRecoveryRequest
import com.cashu.me.Core.DepositRecoveryResult
import com.cashu.me.Core.DepositRecoveryStatus
import com.cashu.me.Core.WalletManager
import com.cashu.me.Models.PaymentMethodKind
import com.cashu.me.ui.components.ActionConfirmationSheet
import com.cashu.me.ui.components.CashuTextField
import com.cashu.me.ui.components.InlineNoticeHost
import com.cashu.me.ui.components.MintPickerSheet
import com.cashu.me.ui.components.NavRow
import com.cashu.me.ui.components.NoticeSeverity
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.components.ToolbarIcon
import com.cashu.me.ui.theme.CashuTheme
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoverDepositScreen(walletManager: WalletManager, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val walletState by walletManager.state.collectAsState()
    var mintUrl by rememberSaveable { mutableStateOf("") }
    var quoteId by rememberSaveable { mutableStateOf("") }
    var methodRaw by rememberSaveable { mutableStateOf("bolt11") }
    val method = PaymentMethodKind.fromRaw(methodRaw) ?: PaymentMethodKind.Bolt11
    val recoveryMethods = listOf(PaymentMethodKind.Bolt11, PaymentMethodKind.Bolt12, PaymentMethodKind.Onchain)
    var unit by rememberSaveable { mutableStateOf("sat") }
    var searchLimit by rememberSaveable { mutableStateOf(1_000) }
    var showOptions by rememberSaveable { mutableStateOf(false) }
    var showMintPicker by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<DepositRecoveryResult?>(null) }
    var approval by remember { mutableStateOf<DepositRecoveryRequest?>(null) }
    val recovered = result?.status == DepositRecoveryStatus.Recovered && result?.remaining == 0L
    val unitLabel = if (unit == "sat") "Sats" else unit.uppercase()
    fun clearResult() { error = null; result = null }

    BackHandler(enabled = working) { /* Keep the approved operation visible until it finishes. */ }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Recover deposit", style = MaterialTheme.typography.titleMedium) },
            navigationIcon = {
                IconButton(onClick = onClose, enabled = !working) {
                    ToolbarIcon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background))
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState())
            .padding(top = CashuTheme.spacing.loose, bottom = CashuTheme.spacing.section)) {
            Column(Modifier.padding(horizontal = CashuTheme.spacing.comfortable),
                verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.loose)) {
                CashuTextField(value = mintUrl, onValueChange = { mintUrl = it; clearResult() }, label = "Mint URL",
                    placeholder = "https://…", singleLine = true, enabled = !working,
                    modifier = Modifier.fillMaxWidth().testTag("depositRecoveryMintURL"),
                    trailingIcon = if (walletState.mints.isNotEmpty()) {
                        {
                            IconButton(enabled = !working, modifier = Modifier.testTag("depositRecoveryMintPicker"),
                                onClick = { focus.clearFocus(); showMintPicker = true }) {
                                Icon(Icons.Outlined.ExpandMore, contentDescription = "Choose mint")
                            }
                        }
                    } else null,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                    keyboardActions = KeyboardActions(onNext = { focus.moveFocus(FocusDirection.Down) }))
                CashuTextField(value = quoteId, onValueChange = { quoteId = it; clearResult() }, label = "Quote ID",
                    placeholder = "Paste quote ID", singleLine = true, enabled = !working,
                    modifier = Modifier.fillMaxWidth().testTag("depositRecoveryQuoteID"),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false,
                        imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }))
            }
            NavRow(title = "Options", subtitle = "${method.friendlyTitle} · $unitLabel", enabled = !working,
                trailingIcon = if (showOptions) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                modifier = Modifier.padding(top = CashuTheme.spacing.snug).testTag("depositRecoveryOptions")
                    .semantics { stateDescription = if (showOptions) "Expanded" else "Collapsed" },
                onClick = { focus.clearFocus(); showOptions = !showOptions })
            Column(Modifier.animateContentSize()) {
                if (showOptions) {
                    RecoveryChoice("Payment method", method.friendlyTitle, recoveryMethods.map { it.friendlyTitle }, !working) {
                        methodRaw = recoveryMethods[it].rawValue; clearResult()
                    }
                    RecoveryChoice("Currency", unitLabel,
                        DepositRecoveryRequest.units.map { if (it == "sat") "Sats" else it.uppercase() }, !working) {
                        unit = DepositRecoveryRequest.units[it]; clearResult()
                    }
                    RecoveryChoice("Search depth", if (searchLimit == 1_000) "1,000 deposits" else "10,000 deposits",
                        listOf("1,000 deposits", "10,000 deposits"), !working) {
                        searchLimit = DepositRecoveryRequest.searchLimits[it].toInt(); clearResult()
                    }
                }
            }
            Column(Modifier.padding(horizontal = CashuTheme.spacing.comfortable),
                verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default)) {
                InlineNoticeHost(text = error, severity = NoticeSeverity.Error)
                InlineNoticeHost(text = result?.message,
                    severity = if (result?.status == DepositRecoveryStatus.Recovered) NoticeSeverity.Success else NoticeSeverity.Info,
                    modifier = Modifier.testTag("depositRecoveryResult"))
                PrimaryButton(text = if (recovered) "Done" else "Recover deposit", loading = working,
                    enabled = !working && walletState.isRuntimeReady && mintUrl.isNotBlank() && quoteId.isNotBlank(),
                    modifier = Modifier.padding(top = CashuTheme.spacing.snug).testTag("recoverDepositButton"), onClick = {
                        focus.clearFocus()
                        if (recovered) onClose() else {
                            clearResult()
                            runCatching { DepositRecoveryRequest(mintUrl, quoteId, method, unit, searchLimit.toUInt()).validated() }
                                .onSuccess { approval = it }.onFailure { error = it.message }
                        }
                    })
                if (!recovered) {
                    Text("Uses this wallet's seed phrase.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
    if (showMintPicker) {
        MintPickerSheet(
            mints = walletState.mints,
            activeMintUrl = walletState.mints.firstOrNull { it.url.trimEnd('/') == mintUrl.trim().trimEnd('/') }?.url,
            mintSubtitle = { it.url.removePrefix("https://") },
            onSelect = { mint ->
                showMintPicker = false
                mint?.let { mintUrl = it.url; clearResult() }
            },
            onDismiss = { showMintPicker = false },
        )
    }
    approval?.let { request ->
        ActionConfirmationSheet(title = "Recover this deposit?",
            message = "Claim this deposit at ${URI(request.mintUrl).host} using this wallet's seed phrase.",
            actionLabel = "Recover", onDismiss = { approval = null }, onConfirm = {
                approval = null
                working = true
                scope.launch {
                    try { result = walletManager.recoverDeposit(request) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (invalid: IllegalArgumentException) { error = invalid.message }
                    catch (_: Throwable) { error = "Recovery failed. Check the deposit details and try again." }
                    finally { working = false }
                }
            })
    }
}

@Composable
private fun RecoveryChoice(label: String, value: String, options: List<String>, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        NavRow(title = label, trailingValue = value, enabled = enabled,
            trailingIcon = Icons.Outlined.ExpandMore, onClick = { expanded = true })
        DropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelect(index) })
            }
        }
    }
}
