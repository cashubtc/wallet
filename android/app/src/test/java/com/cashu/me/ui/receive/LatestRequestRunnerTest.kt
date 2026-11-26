package com.cashu.me.ui.receive

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class LatestRequestRunnerTest {
    @Test(timeout = 5_000)
    fun cancellingSupersededRequestRethrowsCancellationAndPreservesLoadingForSuccessor() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            var loading = false
            val runner = LatestRequestRunner(scope) { loading = it }
            val firstStarted = CompletableDeferred<Unit>()
            val secondGate = CompletableDeferred<String>()
            var firstCancelled = false
            val failures = mutableListOf<Throwable>()
            val successes = mutableListOf<String>()

            val firstJob = runner.launch(
                block = {
                    firstStarted.complete(Unit)
                    try {
                        CompletableDeferred<String>().await()
                    } catch (e: CancellationException) {
                        firstCancelled = true
                        throw e
                    }
                },
                onSuccess = { successes += it },
                onFailure = { failures += it },
            )
            firstStarted.await()
            assertTrue(loading)

            val secondJob = runner.launch(
                block = { secondGate.await() },
                onSuccess = { successes += it },
                onFailure = { failures += it },
            )
            firstJob.join()

            assertTrue("Superseded request must be cancelled", firstJob.isCancelled)
            assertTrue("CancellationException must propagate", firstCancelled)
            assertTrue("Cancellation must never invoke onFailure", failures.isEmpty())
            assertTrue("Superseded finally must not clear loading for the newer request", loading)

            secondGate.complete("offer-2")
            secondJob.join()

            assertEquals(listOf("offer-2"), successes)
            assertTrue(failures.isEmpty())
            assertFalse(loading)
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 5_000)
    fun nonCancellationFailureCallsOnFailureAndClearsLoading() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            var loading = false
            val runner = LatestRequestRunner(scope) { loading = it }
            val failures = mutableListOf<Throwable>()

            val job = runner.launch<String>(
                block = { throw IllegalStateException("Mint unavailable") },
                onSuccess = {},
                onFailure = { failures += it },
            )
            job.join()

            assertEquals(1, failures.size)
            assertEquals("Mint unavailable", failures.single().message)
            assertFalse(loading)
        } finally {
            scope.cancel()
        }
    }

    @Test(timeout = 5_000)
    fun staleCompletionAfterSupersedeCannotOverwriteResultOrClearLoading() = runBlocking {
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        try {
            var loading = false
            val runner = LatestRequestRunner(scope) { loading = it }
            var firstContinuation: Continuation<String>? = null
            val secondGate = CompletableDeferred<String>()
            val successes = mutableListOf<String>()

            // Uses non-cancellable suspendCoroutine to simulate a blocking FFI call returning late.
            val firstJob = runner.launch(
                block = {
                    suspendCoroutine { continuation ->
                        firstContinuation = continuation
                    }
                },
                onSuccess = { successes += it },
                onFailure = {},
            )
            yield()
            assertTrue(loading)

            val secondJob = runner.launch(
                block = { secondGate.await() },
                onSuccess = { successes += it },
                onFailure = {},
            )
            yield()

            firstContinuation!!.resume("stale-offer")
            firstJob.join()

            assertTrue("Stale completion must not overwrite newer request", successes.isEmpty())
            assertTrue("Stale completion must not clear loading while successor is active", loading)

            secondGate.complete("fresh-offer")
            secondJob.join()

            assertEquals(listOf("fresh-offer"), successes)
            assertFalse(loading)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun lightningAddressQrAndActionsRequireNonBlankAddress() {
        assertTrue(shouldShowLightningAddressQrAndActions("npub1example@npubx.cash"))
        assertFalse(shouldShowLightningAddressQrAndActions(""))
        assertFalse(shouldShowLightningAddressQrAndActions("   "))
    }
}
