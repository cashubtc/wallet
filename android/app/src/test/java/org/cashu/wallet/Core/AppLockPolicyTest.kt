package org.cashu.wallet.Core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockPolicyTest {
    @Test
    fun enabledAvailableSessionStartsLockedAndObscured() {
        val runtime = AppLockPolicy.startAuthenticatedSession(
            runtime = AppLockPolicy.initial(isAvailable = true),
            appLockEnabled = true,
            isAvailable = true,
        )

        assertTrue(runtime.authenticatedSessionStarted)
        assertTrue(runtime.state.isLocked)
        assertTrue(runtime.state.isObscured)
        assertTrue(runtime.state.isAvailable)
    }

    @Test
    fun enabledUnavailableSessionRemainsUnlocked() {
        val runtime = AppLockPolicy.startAuthenticatedSession(
            runtime = AppLockPolicy.initial(isAvailable = false),
            appLockEnabled = true,
            isAvailable = false,
        )

        assertTrue(runtime.authenticatedSessionStarted)
        assertFalse(runtime.state.isLocked)
        assertFalse(runtime.state.isObscured)
        assertFalse(runtime.state.isAvailable)
    }

    @Test
    fun disablingAppLockUnlocksAndClearsBackgroundTime() {
        val locked = AppLockRuntime(
            state = AppLockState(isLocked = true, isObscured = true, isAvailable = true),
            backgroundedAtMillis = 10,
            authenticatedSessionStarted = true,
        )

        val runtime = AppLockPolicy.setEnabled(locked, enabled = false)

        assertFalse(runtime.state.isLocked)
        assertFalse(runtime.state.isObscured)
        assertEquals(null, runtime.backgroundedAtMillis)
    }

    @Test
    fun foregroundWithinGraceUnobscuresWithoutLocking() {
        val backgrounded = AppLockPolicy.appResignedActive(
            runtime = AppLockRuntime(state = AppLockState(isAvailable = true)),
            appLockEnabled = true,
            nowMillis = 1_000,
        )

        val runtime = AppLockPolicy.appBecameActive(
            runtime = backgrounded,
            appLockEnabled = true,
            nowMillis = 1_000 + AppLockGracePeriodMillis - 1,
        )

        assertFalse(runtime.state.isLocked)
        assertFalse(runtime.state.isObscured)
        assertEquals(null, runtime.backgroundedAtMillis)
    }

    @Test
    fun foregroundAfterGraceLocksAndStaysObscured() {
        val backgrounded = AppLockPolicy.appResignedActive(
            runtime = AppLockRuntime(state = AppLockState(isAvailable = true)),
            appLockEnabled = true,
            nowMillis = 1_000,
        )

        val runtime = AppLockPolicy.appBecameActive(
            runtime = backgrounded,
            appLockEnabled = true,
            nowMillis = 1_000 + AppLockGracePeriodMillis,
        )

        assertTrue(runtime.state.isLocked)
        assertTrue(runtime.state.isObscured)
        assertEquals(null, runtime.backgroundedAtMillis)
    }

    @Test
    fun unavailableRefreshUnlocksWhenSettingIsEnabled() {
        val locked = AppLockRuntime(
            state = AppLockState(isLocked = true, isObscured = true, isAvailable = true),
            backgroundedAtMillis = 1_000,
        )

        val runtime = AppLockPolicy.refreshAvailability(
            runtime = locked,
            appLockEnabled = true,
            isAvailable = false,
        )

        assertFalse(runtime.state.isLocked)
        assertFalse(runtime.state.isObscured)
        assertFalse(runtime.state.isAvailable)
        assertEquals(null, runtime.backgroundedAtMillis)
    }

    @Test
    fun authenticatingSuppressesLifecycleObscureAndRelock() {
        val authenticating = AppLockPolicy.authenticating(
            runtime = AppLockRuntime(state = AppLockState(isAvailable = true)),
            isAuthenticating = true,
        )

        val resigned = AppLockPolicy.appResignedActive(
            runtime = authenticating,
            appLockEnabled = true,
            nowMillis = 1_000,
        )
        val active = AppLockPolicy.appBecameActive(
            runtime = resigned,
            appLockEnabled = true,
            nowMillis = 1_000 + AppLockGracePeriodMillis,
        )

        assertTrue(active.state.isAuthenticating)
        assertFalse(active.state.isLocked)
        assertFalse(active.state.isObscured)
        assertEquals(null, active.backgroundedAtMillis)
    }
}
