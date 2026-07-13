package com.cashu.me.Core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression: restoring a cashu.me seed in the APK then adding the mint from
 * Mints (instead of the restore-mints wizard) used to leave balance at 0,
 * because NUT-09 only ran inside restoreFromMint. addMint must trigger restore.
 */
class RestoreProofsAfterAddMintTest {
    @Test
    fun runsRestoreForTheAddedMintUrl() = runBlocking {
        var restored: String? = null

        restoreProofsAfterAddingMint(
            mintUrl = "https://mint.example.com",
            restoreMint = { restored = it },
        )

        assertEquals("https://mint.example.com", restored)
    }

    @Test
    fun swallowsRestoreFailuresSoAddMintStillSucceeds() = runBlocking {
        var reported: Throwable? = null

        restoreProofsAfterAddingMint(
            mintUrl = "https://mint.example.com",
            restoreMint = { throw IllegalStateException("mint offline") },
            onRestoreFailed = { reported = it },
        )

        assertEquals("mint offline", reported?.message)
    }

    @Test
    fun doesNotReportWhenRestoreSucceeds() = runBlocking {
        var reported: Throwable? = null
        var restored = false

        restoreProofsAfterAddingMint(
            mintUrl = "https://mint.example.com",
            restoreMint = { restored = true },
            onRestoreFailed = { reported = it },
        )

        assertTrue(restored)
        assertNull(reported)
    }
}
