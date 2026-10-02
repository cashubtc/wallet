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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
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
import com.cashu.me.Core.TransactionTechnicalDetails
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
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val confirmationToastController = LocalConfirmationToastController.current
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    // Row-derived sections render at once; the stored quote fills in after its
    // local read.
    var sections by remember(transaction) { mutableStateOf(TransactionTechnicalDetails.sections(transaction)) }
    LaunchedEffect(transaction) {
        sections = walletManager.transactionTechnicalDetails(transaction)
    }
    val explorerUrl = remember(transaction) { transaction.explorerUrl() }

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
                .testTag(UiTestTags.TransactionDetailsSheet)
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
}
