package com.cashu.me.Core

import android.util.Log

object AppLogger {
    private const val prefix = "CashuWallet"
    private val nostrSecretPattern = Regex("""\bnsec1[023456789acdefghjklmnpqrstuvwxyz]+\b""", RegexOption.IGNORE_CASE)
    private val nwcUriPattern = Regex("""\bnostr\+walletconnect://[^\s,;)"']+""", RegexOption.IGNORE_CASE)
    private val cashuTokenPattern = Regex("""\bcashu[ab][a-z0-9_\-=]{16,}\b""", RegexOption.IGNORE_CASE)
    private val urlPattern = Regex("""https?://[^\s,;)"']+""", RegexOption.IGNORE_CASE)
    private val localPathPattern = Regex("""(?<![A-Za-z0-9])/(?:Users|private|data|var|tmp|storage|sdcard)/[^\s,;)"']+""")
    private val labeledSecretPattern = Regex(
        pattern = """(?i)\b(mnemonic|seed phrase|private key|secret)\s*[:=]\s*([^\s,;]+(?:\s+[^\s,;]+){0,23})""",
    )

    object wallet {
        fun info(message: String) = Log.i("$prefix.Wallet", privacySafeMessage(message))
        fun debug(message: String) = Log.d("$prefix.Wallet", privacySafeMessage(message))
        fun error(message: String, throwable: Throwable? = null) = Log.e("$prefix.Wallet", privacySafeError(message, throwable))
    }

    object security {
        fun info(message: String) = Log.i("$prefix.Security", privacySafeMessage(message))
        fun debug(message: String) = Log.d("$prefix.Security", privacySafeMessage(message))
        fun error(message: String, throwable: Throwable? = null) = Log.e("$prefix.Security", privacySafeError(message, throwable))
    }

    object network {
        fun info(message: String) = Log.i("$prefix.Network", privacySafeMessage(message))
        fun debug(message: String) = Log.d("$prefix.Network", privacySafeMessage(message))
        fun error(message: String, throwable: Throwable? = null) = Log.e("$prefix.Network", privacySafeError(message, throwable))
    }

    object ui {
        fun info(message: String) = Log.i("$prefix.UI", privacySafeMessage(message))
        fun debug(message: String) = Log.d("$prefix.UI", privacySafeMessage(message))
        fun error(message: String, throwable: Throwable? = null) = Log.e("$prefix.UI", privacySafeError(message, throwable))
    }

    internal fun privacySafeMessage(message: String): String {
        return message
            .replace(nostrSecretPattern, "<redacted-nsec>")
            .replace(nwcUriPattern, "<redacted-nwc-uri>")
            .replace(cashuTokenPattern, "<redacted-cashu-token>")
            .replace(urlPattern, "<redacted-url>")
            .replace(localPathPattern, "<redacted-path>")
            .replace(labeledSecretPattern) { match ->
                "${match.groupValues[1]}=<redacted>"
            }
    }

    internal fun privacySafeThrowable(error: Throwable): Throwable {
        val safe = RuntimeException(
            "${error::class.java.simpleName}: ${privacySafeMessage(error.message.orEmpty())}",
        )
        safe.stackTrace = error.stackTrace
        return safe
    }

    private fun privacySafeError(message: String, throwable: Throwable?): String {
        val safeMessage = privacySafeMessage(message)
        val safeThrowable = throwable ?: return safeMessage
        val throwableMessage = privacySafeMessage(safeThrowable.message.orEmpty())
        return "$safeMessage (${safeThrowable::class.java.simpleName}: $throwableMessage)"
    }
}
