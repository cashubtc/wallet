package com.cashu.me.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cashu.me.Core.Errors.AppError
import com.cashu.me.Core.Errors.NostrErrorReporter
import kotlinx.coroutines.launch
import org.cashudevkit.NostrReportReceipt

/** User-approved preview and delivery UI. Nothing in this modal is persisted. */
@Composable
fun ErrorDetailsDialog(error: AppError, onDismiss: () -> Unit) {
    var note by remember(error.reportId) { mutableStateOf("") }
    var isSending by remember(error.reportId) { mutableStateOf(false) }
    var deliveryError by remember(error.reportId) { mutableStateOf<String?>(null) }
    var receipt by remember(error.reportId) { mutableStateOf<NostrReportReceipt?>(null) }
    val scope = rememberCoroutineScope()
    val preview = remember(error, note) { error.preparedReport(note.takeIf(String::isNotBlank)) }
    val configured = remember { NostrErrorReporter.isConfigured() }

    Dialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = true),
    ) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 680.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Error details", style = MaterialTheme.typography.headlineSmall)
                Text(preview.userMessage, style = MaterialTheme.typography.bodyLarge)

                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Detail("Code / category", "${preview.errorCode ?: "—"} · ${error.info.category.name.lowercase()}")
                    Detail("Operation", preview.operation)
                    Detail("Report ID", preview.reportId)
                    Detail("App", "${preview.appName} ${preview.appVersion} (${preview.appBuild})")
                    Detail("Platform", "${preview.platform} ${preview.osVersion}")
                    Detail("Technical detail", preview.technicalMessage)

                    OutlinedTextField(
                        value = note,
                        onValueChange = { note = it.take(1_024) },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Optional note") },
                        supportingText = { Text("${note.length}/1024 · included in the preview above") },
                        minLines = 3,
                        enabled = !isSending && receipt == null,
                    )

                    if (!configured) {
                        Text(
                            "Reporting is unavailable in this build.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    deliveryError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    receipt?.let {
                        val status = if (it.failedRelays == 0u) {
                            "Report sent."
                        } else {
                            "Report sent to ${it.acceptedRelays} relay(s); ${it.failedRelays} failed."
                        }
                        Text(status, color = MaterialTheme.colorScheme.primary)
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss, enabled = !isSending, modifier = Modifier.weight(1f)) {
                        Text(if (receipt == null) "Cancel" else "Done")
                    }
                    if (receipt == null) {
                        Button(
                            onClick = {
                                isSending = true
                                deliveryError = null
                                scope.launch {
                                    try {
                                        receipt = NostrErrorReporter.send(error, note.takeIf(String::isNotBlank))
                                    } catch (_: Throwable) {
                                        // Reporting failures stay local to this modal and never create AppError UI.
                                        deliveryError = "The report could not be sent. Check your connection and retry."
                                    } finally {
                                        isSending = false
                                    }
                                }
                            },
                            enabled = configured && !isSending,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(if (isSending) "Sending…" else if (deliveryError == null) "Send Report" else "Retry")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Detail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) }
    }
}
