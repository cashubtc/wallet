package org.cashu.wallet.ui.components

import android.content.Context
import android.widget.Toast
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString

fun ClipboardManager.copyTextWithToast(
    context: Context,
    text: String,
    message: String = "Copied",
) {
    setText(AnnotatedString(text))
    context.showShortToast(message)
}

fun Context.showShortToast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
