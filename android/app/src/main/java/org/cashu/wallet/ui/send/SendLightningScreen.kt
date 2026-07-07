package org.cashu.wallet.ui.send

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CurrencyBitcoin
import androidx.compose.material.icons.outlined.Receipt
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cashu.wallet.Core.AmountFormatter
import org.cashu.wallet.Core.PaymentRequestDecodeResult
import org.cashu.wallet.Core.PaymentRequestDecoder
import org.cashu.wallet.Core.SettingsManager
import org.cashu.wallet.Core.WalletManager
import org.cashu.wallet.Core.compatibleMintsForCashuPaymentRequest
import org.cashu.wallet.Models.MeltPaymentResult
import org.cashu.wallet.ui.components.AmountText
import org.cashu.wallet.ui.components.CanvasDivider
import org.cashu.wallet.ui.components.CashuTextField
import org.cashu.wallet.ui.components.GhostButton
import org.cashu.wallet.ui.components.InlineNotice
import org.cashu.wallet.ui.components.InspectorRow
import org.cashu.wallet.ui.components.NumberPad
import org.cashu.wallet.ui.components.PaymentStatusPhase
import org.cashu.wallet.ui.components.PaymentStatusScreen
import org.cashu.wallet.ui.components.PrimaryButton
import org.cashu.wallet.ui.components.TwoFaceScreen
import org.cashu.wallet.ui.theme.CashuTheme
import org.cashu.wallet.ui.theme.withMonoDigits

// Multi-line paste area for invoices / addresses; large enough to fit a BOLT12 string at body size.
private val DESTINATION_FIELD_HEIGHT = 160.dp
// Hero status icon on Done/Failed screens — much larger than the inline 20dp icons.
private val STATUS_HERO_ICON = 56.dp

private sealed interface PayFace {
    data object Input : PayFace
    data class Confirm(val raw: String, val decoded: PaymentRequestDecodeResult, val amount: Long?) : PayFace
    data class Paying(val raw: String) : PayFace
    data class Done(val result: MeltPaymentResult?) : PayFace
    data class Failed(val reason: String) : PayFace
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendLightningScreen(
    walletManager: WalletManager,
    settingsManager: SettingsManager,
    onClose: () -> Unit,
    prefilledPayload: String? = null,
    onPrefilledConsumed: () -> Unit = {},
) {
    val walletState by walletManager.state.collectAsState()
    val settings by settingsManager.state.collectAsState()
    val formatter = remember { AmountFormatter() }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current

    var face: PayFace by remember { mutableStateOf(PayFace.Input) }
    var input by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }

    fun decode(raw: String) {
        errorText = null
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) {
            errorText = "Paste an invoice or address."
            return
        }
        var decoded = PaymentRequestDecoder.decode(
            trimmed,
            includeCashuPaymentRequests = true,
            preferCashuPaymentRequests = true,
        )
        if (decoded is PaymentRequestDecodeResult.Unrecognized) {
            errorText = "Couldn't read that. Paste a Lightning invoice, BOLT12 offer, on-chain address, Lightning address, or Cashu request."
            return
        }
        var request = trimmed
        if (decoded is PaymentRequestDecodeResult.CashuPaymentRequest &&
            compatibleMintsForCashuPaymentRequest(decoded.summary, walletState.mints).isEmpty()
        ) {
            // BIP-321 payloads can carry a Lightning invoice alongside the creq.
            // When the user holds none of the requested mints the ecash leg is
            // unpayable, so fall back to the Lightning/on-chain leg if present.
            val summary = decoded.summary
            val fallback = PaymentRequestDecoder.decode(trimmed)
            if (fallback is PaymentRequestDecodeResult.Unrecognized) {
                val required = summary.mints.joinToString()
                errorText = if (required.isEmpty()) {
                    "Add a mint before paying this Cashu request."
                } else {
                    "This Cashu request requires a mint you don't have: $required"
                }
                return
            }
            decoded = fallback
            if (fallback is PaymentRequestDecodeResult.Bolt11 || fallback is PaymentRequestDecodeResult.Bolt12) {
                request = PaymentRequestDecoder.encodedLightningRequest(trimmed) ?: trimmed
            }
        }
        val knownAmount = decoded.knownAmountSats()
        face = PayFace.Confirm(raw = request, decoded = decoded, amount = knownAmount)
        if (knownAmount != null) amount = knownAmount.toString()
    }

    LaunchedEffect(prefilledPayload) {
        val pre = prefilledPayload?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        input = pre
        decode(pre)
        onPrefilledConsumed()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (face) {
                            PayFace.Input -> "Send Bitcoin"
                            is PayFace.Confirm -> "Confirm payment"
                            is PayFace.Paying -> "Sending"
                            is PayFace.Done -> "Sent"
                            is PayFace.Failed -> "Failed"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when (face) {
                            PayFace.Input -> onClose()
                            is PayFace.Confirm -> face = PayFace.Input
                            is PayFace.Paying -> Unit
                            is PayFace.Done -> onClose()
                            is PayFace.Failed -> face = PayFace.Input
                        }
                    }) {
                        Icon(Icons.Outlined.Close, contentDescription = "Close")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        TwoFaceScreen(
            targetState = face,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            forward = { initial, target -> faceOrdinal(target) >= faceOrdinal(initial) },
            label = "send-lightning-face",
        ) { current ->
            when (current) {
                PayFace.Input -> InputFace(
                    input = input,
                    onInputChange = { input = it; errorText = null },
                    onPaste = {
                        val clip = clipboard.getText()?.text
                        if (!clip.isNullOrBlank()) input = clip
                    },
                    onContinue = { decode(input) },
                    errorText = errorText,
                )

                is PayFace.Confirm -> ConfirmFace(
                    decoded = current.decoded,
                    amountInput = amount,
                    onAmountChange = { amount = it },
                    activeMintName = walletState.activeMint?.name ?: "No mint",
                    balanceText = formatter.formatWalletSats(walletState.balance, settings.useBitcoinSymbol),
                    onPay = {
                        val explicitAmount = amount.toLongOrNull()
                        if (current.amount == null && (explicitAmount == null || explicitAmount <= 0L)) {
                            errorText = "Enter an amount."
                            return@ConfirmFace
                        }
                        errorText = null
                        face = PayFace.Paying(current.raw)
                        scope.launch {
                            try {
                                val mintUrl = walletState.activeMint?.url
                                val effectiveAmount = current.amount ?: explicitAmount
                                if (current.decoded is PaymentRequestDecodeResult.CashuPaymentRequest) {
                                    walletManager.payCashuPaymentRequest(current.raw, effectiveAmount, mintUrl)
                                    face = PayFace.Done(result = null)
                                } else {
                                    val quote = walletManager.createMeltQuote(
                                        request = current.raw,
                                        amountSats = effectiveAmount,
                                        preferredMintURL = mintUrl,
                                    )
                                    val result = walletManager.meltTokens(quote.id, mintUrl)
                                    face = PayFace.Done(result = result)
                                }
                            } catch (t: Throwable) {
                                face = PayFace.Failed(t.message ?: "Payment failed.")
                            }
                        }
                    },
                    errorText = errorText,
                )

                // All pay flows resolve through the shared full-screen terminal.
                is PayFace.Paying -> PaymentStatusScreen(
                    phase = PaymentStatusPhase.Processing,
                    title = "Sending payment…",
                )
                is PayFace.Done -> PaymentStatusScreen(
                    phase = PaymentStatusPhase.Success,
                    title = "Payment sent",
                    detail = current.result?.let { r ->
                        buildString {
                            append("${r.amount} sat")
                            if (r.feePaid > 0L) append(" · fee ${r.feePaid} sat")
                        }
                    },
                    onDone = onClose,
                )
                is PayFace.Failed -> PaymentStatusScreen(
                    phase = PaymentStatusPhase.Failure,
                    title = "Payment failed",
                    detail = current.reason,
                    doneLabel = "Try again",
                    onDone = { face = PayFace.Input },
                )
            }
        }
    }
}

private fun faceOrdinal(face: PayFace): Int = when (face) {
    PayFace.Input -> 0
    is PayFace.Confirm -> 1
    is PayFace.Paying -> 2
    is PayFace.Done -> 3
    is PayFace.Failed -> 3
}

private fun PaymentRequestDecodeResult.knownAmountSats(): Long? = when (this) {
    is PaymentRequestDecodeResult.Bolt11 -> amountSats
    is PaymentRequestDecodeResult.Bolt12 -> amountSats
    is PaymentRequestDecodeResult.CashuPaymentRequest -> summary.amount.takeIf { summary.isSatUnit }
    is PaymentRequestDecodeResult.LightningAddress -> null
    is PaymentRequestDecodeResult.Onchain -> null
    PaymentRequestDecodeResult.Unrecognized -> null
}

@Composable
private fun InputFace(
    input: String,
    onInputChange: (String) -> Unit,
    onPaste: () -> Unit,
    onContinue: () -> Unit,
    errorText: String?,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = CashuTheme.spacing.comfortable)
            .imePadding(),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default),
    ) {
        Text(
            text = "Paste a Lightning invoice, BOLT12 offer, on-chain address, or Lightning address.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        CashuTextField(
            value = input,
            onValueChange = onInputChange,
            modifier = Modifier
                .fillMaxWidth()
                .height(DESTINATION_FIELD_HEIGHT),
            label = "Destination",
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None),
        )
        GhostButton(text = "Paste from clipboard", onClick = onPaste)
        if (errorText != null) {
            InlineNotice(text = errorText)
        }
        Spacer(Modifier.weight(1f, fill = true))
        PrimaryButton(
            text = "Continue",
            onClick = onContinue,
            enabled = input.isNotBlank(),
        )
        Spacer(Modifier.navigationBarsPadding())
    }
}

/**
 * Confirm layout follows the iOS PayFlowScaffold order: mint at the top
 * accessory (never a bottom "From" row), the amount as the hero, then the
 * destination details, with the Pay footer firing immediately — no confirm
 * dialog, no hold-to-pay.
 */
@Composable
private fun ConfirmFace(
    decoded: PaymentRequestDecodeResult,
    amountInput: String,
    onAmountChange: (String) -> Unit,
    activeMintName: String,
    balanceText: String,
    onPay: () -> Unit,
    errorText: String?,
) {
    val knownAmount = decoded.knownAmountSats()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(
                horizontal = CashuTheme.spacing.comfortable,
                vertical = CashuTheme.spacing.default,
            ),
        verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.comfortable),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Top accessory: the paying mint + its spendable balance.
        Text(
            text = activeMintName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Balance $balanceText",
            style = MaterialTheme.typography.bodySmall.withMonoDigits(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (knownAmount != null) {
            AmountText(
                text = "$knownAmount",
                style = MaterialTheme.typography.displayMedium.withMonoDigits(),
            )
            Text(
                "sat",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            AmountText(
                text = amountInput.ifEmpty { "0" },
                style = MaterialTheme.typography.displayMedium.withMonoDigits(),
            )
            Text(
                "sat",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(modifier = Modifier.fillMaxWidth()) {
            InspectorRow(
                label = "Type",
                value = PaymentRequestDecoder.typeLabel(decoded),
                leadingIcon = when (decoded) {
                    is PaymentRequestDecodeResult.Onchain -> Icons.Outlined.CurrencyBitcoin
                    else -> Icons.Outlined.Bolt
                },
            )
            CanvasDivider(leadingInset = 16)
            InspectorRow(
                label = "To",
                value = PaymentRequestDecoder.shortRepresentation("", decoded),
                valueMonospaced = true,
            )
        }

        if (errorText != null) {
            InlineNotice(text = errorText)
        }

        if (knownAmount == null) {
            NumberPad(amount = amountInput, onAmountChange = onAmountChange)
        }

        Spacer(Modifier.weight(1f, fill = true))
        PrimaryButton(text = "Pay", onClick = onPay)
        Spacer(Modifier.navigationBarsPadding())
    }
}

