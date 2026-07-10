package org.cashu.wallet.Core.CDK

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class CdkGatewayThreadingTest {
    @Test
    fun cdkGatewayOperationsAreGuardedOffMainThread() {
        val source = sourceFile(
            "src/main/java/org/cashu/wallet/Core/CDK/CdkWalletGatewayImpl.kt",
            "app/src/main/java/org/cashu/wallet/Core/CDK/CdkWalletGatewayImpl.kt",
        ).readText()

        assertTrue(source.contains("private val operationMutex = Mutex()"))
        assertTrue(source.contains("withContext(Dispatchers.IO)"))
        assertTrue(source.contains("operationMutex.withLock"))

        val missingGuard = Regex("""override suspend fun\s+([A-Za-z0-9_]+)""")
            .findAll(source)
            .map { it.groupValues[1] }
            .filter { method ->
                val declarationStart = source.indexOf("override suspend fun $method")
                val declarationEnd = source.indexOf('\n', declarationStart).takeIf { it >= 0 } ?: source.length
                !source.substring(declarationStart, declarationEnd).contains("= cdkCall")
            }
            .toList()

        assertTrue("Expected CDK suspend methods to use cdkCall: $missingGuard", missingGuard.isEmpty())
    }

    @Test
    fun mintQuoteSubscriptionDoesNotHoldGatewayLockWhileWaiting() {
        val source = sourceFile(
            "src/main/java/org/cashu/wallet/Core/CDK/CdkWalletGatewayImpl.kt",
            "app/src/main/java/org/cashu/wallet/Core/CDK/CdkWalletGatewayImpl.kt",
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
