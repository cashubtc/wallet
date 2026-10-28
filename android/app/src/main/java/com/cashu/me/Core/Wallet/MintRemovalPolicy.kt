package com.cashu.me.Core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Removal keeps an unfinished transfer on this device, where adding the mint
 * again finishes it. A seed restore cannot recover it, because no ecash has
 * been issued for it yet.
 */
internal fun mintRemovalConfirmationMessage(mintName: String, hasUnfinishedTransfer: Boolean): String {
    val message = "Remove $mintName from your wallet? Any unspent ecash on this mint will need to be restored from your seed phrase."
    if (!hasUnfinishedTransfer) return message
    return message + "\n\nA transfer with this mint hasn't finished. Add the mint again on this device to finish it."
}

/** Commit local mint metadata only after the native removal succeeds. */
internal suspend fun removeMintWalletBeforeCommit(
    mintUrl: String,
    removeWallets: suspend (mintUrl: String) -> Boolean,
    commitMetadata: () -> Unit,
): Boolean {
    currentCoroutineContext().ensureActive()
    // Shield the native side effect and its local metadata commit from the
    // cancellation hand-off performed by cdkCall's withContext(IO). Once CDK
    // removes the wallet, metadata must follow before cancellation propagates.
    val nativeWalletExisted = withContext(NonCancellable) {
        val existed = removeWallets(mintUrl)
        commitMetadata()
        existed
    }
    currentCoroutineContext().ensureActive()
    return nativeWalletExisted
}
