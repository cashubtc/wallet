package com.cashu.me.Core

/**
 * After a mint is added outside the dedicated restore-mints wizard, re-derive
 * seed proofs via NUT-09 (`wallet.restore()`) so unspent ecash reappears.
 *
 * Seed restore alone does not recover balance — proofs live at the mint and
 * only reappear after restore. Users often restore a seed (e.g. from cashu.me)
 * then add the mint from Mints instead of the restore-mints wizard; without
 * this step the home balance stays 0 forever even though the seed is correct.
 *
 * Restore runs best-effort: the mint is already tracked by [addMint], so a
 * temporary network failure must not roll the mint back. Callers report
 * failures via [onRestoreFailed] (log only). An empty restore on a brand-new
 * wallet is a fast no-op at the mint.
 */
internal suspend fun restoreProofsAfterAddingMint(
    mintUrl: String,
    restoreMint: suspend (String) -> Unit,
    onRestoreFailed: (Throwable) -> Unit = {},
) {
    runCatching { restoreMint(mintUrl) }.onFailure(onRestoreFailed)
}
