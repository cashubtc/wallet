package com.cashu.me.ui.journeys

import android.os.ParcelFileDescriptor
import android.provider.Settings
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cashu.me.App.MainActivity
import com.cashu.me.test.UiFailureArtifactsRule
import com.cashu.me.test.WalletJourneyRobot
import com.cashu.me.test.fixtures.AppTestFixture
import com.cashu.me.test.fixtures.FixtureMode
import com.cashu.me.test.fixtures.LaunchedFixture
import com.cashu.me.ui.testing.UiTestTags
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SheetConfigurationJourneyTest {
    @get:Rule(order = 0) val compose = createEmptyComposeRule()
    @get:Rule(order = 1) val artifacts = UiFailureArtifactsRule(compose) { launched?.close() }
    private var launched: LaunchedFixture? = null

    @Test fun standaloneMintDraftSurvivesSystemTextSizeChange() = verifyDraft(viaSend = false)

    @Test fun sendMintDraftSurvivesSystemTextSizeChange() = verifyDraft(viaSend = true)

    private fun verifyDraft(viaSend: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val originalScale = Settings.System.getFloat(context.contentResolver, Settings.System.FONT_SCALE, 1f)
        fun setFontScale(scale: Float) {
            instrumentation.uiAutomation.executeShellCommand("settings put system font_scale $scale").use {
                ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
            compose.waitUntil(10_000) { context.resources.configuration.fontScale == scale }
            compose.waitForIdle()
        }

        try {
            setFontScale(1f)
            val fixture = AppTestFixture.launch(FixtureMode.SeededWithoutMint).also { launched = it }
            lateinit var originalActivity: MainActivity
            fixture.scenario.onActivity { originalActivity = it }
            val robot = WalletJourneyRobot(compose)
            robot.awaitTag(UiTestTags.WalletScreen)
            if (viaSend) robot.tapText("Send").tapText("Add by URL")
            else robot.tapText("Mints").tapDescription("Add mint")
            val draft = "https://review.example.com"
            robot.typeIntoTag(UiTestTags.AddMintUrl, draft)
            compose.onNodeWithTag(UiTestTags.AddMintUrl).assertTextContains(draft)

            setFontScale(1.3f)

            fixture.scenario.onActivity { assertSame("Text size must not recreate the activity", originalActivity, it) }
            robot.awaitTag(UiTestTags.AddMintUrl)
            compose.onNodeWithTag(UiTestTags.AddMintUrl).assertTextContains(draft)
        } finally {
            launched?.close()
            launched = null
            setFontScale(originalScale)
        }
    }
}
