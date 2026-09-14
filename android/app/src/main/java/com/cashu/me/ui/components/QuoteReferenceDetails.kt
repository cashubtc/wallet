package com.cashu.me.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle

/** Copy the complete ID; emphasizing the suffix does not change the scanner payload. */
@Composable
fun QuoteReferenceDetails(quoteId: String, request: String = "") {
    val clipboard = LocalClipboardManager.current
    val toast = LocalConfirmationToastController.current
    Column {
        TextButton(
            onClick = {
                clipboard.setText(AnnotatedString(quoteId))
                toast?.show("Copied quote ID")
            },
            modifier = Modifier.semantics { contentDescription = "Quote ID: $quoteId. Copy the full quote ID" },
        ) {
            Column {
                Text("Quote ID", style = MaterialTheme.typography.labelSmall)
                Text(buildAnnotatedString {
                    append(quoteId.dropLast(6))
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(quoteId.takeLast(6)) }
                }, fontFamily = FontFamily.Monospace)
            }
        }
        if (request.isNotEmpty() && request != quoteId) {
            TextButton(onClick = {
                clipboard.setText(AnnotatedString(request))
                toast?.show("Copied payment request")
            }) { Text(request) }
        }
    }
}
