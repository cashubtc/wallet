package org.cashu.wallet.Core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppLoggerTest {
    @Test
    fun privacySafeMessageRedactsNostrPrivateKeys() {
        val message = AppLogger.privacySafeMessage(
            "imported nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq",
        )

        assertEquals("imported <redacted-nsec>", message)
    }

    @Test
    fun privacySafeMessageRedactsWalletConnectConnectionUris() {
        val message = AppLogger.privacySafeMessage(
            "connection nostr+walletconnect://pubkey?relay=wss%3A%2F%2Frelay.example&secret=top-secret",
        )

        assertFalse(message.contains("top-secret"))
        assertEquals("connection <redacted-nwc-uri>", message)
    }

    @Test
    fun privacySafeMessageRedactsLabeledSecrets() {
        val message = AppLogger.privacySafeMessage(
            "seed phrase: abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
        )

        assertFalse(message.contains("abandon"))
        assertEquals("seed phrase=<redacted>", message)
    }

    @Test
    fun privacySafeMessageRedactsCashuTokensUrlsAndLocalPaths() {
        val message = AppLogger.privacySafeMessage(
            "mint https://mint.example.com/private/path?x=1 token cashuAabcdefghijklmnopqrstuvwxyz0123456789 path /tmp/cashu/wallet.db",
        )

        assertFalse(message.contains("mint.example.com"))
        assertFalse(message.contains("cashuAabcdefghijklmnopqrstuvwxyz"))
        assertFalse(message.contains("/tmp/cashu"))
        assertEquals(
            "mint <redacted-url> token <redacted-cashu-token> path <redacted-path>",
            message,
        )
    }

    @Test
    fun privacySafeThrowableRedactsMessageButKeepsStack() {
        val error = IllegalStateException("failed token cashuAabcdefghijklmnopqrstuvwxyz0123456789")
        error.stackTrace = arrayOf(StackTraceElement("Example", "method", "Example.kt", 12))

        val safe = AppLogger.privacySafeThrowable(error)

        assertFalse(safe.message.orEmpty().contains("cashuAabcdefghijklmnopqrstuvwxyz"))
        assertEquals(error.stackTrace.toList(), safe.stackTrace.toList())
    }
}
