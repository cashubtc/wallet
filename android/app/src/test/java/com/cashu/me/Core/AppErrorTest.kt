package com.cashu.me.Core

import com.cashu.me.Core.Errors.AppError
import com.cashu.me.Core.Errors.AppErrorCauseType
import com.cashu.me.Core.Errors.AppErrorSeverity
import org.cashudevkit.FfiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppErrorTest {
    @Test
    fun typedFfiErrorUsesSharedMessageAndKeepsTheCashuCode() {
        val error = AppError.from(
            FfiException.Cdk(11001u, "Token Already Spent"),
            operation = "receive token",
        )

        assertEquals(11001u, error.info.code)
        assertEquals("This token was already redeemed.", error.info.userMessage)
        assertEquals(AppErrorSeverity.Error, error.info.severity)
        assertEquals(AppErrorCauseType.FfiCdk, error.causeType)
        assertTrue(error.info.terminal)
        assertFalse(error.info.retryable)
    }

    @Test
    fun nativeErrorsUseSafeFallbackAndFreshReportIds() {
        val first = AppError.from(IllegalStateException("opaque failure"), "sync")
        val second = AppError.from(IllegalStateException("opaque failure"), "sync")

        assertEquals("The wallet couldn't finish that action. Try again in a moment.", first.info.userMessage)
        assertEquals("opaque failure", first.info.technicalMessage)
        assertNotEquals(first.reportId, second.reportId)
        assertTrue(first.isReportable)
    }

    @Test
    fun preparedReportRedactsWalletSecretsAndLimitsUtf8Fields() {
        val error = AppError.from(
            IllegalStateException(
                "seed phrase: abandon ability able about above absent absorb abstract absurd abuse access accident " +
                    "cashuAeyJsb25nX3NlY3JldF90b2tlbiI6dHJ1ZX0 https://mint.example/path /Users/alice/wallet.db",
            ),
            "receive https://mint.example/invoice",
        )

        val report = error.preparedReport("nsec1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqq " + "🔒".repeat(2_000))

        assertFalse(report.technicalMessage.contains("abandon ability"))
        assertFalse(report.technicalMessage.contains("cashuA"))
        assertFalse(report.technicalMessage.contains("mint.example"))
        assertFalse(report.technicalMessage.contains("/Users/alice"))
        assertEquals("receive <redacted-url>", report.operation)
        assertTrue(report.userNote!!.toByteArray().size <= 1_024)
    }
}
