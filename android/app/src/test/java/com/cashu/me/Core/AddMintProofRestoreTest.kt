package com.cashu.me.Core

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Regression: restoring a cashu.me seed in the APK then adding the mint from
 * Mints (instead of the restore-mints wizard) used to leave balance at 0,
 * because NUT-09 only ran inside restoreFromMint. addMint must trigger restore
 * after committing the mint; the app-lifetime caller reports any failure.
 */
class AddMintProofRestoreTest {
    @Test
    fun runsRestoreForTheAddedMintUrl() = runBlocking {
        var restored: String? = null

        restoreProofsForAddedMint(
            mintUrl = "https://mint.example.com",
            restoreMint = { restored = it },
        )

        assertEquals("https://mint.example.com", restored)
    }

    @Test
    fun propagatesRestoreFailures() = runBlocking {
        try {
            restoreProofsForAddedMint(
                mintUrl = "https://mint.example.com",
                restoreMint = { throw IllegalStateException("mint offline") },
            )
            fail("Expected restore failure to propagate")
        } catch (error: IllegalStateException) {
            assertEquals("mint offline", error.message)
        }
    }

    @Test
    fun completesWhenRestoreSucceeds() = runBlocking {
        var restored = false
        restoreProofsForAddedMint(
            mintUrl = "https://mint.example.com",
            restoreMint = { restored = true },
        )
        assertTrue(restored)
    }
}
