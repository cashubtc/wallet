package com.cashu.me.Core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** A transfer into or out of the mint has been paid for and not finished. */
internal class MintTransferInProgressException : IllegalStateException(
    "A transfer involving this mint is still settling. Keep it connected until the transfer finishes.",
)

/** Commit local mint metadata only after the native removal succeeds. */
internal suspend fun removeMintWalletBeforeCommit(
    mintUrl: String,
    removeWalletIfSingleUnit: suspend (mintUrl: String) -> Boolean,
    commitMetadata: () -> Unit,
): Boolean {
    currentCoroutineContext().ensureActive()
    // Shield the native side effect and its local metadata commit from the
    // cancellation hand-off performed by cdkCall's withContext(IO). Once CDK
    // removes the wallet, metadata must follow before cancellation propagates.
    val nativeWalletExisted = withContext(NonCancellable) {
        val existed = removeWalletIfSingleUnit(mintUrl)
        commitMetadata()
        existed
    }
    currentCoroutineContext().ensureActive()
    return nativeWalletExisted
}
