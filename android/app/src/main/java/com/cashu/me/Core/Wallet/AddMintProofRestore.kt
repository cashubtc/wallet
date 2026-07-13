package com.cashu.me.Core

/**
 * NUT-09 proof recovery that must run as part of [WalletManager.addMint].
 *
 * Seed restore alone does not recover balance — proofs live at the mint and
 * only reappear after `wallet.restore()`. Users often restore a seed (e.g.
 * from cashu.me) then add the mint from Mints instead of the restore-mints
 * wizard; without this step the home balance stays 0 forever even though the
 * seed is correct.
 *
 * Unlike receive-side mint tracking, restore failures MUST propagate. A
 * tracked mint with unrecovered proofs is worse than a failed addMint the
 * user can retry. Brand-new wallets get an empty restore (fast no-op).
 */
internal suspend fun restoreProofsForAddedMint(
    mintUrl: String,
    restoreMint: suspend (String) -> Unit,
) {
    restoreMint(mintUrl)
}
