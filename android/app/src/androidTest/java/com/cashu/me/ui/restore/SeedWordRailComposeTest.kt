package com.cashu.me.ui.restore

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.cashu.me.Core.SeedPhraseEntry
import com.cashu.me.ui.setCashuContent
import com.cashu.me.ui.testing.UiTestTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class SeedWordRailComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun completedPhraseCanBeScrubbedInBothDirections() {
        val phrase = List(11) { "abandon" }.plus("about").joinToString(" ")
        val state = SeedPhraseEntryState(SeedPhraseEntry().fill(phrase).entry)
        compose.setCashuContent {
            SeedWordEntryField(state, onOutcome = {}, autoFocus = false,
                modifier = Modifier.verticalScroll(rememberScrollState()))
        }
        val rail = compose.onNodeWithTag(UiTestTags.SeedWordRail)
        rail.performTouchInput {
            down(Offset(centerX, height * .96f))
            advanceEventTime(700)
            moveTo(Offset(centerX, height * .04f), delayMillis = 400)
            up()
        }
        compose.runOnIdle {
            assertEquals(0, state.entry.index)
            assertEquals("abandon", state.entry.draft)
            assertTrue(state.isComplete)
        }
        rail.performTouchInput {
            down(Offset(centerX, height * .04f))
            advanceEventTime(700)
            moveTo(Offset(centerX, height * .96f), delayMillis = 400)
            up()
        }
        compose.runOnIdle {
            assertEquals(11, state.entry.index)
            assertEquals("about", state.entry.draft)
            assertEquals(phrase, state.phrase)
        }
    }
}
