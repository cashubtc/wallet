package com.cashu.me.ui.restore

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.cashu.me.test.fixtures.AppTestFixture
import com.cashu.me.test.fixtures.FixtureMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MintBackupSearchTest {
    @Test
    fun nativeSeedReplacementKeepsDiscoverySettingsAndProtectsUnfinishedRestore() = runBlocking {
        val app = AppTestFixture.launch(FixtureMode.LiveSeededWithoutMint)
        val manager = app.container.walletManager
        try {
            val previousPhrase = checkNotNull(manager.backupMnemonic())
            val restoredPhrase = "legal winner thank year wave sausage worth useful legal winner thank yellow"
            app.container.settingsManager.setUseWebsockets(false)
            manager.initializeRestoredWallet(restoredPhrase)
            assertEquals(restoredPhrase, manager.backupMnemonic())
            assertFalse(app.container.settingsManager.state.value.useWebsockets)
            try {
                app.container.nostrMintBackupService.fetchBackedUpMintUrls()
                fail("The existing privacy setting must prevent relay access")
            } catch (_: com.cashu.me.Core.NostrMintBackupException.WebSocketsDisabled) { }

            try {
                manager.initializeRestoredWallet(previousPhrase)
                fail("An unfinished restore must preserve both wallet databases")
            } catch (error: IllegalStateException) {
                assertTrue(error.message.orEmpty().contains("Finish the current restore"))
            }
            assertEquals(restoredPhrase, manager.backupMnemonic())
            manager.initializeRestoredWallet(restoredPhrase)
            manager.completeRestore()
        } finally {
            manager.deleteWallet()
            app.close()
        }
    }

    @Test
    fun disabledWebsocketsCanBeRetriedWithoutClearingWalletStorage() = runBlocking {
        val app = AppTestFixture.launch(FixtureMode.SeededWithoutMint)
        try {
            val manager = app.container.walletManager
            val phraseBefore = manager.backupMnemonic()
            val staging = RestoreMintsStagingState(
                this, manager, app.container.nostrMintBackupService,
                object : ClipboardManager {
                    override fun getText(): AnnotatedString? = null
                    override fun setText(annotatedString: AnnotatedString) = Unit
                },
                object : HapticFeedback {
                    override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) = Unit
                },
            )
            app.container.settingsManager.setUseWebsockets(false)
            manager.initializeRestoredWallet(checkNotNull(phraseBefore))
            assertFalse(app.container.settingsManager.state.value.useWebsockets)
            staging.searchNostrBackup()
            yield()
            assertNotNull(staging.notice)
            assertFalse("A failed lookup must not claim that no backup exists", staging.backupSearchCompleted)
            assertTrue(staging.notice!!.contains("Settings"))

            app.container.settingsManager.setUseWebsockets(true)
            staging.searchNostrBackup()
            yield()
            assertTrue(staging.backupSearchCompleted)
            assertNull(staging.notice)
            assertEquals(phraseBefore, manager.backupMnemonic())

            // A failed retry must also clear a preceding successful empty result.
            app.container.settingsManager.setUseWebsockets(false)
            staging.searchNostrBackup()
            yield()
            assertFalse(staging.backupSearchCompleted)
        } finally {
            app.close()
        }
    }
}
