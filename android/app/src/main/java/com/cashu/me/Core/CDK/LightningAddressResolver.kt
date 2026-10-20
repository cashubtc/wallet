package com.cashu.me.Core.CDK

import org.cashudevkit.FfiException

/** LNURL first, even for BOLT12 mints. Only a missing discovery endpoint permits DNS fallback. */
internal object LightningAddressResolver {
    suspend fun <T> quote(lnurl: suspend () -> T, bip353: suspend () -> T): T = try {
        lnurl()
    } catch (error: FfiException.Cdk) {
        if (!isMissingEndpoint(error.errorMessage)) throw error
        bip353()
    }

    // CDK 0.18.2 flattens resolver errors into FFI detail strings. Match the full
    // discovery-stage prefix, never callback/mint HTTP errors or response bodies.
    internal fun isMissingEndpoint(message: String): Boolean =
        listOf(404, 410).any { message.startsWith("$DiscoveryPrefix$it):") }

    private const val DiscoveryPrefix =
        "Failed to request invoice from Lightning address service: Failed to fetch pay request data: Http transport error Some("
}
