package com.cashu.me.ui.mints

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardCapitalization
import kotlinx.coroutines.launch
import com.cashu.me.Core.Errors.AppError
import com.cashu.me.Core.WalletManager
import com.cashu.me.Core.mintUrlCandidates
import com.cashu.me.Core.normalizeUserMintUrl
import com.cashu.me.ui.components.CashuTextField
import com.cashu.me.ui.components.GhostButton
import com.cashu.me.ui.components.InlineNotice
import com.cashu.me.ui.components.PrimaryButton
import com.cashu.me.ui.theme.CashuTheme

/**
 * Bottom sheet for pasting/typing a mint URL — mirrors iOS `AddMintSheet`.
 * Camera overlays sit under dialog windows, so [onScan] should dismiss this
 * sheet before opening the scanner; a successful scan reopens via
 * [initialUrl].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddMintSheet(
    walletManager: WalletManager,
    initialUrl: String = "",
    onScan: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current

    var url by remember(initialUrl) { mutableStateOf(initialUrl) }
    var nickname by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<String?>(null) }
    var operationError by remember { mutableStateOf<AppError?>(null) }
    var isAdding by remember { mutableStateOf(false) }

    fun pasteFromClipboard() {
        val candidate = clipboard.getText()?.text?.let { mintUrlCandidates(it).firstOrNull() }
        if (candidate == null) {
            validationError = "No valid mint URL in clipboard."
        } else {
            url = candidate
            validationError = null
        }
    }

    fun addMint() {
        val normalized = normalizeUserMintUrl(url)
        if (normalized == null) {
            validationError = "Enter a valid HTTPS mint URL."
            return
        }
        validationError = null
        operationError = null
        isAdding = true
        scope.launch {
            runCatching { walletManager.addMint(normalized) }
                .onSuccess {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    url = ""
                    nickname = ""
                    onDismiss()
                }
                .onFailure { operationError = AppError.from(it, "add mint") }
            isAdding = false
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = CashuTheme.spacing.comfortable)
                .navigationBarsPadding()
                .padding(bottom = CashuTheme.spacing.comfortable),
            verticalArrangement = Arrangement.spacedBy(CashuTheme.spacing.snug),
        ) {
            Text(
                text = "Add Mint",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(vertical = CashuTheme.spacing.snug),
            )

            CashuTextField(
                value = url,
                onValueChange = {
                    url = it
                    validationError = null
                    operationError = null
                },
                label = "Mint URL",
                placeholder = "https://…",
                singleLine = true,
                isError = validationError != null || operationError != null,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                ),
                trailingIcon = {
                    IconButton(onClick = onScan) {
                        Icon(
                            imageVector = Icons.Outlined.QrCodeScanner,
                            contentDescription = "Scan",
                        )
                    }
                },
            )

            CashuTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = "Nickname (optional)",
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                text = "Enter the URL of a Cashu mint to connect to it. " +
                    "This wallet is not affiliated with any mint.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            validationError?.let { InlineNotice(text = it) }
            operationError?.let { InlineNotice(error = it) }

            Spacer(modifier = Modifier.height(CashuTheme.spacing.tight))

            PrimaryButton(
                text = "Add mint",
                onClick = ::addMint,
                enabled = url.isNotBlank() && !isAdding,
                loading = isAdding,
            )
            GhostButton(
                text = "Paste URL from clipboard",
                onClick = ::pasteFromClipboard,
                enabled = !isAdding,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
