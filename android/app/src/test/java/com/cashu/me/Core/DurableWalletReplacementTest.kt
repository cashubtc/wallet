package com.cashu.me.Core

import com.cashu.me.Core.Protocols.SecureStorage
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class DurableWalletReplacementTest {
    private class Storage : SecureStorage {
        val values = mutableMapOf<String, String>()
        var writes = 0
        var failWrite = 0
        override fun loadString(key: String) = values[key]
        override fun contains(key: String) = key in values
        override fun saveString(key: String, value: String) {
            if (++writes == failWrite) error("Simulated interruption")
            values[key] = value
        }
        override fun delete(key: String) { values.remove(key) }
    }

    private fun scenario(block: suspend (File, Storage) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("wallet-replacement-test").toFile()
        try { block(root, Storage()) } finally { root.deleteRecursively() }
    }

    @Test fun stagedRestorePreservesBothWalletsAcrossRepeatedRelaunches() = scenario { root, storage ->
        val db = File(root, "wallet.db").apply { writeText("old quotes and signing keys") }
        val replacement = DurableWalletReplacement(storage, listOf(db))
        replacement.begin("old encrypted state")
        db.writeText("newly restored funds")
        replacement.stageRestore()
        repeat(3) {
            DurableWalletReplacement(storage, listOf(db)).recover(
                { fail("Staged funds must not be rolled back") },
                { fail("Old state must not be discarded before completion") },
            )
        }
        assertEquals("newly restored funds", db.readText())
        assertEquals("old quotes and signing keys", File(db.path + ".replacement-backup-v1").readText())
        assertThrows(IllegalStateException::class.java) { replacement.requireNoStagedRestore() }
        assertTrue(storage.contains(DurableWalletReplacement.KEY))
    }

    @Test fun explicitCompletionReleasesBackupWithoutRollingBackRecoveredFunds() = scenario { root, storage ->
        val db = File(root, "wallet.db").apply { writeText("old") }
        val replacement = DurableWalletReplacement(storage, listOf(db))
        replacement.begin("old state")
        db.writeText("recovered")
        replacement.stageRestore()
        replacement.finishRestore()
        replacement.finishRestore()
        replacement.recover({ fail("Finished restore cannot roll back") }, {})
        replacement.finishRestore()
        assertEquals("recovered", db.readText())
        assertFalse(File(db.path + ".replacement-backup-v1").exists())
        assertFalse(storage.contains(DurableWalletReplacement.KEY))
    }

    @Test fun failedCompletionKeepsBothWalletsAndCanBeRetried() = scenario { root, storage ->
        val db = File(root, "wallet.db").apply { writeText("old") }
        val replacement = DurableWalletReplacement(storage, listOf(db))
        replacement.begin("state")
        db.writeText("recovered")
        replacement.stageRestore()
        storage.failWrite = storage.writes + 1
        assertThrows(IllegalStateException::class.java) { replacement.finishRestore() }
        replacement.recover({ fail("Do not roll back staged restore") }, {})
        assertEquals("old", File(db.path + ".replacement-backup-v1").readText())
        replacement.finishRestore()
        replacement.recover({ fail("Finished restore cannot roll back") }, {})
        assertEquals("recovered", db.readText())
    }

    @Test fun missingStagedBackupBlocksCompletionAndStartup() = scenario { root, storage ->
        val db = File(root, "wallet.db").apply { writeText("old") }
        val replacement = DurableWalletReplacement(storage, listOf(db))
        replacement.begin("state")
        db.writeText("recovered")
        replacement.stageRestore()
        File(db.path + ".replacement-backup-v1").delete()
        assertThrows(IllegalStateException::class.java) { replacement.finishRestore() }
        try {
            replacement.recover({ fail("Do not roll back staged funds") }, {})
            fail("Missing backup must fail closed")
        } catch (_: IllegalStateException) { }
        assertEquals("recovered", db.readText())
        assertTrue(storage.contains(DurableWalletReplacement.KEY))
    }

    @Test fun firstInstallCanRemainStagedWithoutPreviousFiles() = scenario { root, storage ->
        val db = File(root, "wallet.db")
        val replacement = DurableWalletReplacement(storage, listOf(db))
        replacement.begin("no previous wallet")
        db.writeText("recovered")
        replacement.stageRestore()
        replacement.recover({ fail("Do not remove recovered funds") }, {})
        replacement.finishRestore()
        replacement.recover({}, {})
        assertEquals("recovered", db.readText())
    }

    @Test fun relaunchRecoversOldDatabaseSeedAndPreferencesAfterSeedWasChanged() = scenario { root, storage ->
        val db = File(root, "wallet").apply { mkdirs() }
        File(db, "wallet.db").writeText("old proofs")
        File(db, "wallet.db-wal").writeText("old journal")
        storage.values["seed"] = "old seed"
        DurableWalletReplacement(storage, listOf(db)).begin("old preferences")
        db.mkdirs()
        File(db, "wallet.db").writeText("new proofs")
        storage.values["seed"] = "new seed"
        DurableWalletReplacement(storage, listOf(db)).recover({ state ->
            assertEquals("old preferences", state)
            storage.values["seed"] = "old seed"
        }, { fail("Uncommitted replacement must not delete old secrets") })
        assertEquals("old seed", storage.values["seed"])
        assertEquals("old proofs", File(db, "wallet.db").readText())
        assertEquals("old journal", File(db, "wallet.db-wal").readText())
        assertFalse(storage.contains(DurableWalletReplacement.KEY))
    }

    @Test fun interruptionDuringBackupLeavesOriginalUntouched() = scenario { root, storage ->
        val db = File(root, "wallet.db").apply { writeText("old") }
        storage.failWrite = 2
        assertThrows(IllegalStateException::class.java) { DurableWalletReplacement(storage, listOf(db)).begin("old seed") }
        DurableWalletReplacement(storage, listOf(db)).recover({ fail("Original state was never changed") }, {})
        assertEquals("old", db.readText())
    }

    @Test fun interruptedRollbackCanBeRepeatedWithoutConsumingBackups() = scenario { root, storage ->
        val db = File(root, "wallet.db").apply { writeText("old") }
        DurableWalletReplacement(storage, listOf(db)).begin("state")
        db.writeText("new")
        try {
            DurableWalletReplacement(storage, listOf(db)).recover({ error("Seed storage temporarily unavailable") }, {})
            fail("Recovery should stop")
        } catch (_: IllegalStateException) { }
        db.writeText("partial rollback")
        DurableWalletReplacement(storage, listOf(db)).recover({}, {})
        assertEquals("old", db.readText())
    }

    @Test fun committedReplacementSurvivesRelaunchAndCleanupFailure() = scenario { root, storage ->
        val db = File(root, "wallet.db").apply { writeText("old") }
        val replacement = DurableWalletReplacement(storage, listOf(db))
        replacement.begin("old secrets")
        db.writeText("new")
        replacement.commit()
        try {
            replacement.recover({ fail("Committed wallet must never roll back") }, { error("Cleanup interrupted") })
        } catch (_: IllegalStateException) { }
        DurableWalletReplacement(storage, listOf(db)).recover({ fail("Committed wallet must never roll back") }, {})
        assertEquals("new", db.readText())
        assertFalse(storage.contains(DurableWalletReplacement.KEY))
    }

    @Test fun missingBackupFailsBeforeRestoringSeed() = scenario { root, storage ->
        val db = File(root, "wallet.db").apply { writeText("old") }
        DurableWalletReplacement(storage, listOf(db)).begin("state")
        File(db.path + ".replacement-backup-v1").delete()
        db.writeText("new")
        try {
            DurableWalletReplacement(storage, listOf(db)).recover({ fail("Must not mismatch the seed") }, {})
            fail("Missing backup must block launch")
        } catch (_: IllegalStateException) { }
        assertEquals("new", db.readText())
        assertTrue(storage.contains(DurableWalletReplacement.KEY))
    }

    @Test fun newWalletWithoutPreviousDatabaseRollsBackToAbsence() = scenario { root, storage ->
        val db = File(root, "wallet.db")
        DurableWalletReplacement(storage, listOf(db)).begin("no seed")
        db.writeText("new")
        DurableWalletReplacement(storage, listOf(db)).recover({ assertEquals("no seed", it) }, {})
        assertFalse(db.exists())
    }

    @Test fun journalPreferencesPreserveEveryStoredTypeAndMissingKeys() {
        val snapshot = PreferenceSnapshot(setOf("missing", "string", "boolean", "int", "long", "float", "set"), mapOf(
            "string" to "synthetic token", "boolean" to true, "int" to 1, "long" to Long.MAX_VALUE,
            "float" to 1.5f, "set" to setOf("a", "b"),
        ))
        assertEquals(snapshot, Json.decodeFromString<PreferenceSnapshot>(Json.encodeToString(snapshot)))
    }
}
