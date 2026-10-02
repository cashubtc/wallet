package com.cashu.me.ui.history

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import kotlinx.coroutines.launch
import com.cashu.me.Core.TechnicalDetailSection
import com.cashu.me.Core.TransactionTechnicalDetails
import com.cashu.me.Models.CashuRequest
import com.cashu.me.Models.MintQuoteInfo
import com.cashu.me.Core.WalletManager
import com.cashu.me.Models.WalletTransaction
import com.cashu.me.ui.components.CashuModalBottomSheet
import com.cashu.me.ui.components.ExplorerLinkRow
import com.cashu.me.ui.components.InspectorRow
import com.cashu.me.ui.components.InspectorRowStyle
import com.cashu.me.ui.components.LocalConfirmationToastController
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.components.SecondaryButton
import com.cashu.me.ui.components.SectionHeader
import com.cashu.me.ui.components.SheetHeader
import com.cashu.me.ui.components.openInBrowser
import com.cashu.me.ui.theme.CashuTheme
import com.cashu.me.ui.testing.UiTestTags

/**
 * The identifiers behind a receipt, for support and debugging. Opened from the
 * receipt's last row as a nested full-height sheet — the description reader
 * precedent — so the receipt stays put underneath (iOS
 * `TransactionTechnicalDetailsView` parity). Reads local snapshots only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionTechnicalDetailsSheet(
    transaction: WalletTransaction,
    walletManager: WalletManager,
    onDismissRequest: () -> Unit,
) {
    val walletState by walletManager.state.collectAsState()
    val current = walletState.transactions.firstOrNull { it.id == transaction.id } ?: transaction
    var sections by remember(current) { mutableStateOf(TransactionTechnicalDetails.sections(current)) }
    LaunchedEffect(current) {
        sections = walletManager.transactionTechnicalDetails(current)
    }
    TechnicalDetailsContent(sections, TransactionTechnicalDetails.explorerUrl(current, sections), walletManager, onDismissRequest)
}

@Composable
fun RequestTechnicalDetailsSheet(
    request: CashuRequest,
    walletManager: WalletManager,
    onDismissRequest: () -> Unit,
) {
    val walletState by walletManager.state.collectAsState()
    var quote by remember(request.id, request.quoteId) { mutableStateOf<MintQuoteInfo?>(null) }
    LaunchedEffect(request) { quote = walletManager.requestMintQuoteSnapshot(request) }
    val payments = request.receivedPayments.mapIndexedNotNull { index, payment ->
        walletState.transactions.firstOrNull { it.id == payment.transactionId }?.let { index + 1 to it }
    }
    TechnicalDetailsContent(TransactionTechnicalDetails.requestSections(request, quote), null, walletManager, onDismissRequest, payments, UiTestTags.RequestDetailsSheet)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TechnicalDetailsContent(
    sections: List<TechnicalDetailSection>,
    explorerUrl: String?,
    walletManager: WalletManager,
    onDismissRequest: () -> Unit,
    payments: List<Pair<Int, WalletTransaction>> = emptyList(),
    testTag: String = UiTestTags.TransactionDetailsSheet,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val confirmationToastController = LocalConfirmationToastController.current
    var selectedPayment by remember { mutableStateOf<WalletTransaction?>(null) }
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )

    fun copy(label: String, value: String, confirmation: String) {
        scope.launch {
            clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, value)))
            confirmationToastController?.show(confirmation)
        }
    }

    CashuModalBottomSheet(onDismissRequest = onDismissRequest, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .testTag(testTag)
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = CashuTheme.spacing.comfortable)
                .padding(bottom = CashuTheme.spacing.section),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.section),
        ) {
            SheetHeader(title = "Details")
            sections.forEachIndexed { index, section ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(
                        text = section.title,
                        contentPadding = PaddingValues(
                            start = CashuTheme.spacing.comfortable,
                            end = CashuTheme.spacing.comfortable,
                            bottom = CashuTheme.spacing.snug,
                        ),
                    )
                    section.rows.forEach { row ->
                        InspectorRow(
                            style = InspectorRowStyle.History,
                            label = row.label,
                            value = row.value,
                            valueMonospaced = row.copyable && row.label != "Mint",
                            onClick = if (row.copyable) {
                                {
                                    copy(row.label, row.fullValue, TransactionTechnicalDetails.copyConfirmation(row.label))
                                }
                            } else null,
                            trailingIcon = if (row.copyable) Icons.Outlined.ContentCopy else null,
                        )
                    }
                    if (index == sections.lastIndex && explorerUrl != null) {
                        ExplorerLinkRow(
                            onClick = { context.openInBrowser(explorerUrl) },
                            style = InspectorRowStyle.History,
                        )
                    }
                }
            }
            if (payments.isNotEmpty()) {
                Column {
                    SectionHeader(text = "Payment details")
                    payments.forEach { (number, payment) ->
                        InspectorRow(
                            style = InspectorRowStyle.History,
                            label = "Payment $number", value = "",
                            onClick = { selectedPayment = payment },
                            trailingIcon = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                            modifier = Modifier.testTag("cashu.history.details.payment.${payment.id}"),
                        )
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.default)) {
                SecondaryButton(
                    text = "Copy all",
                    compact = true,
                    onClick = {
                        copy("Details", TransactionTechnicalDetails.copyAllText(sections), "Copied details")
                    },
                    modifier = Modifier.testTag(UiTestTags.HistoryTransactionDetailsCopyAll),
                )
                PrimaryButton(
                    "Done",
                    onClick = {
                        scope.launch { sheetState.hide() }.invokeOnCompletion { onDismissRequest() }
                    },
                )
            }
        }
    }
    selectedPayment?.let { payment ->
        TransactionTechnicalDetailsSheet(payment, walletManager, onDismissRequest = { selectedPayment = null })
    }
}
