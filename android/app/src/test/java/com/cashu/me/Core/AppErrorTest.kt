package com.cashu.me.Core

import com.cashu.me.Core.Errors.AppError
import com.cashu.me.Core.Errors.AppErrorCauseType
import org.cashudevkit.FfiErrorSeverity
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
        assertEquals(FfiErrorSeverity.ERROR, error.info.severity)
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
    }
}
