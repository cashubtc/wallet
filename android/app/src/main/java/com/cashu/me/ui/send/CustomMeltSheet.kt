package com.cashu.me.ui.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import com.cashu.me.Core.Protocols.CurrencyAmount
import com.cashu.me.Core.Protocols.CurrencyRegistry
import com.cashu.me.Core.UnitAmountEntry
import com.cashu.me.Core.AmountFormatter
import com.cashu.me.Core.WalletManager
import com.cashu.me.Models.AdvertisedPaymentMethod
import com.cashu.me.Models.MeltQuoteInfo
import com.cashu.me.Models.MeltSettlement
import com.cashu.me.Models.MintInfo
import com.cashu.me.Models.TransactionStatus
import com.cashu.me.ui.components.AmountText
import com.cashu.me.ui.components.AmountEntryHero
import com.cashu.me.ui.components.DescriptionDetailRow
import com.cashu.me.ui.components.InlineNotice
import com.cashu.me.ui.components.InspectorRow
import com.cashu.me.ui.components.NoticeSeverity
import com.cashu.me.ui.components.MintSelectorDirection
import com.cashu.me.ui.components.MintSelectorRow
import com.cashu.me.ui.components.NumberPadFooter
import com.cashu.me.ui.components.QuoteAmountEntry
import com.cashu.me.ui.components.PaymentDetailContent
import com.cashu.me.ui.components.PaymentStatusPhase
import com.cashu.me.ui.components.PaymentStatusScreen
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.components.QrCard
import com.cashu.me.ui.components.QuoteReferenceDetails
import com.cashu.me.ui.components.SheetHeader
import com.cashu.me.ui.theme.CashuTheme
import kotlinx.coroutines.CancellationException

/** The user explicitly chooses a mint/method/unit before entering an opaque request. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CustomMeltSheet(walletManager: WalletManager, mint: MintInfo, method: AdvertisedPaymentMethod, onClose: () -> Unit) {
    val wallet by walletManager.state.collectAsState()
    var rawAmount by remember { mutableStateOf("") }
    var request by remember { mutableStateOf("") }
    var requestDraft by remember { mutableStateOf("") }
    var showRequestEditor by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf<MeltQuoteInfo?>(null) }
    var balance by remember { mutableStateOf<Long?>(null) }
    var working by remember { mutableStateOf(false) }
    var submitted by remember { mutableStateOf(false) }
    var settled by remember { mutableStateOf(false) }
    var settledFee by remember { mutableStateOf<Long?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val currency = CurrencyRegistry.currencyForMintUnit(method.unit)
    val formatter = remember { AmountFormatter() }
    val amount = UnitAmountEntry.validatedBaseUnits(rawAmount, currency.decimals)
    val transaction = wallet.transactions.firstOrNull {
        quote != null && it.quoteId == quote?.id && it.mintUrl == mint.url && it.unit == method.unit
    }
    fun formatted(value: Long) = CurrencyAmount(value, currency).formatted()
    val amountWarning = when {
        amount == null -> null
        amount > (balance ?: 0) -> "Insufficient balance."
        method.minAmount?.let { amount < it } == true -> "Minimum: ${formatted(requireNotNull(method.minAmount))}"
        method.maxAmount?.let { amount > it } == true -> "Maximum: ${formatted(requireNotNull(method.maxAmount))}"
        else -> null
    }

    fun submit() {
        val current = quote
        working = true
        error = null
        if (current != null) submitted = true
        // Submission survives dismissal/recreation; CDK owns the durable operation.
        walletManager.launch {
            try {
                if (current == null) quote = walletManager.createCustomMeltQuote(method.method, request, requireNotNull(amount), mint.url, method.unit)
                else {
                    val result = walletManager.meltTokens(current.id, current.mintUrl)
                    settled = result.settlement == MeltSettlement.Settled
                    if (settled) settledFee = result.feePaid
                }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (failure: Exception) { error = failure.message ?: "Payment could not be completed."
            } finally { working = false }
        }
    }

    LaunchedEffect(mint.url, method.unit) { balance = walletManager.unitBalance(mint.url, method.unit) }
    LaunchedEffect(transaction?.status) {
        if (submitted && transaction?.status == TransactionStatus.Completed) { settled = true; error = null }
    }
    ModalBottomSheet(
        onDismissRequest = { if (!working) onClose() },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !working }),
    ) {
        SheetHeader(title = method.displayName, actions = {
            if (quote == null) {
                IconButton(onClick = { requestDraft = request; showRequestEditor = true }, enabled = !working) {
                    Icon(Icons.Outlined.Edit, contentDescription = "Payment request or memo${if (request.isEmpty()) " (optional)" else ": $request"}")
                }
            }
        })
        val current = quote
        if (settled && current != null) {
            PaymentStatusScreen(phase = PaymentStatusPhase.Success, title = "Payment Sent!", onDone = onClose, rows = {
                InspectorRow(label = "Amount", value = formatted(current.amount))
                InspectorRow(label = "Fee", value = formatted(settledFee ?: transaction?.fee ?: 0))
                InspectorRow(label = "Mint", value = mint.name)
            })
        } else {
            Column(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(),
            ) {
                if (current == null) {
                    QuoteAmountEntry(hero = {
                        Box(Modifier.testTag("custom-payment-amount")) {
                            AmountEntryHero(entryRaw = rawAmount, isSat = method.unit == "sat", unit = method.unit,
                                useBitcoinSymbol = false, formatter = formatter)
                        }
                        (error ?: amountWarning)?.let {
                            Spacer(Modifier.height(CashuTheme.spacing.default))
                            InlineNotice(text = it, severity = NoticeSeverity.Caution)
                        }
                    }, details = {
                        MintSelectorRow(direction = MintSelectorDirection.Source, mint = mint,
                            balanceText = balance?.let(::formatted) ?: "…", showBalance = true,
                            modifier = Modifier.padding(horizontal = CashuTheme.spacing.snug))
                        Spacer(Modifier.height(CashuTheme.spacing.snug))
                    }, footer = {
                        NumberPadFooter(amount = rawAmount, onAmountChange = { if (!working) rawAmount = it },
                            decimals = currency.decimals, buttonText = "Create quote", onButtonClick = ::submit,
                            buttonEnabled = !working && amount != null && method.accepts(amount) && amount <= (balance ?: 0),
                            buttonLoading = working, buttonModifier = Modifier.testTag("custom-payment-quote"))
                    })
                } else {
                    PaymentDetailContent(
                        modifier = Modifier.weight(1f),
                        hero = { qrSize ->
                            QrCard(content = current.id, size = qrSize, staticOnly = true, confirmationMessage = "Copied quote ID")
                        },
                    ) {
                        AmountText(text = formatted(current.amount),
                            style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                            semanticsLabel = "Payment amount: ${formatted(current.amount)}")
                        if (submitted && (error == null || transaction != null)) {
                            Text(if (working) "Sending…" else if (transaction?.status == TransactionStatus.Failed) "Payment failed" else "Payment pending",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        QuoteReferenceDetails(current.id)
                        if (!submitted && current.totalAmount > (balance ?: 0)) {
                            InlineNotice(text = "Insufficient balance, including fees.", severity = NoticeSeverity.Caution)
                        }
                        if (!submitted && current.isExpired) {
                            InlineNotice(text = "Quote expired. Edit the amount to request a new quote.", severity = NoticeSeverity.Caution)
                        }
                        error?.let { InlineNotice(text = it, severity = NoticeSeverity.Caution) }
                        Column {
                            InspectorRow(label = "Maximum fee", value = formatted(current.feeReserve), valueMonospaced = true)
                            InspectorRow(label = "Total", value = formatted(current.totalAmount), valueMonospaced = true)
                            InspectorRow(label = "Mint", value = mint.name)
                            if (request.isNotEmpty()) DescriptionDetailRow(request)
                        }
                    }
                }
                if (current != null) Column(
                    modifier = Modifier.padding(horizontal = CashuTheme.spacing.comfortable).navigationBarsPadding(),
                    verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (submitted) {
                        PrimaryButton(text = "Done", enabled = !working, onClick = onClose)
                    } else {
                        PrimaryButton(
                            text = if (working) "Processing…" else "Pay",
                            enabled = !working && !current.isExpired && current.totalAmount <= (balance ?: 0),
                            modifier = Modifier.testTag("custom-payment-pay"),
                            onClick = ::submit,
                        )
                        TextButton(onClick = { quote = null; error = null }, enabled = !working) { Text("Edit amount") }
                    }
                }
            }
        }
    }
    if (showRequestEditor) {
        AlertDialog(onDismissRequest = { showRequestEditor = false },
            title = { Text("Payment request or memo") },
            text = {
                OutlinedTextField(requestDraft, { requestDraft = it }, label = { Text("Optional") },
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                    modifier = Modifier.fillMaxWidth().testTag("custom-payment-request"))
            },
            confirmButton = { TextButton(onClick = { request = requestDraft; showRequestEditor = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showRequestEditor = false }) { Text("Cancel") } },
        )
    }
}
