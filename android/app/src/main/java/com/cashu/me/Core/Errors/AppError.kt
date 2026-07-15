package com.cashu.me.Core.Errors

import android.database.sqlite.SQLiteException
import android.os.Build
import com.cashu.me.BuildConfig
import com.cashu.me.Core.NostrErrorTransport
import java.io.IOException
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.cashudevkit.FfiException

enum class AppErrorCauseType { FfiCdk, FfiInternal, Network, Database, Native }
enum class AppErrorCategory { Protocol, Internal }
enum class AppErrorSeverity { Error, Caution, Info }

data class AppErrorInfo(
    val code: UInt?,
    val userMessage: String,
    val technicalMessage: String,
    val category: AppErrorCategory,
    val severity: AppErrorSeverity,
    val retryable: Boolean,
    val terminal: Boolean,
    val reportable: Boolean,
)

@Serializable
data class NostrErrorReport(
    @SerialName("schema_version") val schemaVersion: UInt = 1u,
    @SerialName("report_id") val reportId: String,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("app_name") val appName: String,
    @SerialName("app_version") val appVersion: String,
    @SerialName("app_build") val appBuild: String,
    val platform: String,
    @SerialName("os_version") val osVersion: String,
    val operation: String,
    @SerialName("error_code") val errorCode: UInt?,
    @SerialName("user_message") val userMessage: String,
    @SerialName("technical_message") val technicalMessage: String,
    @SerialName("user_note") val userNote: String?,
)

data class NostrReportReceipt(
    val eventId: String,
    val acceptedRelays: UInt,
    val failedRelays: UInt,
)

/** One application error contract for FFI and Android failures. */
data class AppError(
    val info: AppErrorInfo,
    val reportId: String,
    val timestamp: Long,
    val operation: String,
    val causeType: AppErrorCauseType,
    val cause: Throwable? = null,
) {
    val isReportable: Boolean get() = info.reportable

    fun preparedReport(userNote: String? = null): NostrErrorReport = NostrErrorReport(
        reportId = reportId,
        createdAt = timestamp,
        appName = "cashu.me",
        appVersion = BuildConfig.VERSION_NAME,
        appBuild = BuildConfig.VERSION_CODE.toString(),
        platform = "android",
        osVersion = Build.VERSION.RELEASE ?: "unknown",
        operation = ErrorSanitizer.text(operation, 128),
        errorCode = info.code,
        userMessage = ErrorSanitizer.text(info.userMessage, 512),
        technicalMessage = ErrorSanitizer.text(info.technicalMessage, 2_048),
        userNote = userNote?.let { ErrorSanitizer.text(it, 1_024) }?.takeIf(String::isNotBlank),
    )

    companion object {
        fun from(error: Throwable, operation: String): AppError {
            val causeType = when (error) {
                is FfiException.Cdk -> AppErrorCauseType.FfiCdk
                is FfiException.Internal -> AppErrorCauseType.FfiInternal
                is SQLiteException -> AppErrorCauseType.Database
                is IOException -> AppErrorCauseType.Network
                else -> AppErrorCauseType.Native
            }
            return AppError(
                info = ErrorInfoResolver.resolve(error, causeType),
                reportId = UUID.randomUUID().toString().lowercase(),
                timestamp = System.currentTimeMillis() / 1_000L,
                operation = operation,
                causeType = causeType,
                cause = error,
            )
        }

        fun fromMessage(message: String, operation: String): AppError =
            from(IllegalStateException(message), operation)
    }
}

/** Compatibility boundary to be replaced by CDK's FFI resolver once it is released. */
object ErrorInfoResolver {
    private const val fallback = "The wallet couldn't finish that action. Try again in a moment."

    fun resolve(error: Throwable, causeType: AppErrorCauseType): AppErrorInfo {
        val code = (error as? FfiException.Cdk)?.code
        val detail = when (error) {
            is FfiException.Cdk -> error.errorMessage
            is FfiException.Internal -> error.errorMessage
            else -> error.message ?: error::class.qualifiedName.orEmpty()
        }
        return resolve(code, detail, causeType)
    }

    fun resolve(code: UInt?, detail: String, causeType: AppErrorCauseType): AppErrorInfo {
        val safeDetail = ErrorSanitizer.text(detail.ifBlank { "Unknown error" }, 2_048)
        val normalized = detail.lowercase()
        val terminal = has(normalized, "already issued", "already paid", "already spent", "already redeemed")
        val severity = if (has(normalized, "unsupported", "fee exceeded", "amountless", "outside of allowed")) {
            AppErrorSeverity.Caution
        } else {
            AppErrorSeverity.Error
        }
        val userMessage = when {
            has(normalized, "already being minted") -> "This payment is already being claimed. Give it a moment and refresh."
            has(normalized, "already issued", "already minted") -> "Ecash has already been issued for this quote."
            has(normalized, "already paid", "invoice already paid") -> "This invoice has already been paid."
            has(normalized, "token already spent", "proof already used", "already redeemed") -> "This token was already redeemed."
            has(normalized, "insufficient", "not enough", "no spendable", "balance too low") -> "Not enough balance."
            has(normalized, "expired quote", "quote expired", "invoice expired") -> "This quote has expired. Create a new request."
            has(normalized, "pending quote", "payment pending", "quote pending") -> "The payment is still pending. Try again shortly."
            has(normalized, "duplicate outputs", "already signed") -> "The wallet fell out of sync with this mint. Try again to resync."
            has(normalized, "invalid proof", "could not verify", "dleq") -> "This token could not be verified. Ask the sender for a new token."
            has(normalized, "unsupported unit") -> "This mint doesn't support that unit. Choose another mint."
            has(normalized, "unsupported payment method") -> "This mint doesn't support that payment method. Choose another mint."
            has(normalized, "invalid payment request", "invalid invoice") -> "That payment request isn't valid. Check it and try again."
            has(normalized, "timeout", "timed out") -> "The mint took too long to respond. Check your connection and try again."
            has(normalized, "network", "connection", "connect", "dns", "offline", "tls", "certificate") ->
                "Couldn't reach the mint. Check your connection and try again."
            has(normalized, "sqlite", "database", "corrupt", "malformed") ->
                "The wallet database could not be opened. Restart the app and try again."
            causeType == AppErrorCauseType.FfiCdk && safeDetail.isNotBlank() -> safeDetail
            else -> fallback
        }
        val expected = terminal || severity != AppErrorSeverity.Error || has(
            normalized,
            "insufficient", "not enough", "expired", "pending", "invalid invoice",
            "unsupported", "timeout", "offline", "network", "connection", "cancel",
        )
        val reportable = !expected && causeType in setOf(
            AppErrorCauseType.FfiInternal,
            AppErrorCauseType.Database,
            AppErrorCauseType.Native,
        )
        return AppErrorInfo(
            code = code,
            userMessage = userMessage,
            technicalMessage = safeDetail,
            category = if (causeType == AppErrorCauseType.FfiCdk) AppErrorCategory.Protocol else AppErrorCategory.Internal,
            severity = severity,
            retryable = !terminal,
            terminal = terminal,
            reportable = reportable,
        )
    }

    private fun has(value: String, vararg terms: String): Boolean = terms.any(value::contains)
}

internal object ErrorSanitizer {
    private val nsec = Regex("""\bnsec1[023456789acdefghjklmnpqrstuvwxyz]+\b""", RegexOption.IGNORE_CASE)
    private val nwc = Regex("""\bnostr\+walletconnect://[^\s,;)\"']+""", RegexOption.IGNORE_CASE)
    private val token = Regex("""\bcashu[ab][a-z0-9_\-=]{16,}\b""", RegexOption.IGNORE_CASE)
    private val url = Regex("""https?://[^\s,;)\"']+""", RegexOption.IGNORE_CASE)
    private val path = Regex("""(?<![A-Za-z0-9])/(?:Users|private|data|var|tmp|storage|sdcard)/[^\s,;)\"']+""")
    private val secret = Regex("""(?i)\b(mnemonic|seed phrase|private key|secret)\s*[:=]\s*([^\s,;]+(?:\s+[^\s,;]+){0,23})""")

    fun text(value: String, maxBytes: Int): String {
        var safe = value
            .replace(nsec, "<redacted-nsec>")
            .replace(nwc, "<redacted-nwc-uri>")
            .replace(token, "<redacted-cashu-token>")
            .replace(url, "<redacted-url>")
            .replace(path, "<redacted-path>")
            .replace(secret) { "${it.groupValues[1]}=<redacted>" }
            .trim()
        while (safe.toByteArray().size > maxBytes && safe.isNotEmpty()) safe = safe.dropLast(1)
        return safe
    }
}

object NostrErrorReporter {
    fun isConfigured(): Boolean = NostrErrorTransport.parseNprofile(BuildConfig.NOSTR_ERROR_REPORT_NPROFILE) != null

    suspend fun send(error: AppError, userNote: String?): NostrReportReceipt {
        val recipient = NostrErrorTransport.parseNprofile(BuildConfig.NOSTR_ERROR_REPORT_NPROFILE)
            ?: error("Error reporting is not configured for this build.")
        return NostrErrorTransport.send(recipient, error.preparedReport(userNote))
    }
}
