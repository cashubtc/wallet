package com.cashu.me.ui.receive.nfc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ListItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import com.cashu.me.Models.CashuRequest
import com.cashu.me.Models.MintInfo
import com.cashu.me.ui.components.CashuTextField
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.components.SheetHeader
import com.cashu.me.ui.theme.CashuTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NfcRequestEditorSheet(
    request: CashuRequest,
    availableMints: List<MintInfo>,
    activeMintUrl: String?,
    onDismiss: () -> Unit,
    onSave: (amount: Long?, memo: String?, acceptAnyMint: Boolean, mintUrl: String?) -> Unit,
) {
    var amount by remember(request.id) { mutableStateOf(request.amount?.toString().orEmpty()) }
    var memo by remember(request.id) { mutableStateOf(request.memo.orEmpty()) }
    var acceptAnyMint by remember(request.id) { mutableStateOf(request.mints.isEmpty()) }
    var selectedMintUrl by remember(request.id) {
        mutableStateOf(request.mints.firstOrNull() ?: activeMintUrl ?: availableMints.firstOrNull()?.url)
    }
    val parsedAmount = amount.trim().takeIf(String::isNotEmpty)?.toLongOrNull()
    val amountInvalid = amount.isNotBlank() && (parsedAmount == null || parsedAmount <= 0)

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = CashuTheme.spacing.comfortable),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.comfortable),
        ) {
            SheetHeader(
                title = "Edit Cashu Request",
                navigationIcon = Icons.Outlined.Close,
                navigationContentDescription = "Close",
                onNavigationClick = onDismiss,
            )
            Column(
                modifier = Modifier.padding(horizontal = CashuTheme.spacing.page),
                verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.comfortable),
            ) {
                CashuTextField(
                    value = amount,
                    onValueChange = { next -> amount = next.filter(Char::isDigit) },
                    label = "Amount (${request.unit})",
                    placeholder = "Any amount",
                    supportingText = if (amountInvalid) "Enter a positive whole amount or leave it empty." else null,
                    isError = amountInvalid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                CashuTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = "Description",
                    placeholder = "Optional",
                    modifier = Modifier.fillMaxWidth(),
                )
                ListItem(
                    headlineContent = { Text("Accept any mint") },
                    supportingContent = {
                        Text(
                            if (acceptAnyMint) {
                                val active = availableMints.firstOrNull { it.url == activeMintUrl }
                                "Foreign-mint payments are converted and settled to ${active?.name ?: activeMintUrl ?: "your active mint"}."
                            } else {
                                "Only direct payments from the selected mint are accepted."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    },
                    trailingContent = {
                        Switch(checked = acceptAnyMint, onCheckedChange = { acceptAnyMint = it })
                    },
                )
                if (!acceptAnyMint) {
                    Column(verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug)) {
                        Text("Accepted mint", style = MaterialTheme.typography.labelLarge)
                        availableMints.forEach { mint ->
                            ListItem(
                                headlineContent = { Text(mint.name.ifBlank { mint.url }) },
                                supportingContent = { Text(mint.url, style = MaterialTheme.typography.bodySmall) },
                                trailingContent = {
                                    RadioButton(
                                        selected = selectedMintUrl == mint.url,
                                        onClick = { selectedMintUrl = mint.url },
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                PrimaryButton(
                    text = "Save request",
                    enabled = !amountInvalid && (acceptAnyMint || selectedMintUrl != null),
                    onClick = {
                        onSave(
                            parsedAmount,
                            memo.trim().takeIf(String::isNotEmpty),
                            acceptAnyMint,
                            selectedMintUrl,
                        )
                    },
                )
            }
        }
    }
}
