package com.cashu.me.Core.Errors

import android.database.sqlite.SQLiteException
import android.os.Build
import com.cashu.me.BuildConfig
import java.io.IOException
import java.util.UUID
import org.cashudevkit.FfiErrorInfo
import org.cashudevkit.FfiErrorSeverity
import org.cashudevkit.FfiException
import org.cashudevkit.NostrErrorReport
import org.cashudevkit.NostrReportReceipt
import org.cashudevkit.getErrorInfo
import org.cashudevkit.isValidNostrErrorReportNprofile
import org.cashudevkit.prepareNostrErrorReport
import org.cashudevkit.sendNostrErrorReport

enum class AppErrorCauseType { FfiCdk, FfiInternal, Network, Database, Native }

/** One application error contract for FFI and Android failures. */
data class AppError(
    val info: FfiErrorInfo,
    val reportId: String,
    val timestamp: ULong,
    val operation: String,
    val causeType: AppErrorCauseType,
    val cause: Throwable? = null,
) {
    val isReportable: Boolean get() = info.severity == FfiErrorSeverity.ERROR

    fun preparedReport(userNote: String? = null): NostrErrorReport =
        prepareNostrErrorReport(
            NostrErrorReport(
                schemaVersion = 0u,
                reportId = reportId,
                createdAt = timestamp,
                appName = "cashu.me",
                appVersion = BuildConfig.VERSION_NAME,
                appBuild = BuildConfig.VERSION_CODE.toString(),
                platform = "android",
                osVersion = Build.VERSION.RELEASE,
                operation = operation,
                errorCode = info.code,
                userMessage = info.userMessage,
                technicalMessage = info.technicalMessage,
                userNote = userNote,
            ),
        )

    companion object {
        fun from(error: Throwable, operation: String): AppError {
            val (info, causeType) = when (error) {
                is FfiException.Cdk -> getErrorInfo(error.code, error.errorMessage) to AppErrorCauseType.FfiCdk
                is FfiException.Internal -> getErrorInfo(null, error.errorMessage) to AppErrorCauseType.FfiInternal
                is IOException -> getErrorInfo(null, "Network error: ${safeDetail(error)}") to AppErrorCauseType.Network
                is SQLiteException -> getErrorInfo(null, "Database error: ${safeDetail(error)}") to AppErrorCauseType.Database
                else -> getErrorInfo(null, safeDetail(error)) to AppErrorCauseType.Native
            }
            return AppError(
                info = info,
                reportId = UUID.randomUUID().toString(),
                timestamp = (System.currentTimeMillis() / 1_000L).toULong(),
                operation = operation,
                causeType = causeType,
                cause = error,
            )
        }

        fun fromMessage(message: String, operation: String): AppError =
            from(IllegalStateException(message), operation)

        private fun safeDetail(error: Throwable): String =
            error.message?.takeIf(String::isNotBlank)
                ?: error::class.qualifiedName
                ?: "Unknown Android error"
    }
}

object NostrErrorReporter {
    fun isConfigured(): Boolean =
        BuildConfig.NOSTR_ERROR_REPORT_NPROFILE.isNotBlank() &&
            isValidNostrErrorReportNprofile(BuildConfig.NOSTR_ERROR_REPORT_NPROFILE)

    suspend fun send(error: AppError, userNote: String?): NostrReportReceipt {
        check(isConfigured()) { "Error reporting is not configured for this build." }
        return sendNostrErrorReport(
            recipientNprofile = BuildConfig.NOSTR_ERROR_REPORT_NPROFILE,
            report = error.preparedReport(userNote),
        )
    }
}
