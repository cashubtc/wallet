package com.cashu.me.Core.CDK

import java.io.File
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
                if (method == "waitForPendingMelt") return@filter false
                val declarationStart = source.indexOf("override suspend fun $method")
                val declarationEnd = source.indexOf('\n', declarationStart).takeIf { it >= 0 } ?: source.length
                !source.substring(declarationStart, declarationEnd).contains("= cdkCall")
            }
            .toList()

        assertTrue("Expected CDK suspend methods to use cdkCall: $missingGuard", missingGuard.isEmpty())
        // A pending melt may wait for minutes. It must leave the gateway mutex
        // free while the native cancellable handle polls in the background.
        assertTrue(source.contains("withContext(Dispatchers.IO) { record.handle.wait() }"))
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

    private fun sourceFile(vararg candidates: String): File {
        val roots = generateSequence(File("").absoluteFile) { it.parentFile }
        return roots
            .flatMap { root -> candidates.asSequence().map { File(root, it) } }
            .firstOrNull { it.exists() }
            ?: error("Missing test fixture: ${candidates.joinToString()}")
    }
}
