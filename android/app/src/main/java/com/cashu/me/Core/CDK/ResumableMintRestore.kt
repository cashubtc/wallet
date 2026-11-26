package com.cashu.me.Core.CDK

import kotlinx.serialization.Serializable
import org.cashudevkit.FfiException
import org.cashudevkit.Nut13Options
import org.cashudevkit.Restored
import org.cashudevkit.RestoreStart

/**
 * Resume position and accumulated totals for an interrupted seed restore at one
 * mint. CDK reports the next derivation index per keyset when a scan is
 * interrupted (or completes); passing those back as the starting indices lets
 * the next attempt continue without repeating finished batches.
 *
 * Amounts cover only the ranges already scanned by earlier attempts. The
 * resumed scan reports its own range, so the two are summed for display.
 * iOS twin: `MintRestoreCheckpoint`.
 */
@Serializable
data class MintRestoreCheckpoint(
    /** Inclusive next index to scan, keyed by encoded keyset ID. */
    val nextKeysetIndices: Map<String, Long>,
    val spent: Long,
    val unspent: Long,
    val pending: Long,
) {
    /**
     * Fold in another scanned range. Positions never move backwards, even if a
     * later report omits a keyset or carries a smaller index.
     */
    fun advanced(
        spent: Long,
        unspent: Long,
        pending: Long,
        nextKeysetIndices: Map<String, Long>,
    ): MintRestoreCheckpoint {
        val merged = this.nextKeysetIndices.toMutableMap()
        nextKeysetIndices.forEach { (keysetId, index) ->
            merged[keysetId] = maxOf(merged[keysetId] ?: 0L, index)
        }
        return MintRestoreCheckpoint(
            nextKeysetIndices = merged,
            spent = this.spent + spent,
            unspent = this.unspent + unspent,
            pending = this.pending + pending,
        )
    }

    companion object {
        val Empty = MintRestoreCheckpoint(emptyMap(), 0, 0, 0)
    }
}

/**
 * A restore that stopped mid-scan after recovering part of the wallet. The
 * checkpoint is already persisted, so retrying resumes where it stopped.
 */
class MintRestoreInterrupted(
    val checkpoint: MintRestoreCheckpoint,
    val reason: String,
) : Exception("Restore paused before finishing. Your progress is saved. Retry to continue.")

/** Per-mint persistence for [MintRestoreCheckpoint]; null clears it. */
interface MintRestoreCheckpoints {
    fun load(mintUrl: String): MintRestoreCheckpoint?
    fun save(mintUrl: String, checkpoint: MintRestoreCheckpoint?)

    /** No persistence: every attempt scans from index zero. */
    object None : MintRestoreCheckpoints {
        override fun load(mintUrl: String): MintRestoreCheckpoint? = null
        override fun save(mintUrl: String, checkpoint: MintRestoreCheckpoint?) = Unit
    }
}

data class RestoredTotals(val spent: Long, val unspent: Long, val pending: Long)

/**
 * Runs a seed restore that survives mid-scan failures. Saves the position CDK
 * reports so the next attempt starts there instead of repeating finished batches.
 * iOS twin: `ResumableMintRestore`.
 */
object ResumableMintRestore {
    /** Same scan shape as `Wallet.restore()` (CDK's NUT-13 defaults). */
    const val BATCH_SIZE = 100u
    const val MAX_GAP = 3u

    /**
     * Returns totals across every attempt, including earlier interrupted ones.
     * Throws [MintRestoreInterrupted] after saving a checkpoint when the scan
     * stopped part-way; any other failure leaves the saved checkpoint untouched.
     */
    suspend fun run(
        checkpoints: MintRestoreCheckpoints,
        mintUrl: String,
        perform: suspend (Nut13Options) -> Restored,
    ): RestoredTotals {
        val saved = checkpoints.load(mintUrl)
        val options = Nut13Options(
            batchSize = BATCH_SIZE,
            maxGap = MAX_GAP,
            start = saved?.let { checkpoint ->
                RestoreStart.KeysetIndices(checkpoint.nextKeysetIndices.mapValues { it.value.toUInt() })
            },
        )
        val base = saved ?: MintRestoreCheckpoint.Empty

        try {
            val restored = perform(options)
            checkpoints.save(mintUrl, null)
            return RestoredTotals(
                spent = base.spent + restored.spent.value.toLong(),
                unspent = base.unspent + restored.unspent.value.toLong(),
                pending = base.pending + restored.pending.value.toLong(),
            )
        } catch (error: FfiException.RestoreInterrupted) {
            val partial = error.partial
            val advanced = base.advanced(
                spent = partial.spent.value.toLong(),
                unspent = partial.unspent.value.toLong(),
                pending = partial.pending.value.toLong(),
                nextKeysetIndices = partial.nextKeysetIndices.mapValues { it.value.toLong() },
            )
            checkpoints.save(mintUrl, advanced)
            throw MintRestoreInterrupted(advanced, error.errorMessage)
        }
    }
}
