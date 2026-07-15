package com.cashu.me.Core.Wallet

import com.cashu.me.Core.Errors.AppError
import org.cashudevkit.FfiErrorSeverity

/** Severity tier the UI should render a wallet message at (iOS ErrorSeverity). */
enum class WalletMessageSeverity { Error, Caution, Info }

/** Compatibility view of the FFI-owned error descriptor used by existing call sites. */
data class WalletMessage(
    val text: String,
    val severity: WalletMessageSeverity = WalletMessageSeverity.Error,
    val isTerminal: Boolean = false,
) {
    val isRetryable: Boolean get() = !isTerminal
}

val Throwable.appError: AppError
    get() = AppError.from(this, operation = "unknown")

val Throwable.walletMessage: WalletMessage
    get() = WalletErrorMessages.classify(this)

val Throwable.userFacingWalletMessage: String
    get() = walletMessage.text

/** Delegates all CDK and uncoded detail normalization to the shared FFI table. */
object WalletErrorMessages {
    fun classify(error: Throwable): WalletMessage = AppError.from(error, "unknown").toWalletMessage()

    fun classifyMessage(rawMessage: String): WalletMessage =
        AppError.fromMessage(rawMessage, "unknown").toWalletMessage()
}

fun AppError.toWalletMessage(): WalletMessage = WalletMessage(
    text = info.userMessage,
    severity = when (info.severity) {
        FfiErrorSeverity.ERROR -> WalletMessageSeverity.Error
        FfiErrorSeverity.CAUTION -> WalletMessageSeverity.Caution
        FfiErrorSeverity.INFO -> WalletMessageSeverity.Info
    },
    isTerminal = info.terminal,
)
