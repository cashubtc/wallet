package org.cashu.wallet.Core.CDK

/**
 * Narrow CDK boundary used by [org.cashu.wallet.Core.NwcManager].
 *
 * The concrete implementation is backed by CDK's `NwcService`; keeping that
 * native object behind this interface makes its lifecycle explicit and lets
 * the orchestration layer be tested without a live relay or mint.
 */
interface NwcServiceGateway {
    suspend fun createOrRestoreNwcService(
        mintUrl: String,
        relays: List<String>,
        seed: ByteArray,
        clientSecretKey: String?,
        maxPaymentMsat: ULong?,
    ): NwcServiceHandle
}

interface NwcServiceHandle {
    val connectionUri: String

    suspend fun start()
    suspend fun stop()
    suspend fun isRunning(): Boolean
    suspend fun close()
}
