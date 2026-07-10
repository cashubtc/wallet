package org.cashu.wallet.Core

internal const val AppLockGracePeriodMillis = 30_000L

internal data class AppLockRuntime(
    val state: AppLockState,
    val backgroundedAtMillis: Long? = null,
    val authenticatedSessionStarted: Boolean = false,
)

internal object AppLockPolicy {
    fun initial(isAvailable: Boolean): AppLockRuntime =
        AppLockRuntime(state = AppLockState(isAvailable = isAvailable))

    fun startAuthenticatedSession(
        runtime: AppLockRuntime,
        appLockEnabled: Boolean,
        isAvailable: Boolean,
    ): AppLockRuntime {
        if (runtime.authenticatedSessionStarted) return runtime
        val state = runtime.state.copy(isAvailable = isAvailable)
        return runtime.copy(
            state = if (appLockEnabled && isAvailable) state.locked() else state,
            authenticatedSessionStarted = true,
        )
    }

    fun endAuthenticatedSession(isAvailable: Boolean): AppLockRuntime =
        initial(isAvailable)

    fun setEnabled(
        runtime: AppLockRuntime,
        enabled: Boolean,
        isAvailable: Boolean = runtime.state.isAvailable,
    ): AppLockRuntime =
        if (enabled) {
            runtime.copy(state = runtime.state.copy(isAvailable = isAvailable))
        } else {
            runtime.copy(
                state = runtime.state.copy(isAvailable = isAvailable).unlocked(),
                backgroundedAtMillis = null,
            )
        }

    fun appResignedActive(
        runtime: AppLockRuntime,
        appLockEnabled: Boolean,
        nowMillis: Long,
    ): AppLockRuntime {
        val current = runtime.state
        if (!appLockEnabled || current.isAuthenticating) return runtime
        return runtime.copy(
            state = current.copy(isObscured = true),
            backgroundedAtMillis = runtime.backgroundedAtMillis ?: nowMillis,
        )
    }

    fun appBecameActive(
        runtime: AppLockRuntime,
        appLockEnabled: Boolean,
        nowMillis: Long,
        gracePeriodMillis: Long = AppLockGracePeriodMillis,
    ): AppLockRuntime {
        val current = runtime.state
        if (!appLockEnabled || current.isAuthenticating) return runtime
        if (current.isLocked) {
            return runtime.copy(state = current.copy(isObscured = true))
        }
        val shouldRelock = runtime.backgroundedAtMillis
            ?.let { nowMillis - it >= gracePeriodMillis } == true
        val state = if (shouldRelock) current.locked() else current.copy(isObscured = false)
        return runtime.copy(state = state, backgroundedAtMillis = null)
    }

    fun refreshAvailability(
        runtime: AppLockRuntime,
        appLockEnabled: Boolean,
        isAvailable: Boolean,
    ): AppLockRuntime {
        val availableState = runtime.state.copy(isAvailable = isAvailable)
        return runtime.copy(
            state = if (!isAvailable && appLockEnabled) availableState.unlocked() else availableState,
            backgroundedAtMillis = if (!isAvailable && appLockEnabled) null else runtime.backgroundedAtMillis,
        )
    }

    fun authenticating(runtime: AppLockRuntime, isAuthenticating: Boolean): AppLockRuntime =
        runtime.copy(state = runtime.state.copy(isAuthenticating = isAuthenticating))

    fun authenticated(runtime: AppLockRuntime): AppLockRuntime =
        runtime.copy(state = runtime.state.unlocked(), backgroundedAtMillis = null)

    private fun AppLockState.locked(): AppLockState =
        copy(isLocked = true, isObscured = true)

    private fun AppLockState.unlocked(): AppLockState =
        copy(isLocked = false, isObscured = false)
}
