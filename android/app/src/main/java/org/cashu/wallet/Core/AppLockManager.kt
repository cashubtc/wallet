package org.cashu.wallet.Core

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine

data class AppLockState(
    val isLocked: Boolean = false,
    val isAuthenticating: Boolean = false,
    val isObscured: Boolean = false,
    val isAvailable: Boolean = false,
)

class AppLockManager(
    context: Context,
    private val settingsManager: SettingsManager,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    private val appContext = context.applicationContext
    private val gracePeriodMillis = 30_000L
    private val authenticators =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    private val mutableState = MutableStateFlow(
        AppLockState(isAvailable = canAuthenticate()),
    )
    val state: StateFlow<AppLockState> = mutableState.asStateFlow()

    private var backgroundedAtMillis: Long? = null
    private var authenticatedSessionStarted = false

    fun startAuthenticatedSession() {
        if (authenticatedSessionStarted) return
        authenticatedSessionStarted = true
        val available = refreshAvailability()
        if (settingsManager.state.value.appLockEnabled && available) {
            lock()
        }
    }

    fun endAuthenticatedSession() {
        authenticatedSessionStarted = false
        backgroundedAtMillis = null
        mutableState.value = AppLockState(isAvailable = canAuthenticate())
    }

    fun setEnabled(enabled: Boolean) {
        if (!enabled) {
            unlock()
            return
        }
        refreshAvailability()
    }

    fun appResignedActive() {
        val current = mutableState.value
        if (!settingsManager.state.value.appLockEnabled || current.isAuthenticating) return
        if (backgroundedAtMillis == null) backgroundedAtMillis = nowMillis()
        mutableState.value = current.copy(isObscured = true)
    }

    fun appBecameActive() {
        val current = mutableState.value
        if (!settingsManager.state.value.appLockEnabled || current.isAuthenticating) return
        if (current.isLocked) {
            mutableState.value = current.copy(isObscured = true)
            return
        }
        val shouldRelock = backgroundedAtMillis?.let { nowMillis() - it >= gracePeriodMillis } == true
        if (shouldRelock) {
            lock()
        } else {
            mutableState.value = current.copy(isObscured = false)
        }
        backgroundedAtMillis = null
    }

    fun refreshAvailability(): Boolean {
        val available = canAuthenticate()
        val current = mutableState.value
        mutableState.value = current.copy(isAvailable = available)
        if (!available && settingsManager.state.value.appLockEnabled) {
            AppLogger.security.info("App Lock authentication unavailable; wallet remains unlocked")
            unlock()
        }
        return available
    }

    suspend fun authenticate(
        activity: FragmentActivity?,
        reason: String = "Unlock your wallet",
    ): Boolean {
        if (mutableState.value.isAuthenticating) return false
        if (!refreshAvailability()) {
            unlock()
            return true
        }
        if (activity == null) {
            AppLogger.security.error("Authentication unavailable: no FragmentActivity")
            return false
        }

        mutableState.value = mutableState.value.copy(isAuthenticating = true)
        return try {
            val success = prompt(activity, reason)
            if (success) unlock()
            success
        } finally {
            mutableState.value = mutableState.value.copy(isAuthenticating = false)
        }
    }

    private suspend fun prompt(activity: FragmentActivity, reason: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val executor = ContextCompat.getMainExecutor(activity)
            val prompt = BiometricPrompt(
                activity,
                executor,
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        if (continuation.isActive) continuation.resume(true)
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        if (continuation.isActive) {
                            AppLogger.security.info("Authentication not completed")
                            continuation.resume(false)
                        }
                    }

                    override fun onAuthenticationFailed() {
                        AppLogger.security.info("Authentication failed")
                    }
                },
            )
            val promptInfo = BiometricPrompt.PromptInfo.Builder()
                .setTitle(reason)
                .setSubtitle("Authenticate to continue.")
                .setAllowedAuthenticators(authenticators)
                .build()
            continuation.invokeOnCancellation { prompt.cancelAuthentication() }
            runCatching { prompt.authenticate(promptInfo) }
                .onFailure { error ->
                    AppLogger.security.error("Authentication prompt failed", error)
                    if (continuation.isActive) continuation.resume(false)
                }
        }

    private fun canAuthenticate(): Boolean =
        BiometricManager.from(appContext).canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS

    private fun lock() {
        mutableState.value = mutableState.value.copy(isLocked = true, isObscured = true)
    }

    private fun unlock() {
        backgroundedAtMillis = null
        mutableState.value = mutableState.value.copy(isLocked = false, isObscured = false)
    }
}
