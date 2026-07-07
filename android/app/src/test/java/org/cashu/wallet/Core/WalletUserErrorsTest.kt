package org.cashu.wallet.Core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletUserErrorsTest {
    @Test
    fun mapsCommonWalletFailuresToActionableCopy() {
        assertEquals(
            "Insufficient balance for this payment.",
            WalletUserErrors.message(IllegalStateException("insufficient funds in wallet")),
        )
        assertEquals(
            "This Lightning request could not be processed.",
            WalletUserErrors.message(IllegalArgumentException("bolt11 invoice parse failed")),
        )
        assertEquals(
            "The local wallet database could not be opened.",
            WalletUserErrors.message(IllegalStateException("SQLite database is malformed")),
        )
    }

    @Test
    fun redactsSensitiveFallbackMessages() {
        val message = WalletUserErrors.message(
            IllegalStateException(
                "failed for cashuAabcdefghijklmnop and https://mint.example",
            ),
        )

        assertTrue(message.contains("<redacted-cashu-token>"))
        assertTrue(message.contains("<redacted-url>"))
        assertFalse(message.contains("cashuAabcdefghijklmnop"))
    }

    @Test
    fun fallsBackForBlankErrors() {
        assertEquals("Wallet operation failed.", WalletUserErrors.message(RuntimeException()))
    }
}
