package org.cashu.wallet.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPreferencesTest {
    @Test
    fun reducedMotionFollowsDisabledAnimatorScale() {
        assertTrue(reduceMotionEnabled(animatorsEnabled = true, animatorDurationScale = 0f))
        assertTrue(reduceMotionEnabled(animatorsEnabled = false, animatorDurationScale = 1f))
    }

    @Test
    fun reducedMotionStaysOffWhenSystemAnimatorsAreEnabled() {
        assertFalse(reduceMotionEnabled(animatorsEnabled = true, animatorDurationScale = 1f))
        assertFalse(reduceMotionEnabled(animatorsEnabled = true, animatorDurationScale = 0.5f))
    }
}
