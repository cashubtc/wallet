package com.cashu.me.Core.CDK

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CdkGatewayThreadingTest {
    @Test
    fun cdkGatewayOperationsAreGuardedOffMainThread() {
        val source = sourceFile(
            "src/main/java/com/cashu/me/Core/CDK/CdkWalletGatewayImpl.kt",
            "app/src/main/java/com/cashu/me/Core/CDK/CdkWalletGatewayImpl.kt",
        ).readText()

        assertTrue(source.contains("private val operationMutex = Mutex()"))
        assertTrue(source.contains("withContext(Dispatchers.IO)"))
        assertTrue(source.contains("operationMutex.withLock"))

        val missingGuard = Regex("""override suspend fun\s+([A-Za-z0-9_]+)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .filter { method ->
                val declarationStart = source.indexOf("override suspend fun $method")
                // Signatures may span multiple lines; the first opening brace
                // closes the declaration because guarded methods use `= cdkCall {`.
                val declarationEnd = source.indexOf('{', declarationStart).takeIf { it >= 0 } ?: source.length
                !source.substring(declarationStart, declarationEnd).contains("= cdkCall")
            }
            .toList()

        assertTrue("Expected CDK suspend methods to use cdkCall: $missingGuard", missingGuard.isEmpty())
    }

    @Test
    fun mintQuoteSubscriptionDoesNotHoldGatewayLockWhileWaiting() {
        val source = sourceFile(
            "src/main/java/com/cashu/me/Core/CDK/CdkWalletGatewayImpl.kt",
            "app/src/main/java/com/cashu/me/Core/CDK/CdkWalletGatewayImpl.kt",
        ).readText()

        assertTrue(source.contains("val subscription = cdkCall"))
        assertTrue(source.contains("subscription.recv()"))
        assertTrue(source.contains("}.flowOn(Dispatchers.IO)"))
    }

    @Test
    fun pendingMeltWaitDoesNotHoldGatewayLockOrRetryPayment() {
        val source = sourceFile(
            "src/main/java/com/cashu/me/Core/CDK/CdkWalletGatewayImpl.kt",
            "app/src/main/java/com/cashu/me/Core/CDK/CdkWalletGatewayImpl.kt",
        ).readText()

        assertTrue(source.contains("prepared.confirmPreferAsync()"))
        val waitStart = source.indexOf("override fun awaitPendingMelt")
        val waitEnd = source.indexOf("override suspend fun checkMeltQuoteStatus", waitStart)
        assertTrue("Missing pending melt waiter", waitStart >= 0 && waitEnd > waitStart)
        val waitBlock = source.substring(waitStart, waitEnd)
        assertTrue(waitBlock.contains("entry.pending.wait()"))
        assertFalse(waitBlock.contains("prepareMelt("))
        assertFalse(waitBlock.contains("= cdkCall"))
        assertTrue(source.contains(".checkMeltQuoteStatus(quoteId)"))
        // wait()/confirm must map FinalizedMelt.state — unpaid is Failed, not Settled.
        assertTrue(source.contains("when (state)"))
        assertTrue(source.contains("MeltSettlement.Failed"))
        assertTrue(source.contains("CdkQuoteState.UNPAID"))
    }

    @Test
    fun startupMaintenanceUsesCdkSagaRecoveryAndKeysetRefresh() {
        val source = sourceFile(
            "src/main/java/com/cashu/me/Core/CDK/CdkWalletGatewayImpl.kt",
            "app/src/main/java/com/cashu/me/Core/CDK/CdkWalletGatewayImpl.kt",
        ).readText()

        assertTrue(source.contains(".recoverIncompleteSagas()"))
        assertTrue(source.contains(".refreshKeysets()"))
    }

    @Test
    fun pendingMeltResyncTreatsUnpaidAsTerminalFailure() {
        val source = sourceFile(
            "src/main/java/com/cashu/me/Core/Wallet/WalletManager.kt",
            "app/src/main/java/com/cashu/me/Core/Wallet/WalletManager.kt",
        ).readText()

        val syncStart = source.indexOf("suspend fun syncPendingMeltQuotes")
        val syncEnd = source.indexOf("private fun watchPendingMelt", syncStart)
        assertTrue("Missing pending melt resync", syncStart >= 0 && syncEnd > syncStart)
        val syncBlock = source.substring(syncStart, syncEnd)
        assertTrue(syncBlock.contains("MeltQuoteState.Failed"))
        assertTrue(syncBlock.contains("MeltQuoteState.Unpaid"))
        assertTrue(syncBlock.contains("MeltSettlement.Failed"))
        // Still in-flight only:
        assertTrue(syncBlock.contains("MeltQuoteState.Pending"))
        assertTrue(syncBlock.contains("MeltQuoteState.Unknown"))
    }

    private fun sourceFile(vararg candidates: String): File {
        val roots = generateSequence(File("").absoluteFile) { it.parentFile }
        return roots
            .flatMap { root -> candidates.asSequence().map { File(root, it) } }
            .firstOrNull { it.exists() }
            ?: error("Missing test fixture: ${candidates.joinToString()}")
    }
}
