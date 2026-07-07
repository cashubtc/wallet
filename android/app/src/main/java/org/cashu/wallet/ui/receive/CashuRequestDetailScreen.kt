package org.cashu.wallet.ui.receive

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CurrencyExchange
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Receipt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.CashuRequestStore
import org.cashu.wallet.Core.NostrService
import org.cashu.wallet.Core.PaymentRequestBuilder
import org.cashu.wallet.Core.Protocols.CurrencyAmount
import org.cashu.wallet.Core.Protocols.CurrencyRegistry
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.CashuTextField
import org.cashu.wallet.ui.components.DestructiveTextButton
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InspectorRow
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.QrCard
import org.cashu.wallet.ui.components.SectionHeader
import org.cashu.wallet.ui.components.UnitPickerSheet
import org.cashu.wallet.ui.components.copyTextWithToast
import org.cashu.wallet.ui.components.requestRowTitle
import org.cashu.wallet.ui.components.shareText
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CashuRequestDetailScreen(
    walletManager: WalletManager,
    settingsManager: SettingsManager,
    nostrService: NostrService,
    cashuRequestStore: CashuRequestStore,
    requestId: String,
    onClose: () -> Unit,
) {
    val storeState by cashuRequestStore.state.collectAsStateWithLifecycle()
    val walletState by walletManager.state.collectAsStateWithLifecycle()
    val settings by settingsManager.state.collectAsStateWithLifecycle()
    val nostrState by nostrService.state.collectAsStateWithLifecycle()
    val formatter = remember { AmountFormatter() }
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val request = storeState.requests.firstOrNull { it.id == requestId }
    var copied by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var amountEditorOpen by remember { mutableStateOf(false) }
    var memoEditorOpen by remember { mutableStateOf(false) }
    var mintPickerOpen by remember { mutableStateOf(false) }
    var unitPickerOpen by remember { mutableStateOf(false) }
    var editError by remember { mutableStateOf<String?>(null) }
    val screenTitle = request?.let(::requestRowTitle) ?: "Cashu Request"

    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }

    // Track payment count changes for celebration animation.
    val paymentCount = request?.receivedPayments?.size ?: 0
    var previousCount by remember(requestId) { mutableStateOf(paymentCount) }
    var celebrate by remember { mutableStateOf(false) }
    LaunchedEffect(paymentCount) {
        if (paymentCount > previousCount && previousCount >= 0) {
            celebrate = true
            delay(2500)
            celebrate = false
        }
        previousCount = paymentCount
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    if (request != null) {
                        IconButton(onClick = {
                            context.shareText(request.encoded, subject = screenTitle)
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
        if (request == null) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "Request not found",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(CashuTheme.spacing.comfortable))
                GhostButton(text = "Back", onClick = onClose)
            }
            return@Scaffold
        }
        val isQuoteIntent = request.quoteId != null

        fun regenerateRequest(
            amount: Long? = request.amount,
            unit: String = request.unit,
            mints: List<String> = request.mints,
            memo: String? = request.memo,
        ) {
            val relays = settings.nostrRelays
            if (nostrState.publicKeyHex.isBlank() || relays.isEmpty()) {
                editError = "Nostr isn't ready. Check your relays in Settings."
                return
            }
            val normalizedAmount = amount?.takeIf { it > 0 }
            val normalizedMemo = memo?.trim()?.takeIf { it.isNotBlank() }
            runCatching {
                val encoded = PaymentRequestBuilder.build(
                    id = request.id,
                    amount = normalizedAmount,
                    unit = unit,
                    mints = mints,
                    description = normalizedMemo,
                    nostrPubkeyHex = nostrState.publicKeyHex,
                    relays = relays,
                )
                cashuRequestStore.update(
                    id = request.id,
                    amount = normalizedAmount,
                    unit = unit,
                    mints = mints,
                    memo = normalizedMemo,
                    encoded = encoded,
                ) ?: cashuRequestStore.upsert(
                    request.copy(
                        amount = normalizedAmount,
                        unit = unit,
                        mints = mints,
                        memo = normalizedMemo,
                        encoded = encoded,
                    ),
                )
            }.onSuccess {
                editError = null
            }.onFailure {
                editError = it.message ?: "Couldn't update request."
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = CashuTheme.spacing.comfortable),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.comfortable),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(CashuTheme.spacing.snug))
            QrCard(content = request.encoded, shareSubject = screenTitle, staticOnly = true)

            // Request amounts render in the request's own unit.
            val isSatRequest = request.unit.equals("sat", ignoreCase = true)
            val requestCurrency = CurrencyRegistry.currencyForMintUnit(request.unit)
            fun formatRequestAmount(amount: Long): String = if (isSatRequest) {
                formatter.formatWalletSats(amount, settings.useBitcoinSymbol)
            } else {
                CurrencyAmount(amount, requestCurrency).formatted()
            }

            if (request.amount != null && request.amount > 0L) {
                AmountText(
                    text = formatRequestAmount(request.amount),
                    style = MaterialTheme.typography.headlineSmall.withMonoDigits(),
                )
            }

            StatusBlock(
                received = request.receivedPayments.isNotEmpty(),
                paymentCount = paymentCount,
                celebrate = celebrate,
            )

            SectionHeader("Details")
            Column(modifier = Modifier.fillMaxWidth()) {
                val activeMintUrl = request.mints.firstOrNull()
                val mintLabel = activeMintUrl?.let { url ->
                    walletState.mints.firstOrNull { it.url == url }?.name ?: url
                } ?: "Any mint"
                if (isQuoteIntent) {
                    InspectorRow(
                        label = "Type",
                        value = screenTitle,
                        leadingIcon = Icons.Outlined.Receipt,
                    )
                    CanvasDivider(leadingInset = 16)
                }
                InspectorRow(
                    label = "Mint",
                    value = mintLabel,
                    leadingIcon = Icons.Outlined.AccountBalance,
                    editable = !isQuoteIntent,
                    onClick = if (isQuoteIntent) null else ({ mintPickerOpen = true }),
                )
                CanvasDivider(leadingInset = 16)
                InspectorRow(
                    label = "Amount",
                    value = request.amount?.let {
                        if (isSatRequest) "$it sat" else formatRequestAmount(it)
                    } ?: "Any",
                    leadingIcon = Icons.Outlined.AccountBalanceWallet,
                    valueMonospaced = true,
                    editable = !isQuoteIntent,
                    onClick = if (isQuoteIntent) null else ({ amountEditorOpen = true }),
                )
                CanvasDivider(leadingInset = 16)
                InspectorRow(
                    label = "Unit",
                    value = request.unit.uppercase(),
                    leadingIcon = Icons.Outlined.CurrencyExchange,
                    editable = !isQuoteIntent,
                    onClick = if (isQuoteIntent) null else ({ unitPickerOpen = true }),
                )
                CanvasDivider(leadingInset = 16)
                InspectorRow(
                    label = "Memo",
                    value = request.memo ?: "None",
                    editable = !isQuoteIntent,
                    onClick = if (isQuoteIntent) null else ({ memoEditorOpen = true }),
                )
                CanvasDivider(leadingInset = 16)
                InspectorRow(
                    label = "Created",
                    value = formatDate(request.createdAtEpochMillis),
                    leadingIcon = Icons.Outlined.CalendarToday,
                )
                if (request.totalReceived > 0L) {
                    CanvasDivider(leadingInset = 16)
                    InspectorRow(
                        label = "Total received",
                        value = formatRequestAmount(request.totalReceived),
                        leadingIcon = Icons.Outlined.CheckCircle,
                        valueMonospaced = true,
                    )
                }
            }
            if (editError != null) {
                InlineNotice(text = editError!!)
            }

            Spacer(Modifier.height(CashuTheme.spacing.snug))
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
            ) {
                PrimaryButton(
                    text = if (copied) "Copied" else copyActionLabel(screenTitle),
                    onClick = {
                        clipboard.copyTextWithToast(context, request.encoded)
                        copied = true
                    },
                )
                DestructiveTextButton(
                    text = "Remove from history",
                    onClick = { confirmDelete = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Spacer(Modifier.height(CashuTheme.spacing.section))
        }

        if (!isQuoteIntent && amountEditorOpen) {
            RequestAmountDialog(
                initialAmount = request.amount,
                unit = request.unit,
                onSave = { amount ->
                    amountEditorOpen = false
                    regenerateRequest(amount = amount)
                },
                onDismiss = { amountEditorOpen = false },
            )
        }

        if (!isQuoteIntent && memoEditorOpen) {
            RequestMemoDialog(
                initialMemo = request.memo.orEmpty(),
                onSave = { memo ->
                    memoEditorOpen = false
                    regenerateRequest(memo = memo)
                },
                onDismiss = { memoEditorOpen = false },
            )
        }

        if (!isQuoteIntent && mintPickerOpen) {
            RequestMintPickerSheet(
                mints = walletState.mints,
                selectedMintUrl = request.mints.firstOrNull(),
                onSelectAny = {
                    mintPickerOpen = false
                    regenerateRequest(mints = emptyList())
                },
                onSelectMint = { mint ->
                    mintPickerOpen = false
                    val nextUnit = if (request.unit in mint.effectiveMintUnits) {
                        request.unit
                    } else {
                        mint.defaultMintUnit
                    }
                    regenerateRequest(
                        unit = nextUnit,
                        mints = listOf(mint.url),
                    )
                },
                onDismiss = { mintPickerOpen = false },
            )
        }

        if (!isQuoteIntent && unitPickerOpen) {
            UnitPickerSheet(
                units = requestUnitOptions(walletState.mints, request.mints),
                selectedUnit = request.unit,
                onSelect = { unit ->
                    unitPickerOpen = false
                    regenerateRequest(unit = unit)
                },
                onDismiss = { unitPickerOpen = false },
                title = "Choose request unit",
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remove from history?") },
            text = {
                Text(
                    "The request will be removed from this device. Shared payment links keep routing to this wallet, and any payments already received stay in your wallet.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    cashuRequestStore.delete(request!!.id)
                    onClose()
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun RequestAmountDialog(
    initialAmount: Long?,
    unit: String,
    onSave: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var rawAmount by remember(initialAmount) { mutableStateOf(initialAmount?.toString().orEmpty()) }
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit amount") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default)) {
                CashuTextField(
                    value = rawAmount,
                    onValueChange = {
                        rawAmount = it.filter { ch -> ch.isDigit() }
                        errorText = null
                    },
                    label = "Amount",
                    supportingText = "Leave blank for any amount. Values are in ${unit.uppercase()}.",
                    isError = errorText != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                if (errorText != null) {
                    InlineNotice(text = errorText!!)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = rawAmount.trim().takeIf { it.isNotEmpty() }?.toLongOrNull()
                if (rawAmount.isNotBlank() && (parsed == null || parsed <= 0)) {
                    errorText = "Enter a positive whole number or leave it blank."
                    return@TextButton
                }
                onSave(parsed)
            }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun RequestMemoDialog(
    initialMemo: String,
    onSave: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var memo by remember(initialMemo) { mutableStateOf(initialMemo) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit memo") },
        text = {
            CashuTextField(
                value = memo,
                onValueChange = { memo = it },
                label = "Memo",
                placeholder = "Optional",
                minLines = 3,
                maxLines = 5,
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(memo.trim().takeIf { it.isNotBlank() }) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RequestMintPickerSheet(
    mints: List<MintInfo>,
    selectedMintUrl: String?,
    onSelectAny: () -> Unit,
    onSelectMint: (MintInfo) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CashuTheme.spacing.comfortable)
                .navigationBarsPadding(),
        ) {
            Text(
                text = "Choose request mint",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(
                    horizontal = CashuTheme.spacing.snug,
                    vertical = CashuTheme.spacing.default,
                ),
            )
            RequestMintPickerRow(
                label = "Any mint",
                supporting = "Let the sender choose a compatible mint",
                selected = selectedMintUrl == null,
                onClick = onSelectAny,
            )
            mints.forEach { mint ->
                RequestMintPickerRow(
                    label = mint.name,
                    supporting = mint.url,
                    selected = mint.url == selectedMintUrl,
                    onClick = { onSelectMint(mint) },
                )
            }
            Spacer(Modifier.height(CashuTheme.spacing.snug))
        }
    }
}

@Composable
private fun RequestMintPickerRow(
    label: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(
                horizontal = CashuTheme.spacing.snug,
                vertical = CashuTheme.spacing.default,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Icon(
            imageVector = Icons.Outlined.AccountBalance,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(CashuTheme.spacing.loose),
            )
        }
    }
}

private fun requestUnitOptions(mints: List<MintInfo>, selectedMintUrls: List<String>): List<String> {
    val selected = selectedMintUrls.takeIf { it.isNotEmpty() }?.toSet()
    val source = selected?.let { urls -> mints.filter { it.url in urls } }.orEmpty()
        .ifEmpty { mints }
    val units = source
        .flatMap { it.effectiveMintUnits.ifEmpty { it.units } }
        .ifEmpty { listOf("sat") }
        .distinct()
    return units.sortedWith(compareBy<String> { if (it == "sat") 0 else 1 }.thenBy { it })
}

private fun copyActionLabel(title: String): String =
    when (title) {
        "Lightning Invoice" -> "Copy invoice"
        "Reusable Invoice" -> "Copy invoice"
        "Bitcoin Address" -> "Copy address"
        else -> "Copy request"
    }

@Composable
private fun StatusBlock(received: Boolean, paymentCount: Int, celebrate: Boolean) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
    ) {
        if (received) {
            // Live celebration grows in gently (0.9 → 1, the one delight beat);
            // the persistent N-payments state is quiet — no animation.
            AnimatedVisibility(
                visible = true,
                enter = if (celebrate) {
                    scaleIn(
                        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
                        initialScale = 0.9f,
                    ) + fadeIn()
                } else {
                    fadeIn()
                },
                exit = fadeOut(),
            ) {
                Icon(
                    imageVector = Icons.Outlined.CheckCircle,
                    contentDescription = null,
                    tint = CashuTheme.colors.received,
                    modifier = Modifier.size(CashuTheme.spacing.loose),
                )
            }
            Text(
                text = when {
                    celebrate -> "Payment received!"
                    paymentCount == 1 -> "1 payment received"
                    else -> "$paymentCount payments received"
                },
                style = MaterialTheme.typography.titleMedium,
                color = CashuTheme.colors.received,
            )
        } else {
            val transition = rememberInfiniteTransition(label = "waiting-pulse")
            val alpha by transition.animateFloat(
                initialValue = 1f,
                targetValue = 0.4f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1100),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "waiting-pulse-alpha",
            )
            Box(modifier = Modifier.alpha(alpha)) {
                Icon(
                    imageVector = Icons.Outlined.Schedule,
                    contentDescription = null,
                    tint = CashuTheme.colors.pending,
                    modifier = Modifier.size(CashuTheme.spacing.loose),
                )
            }
            Text(
                text = "Waiting for payment…",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

private fun formatDate(epochMillis: Long): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMillis))
