package com.cashu.me.Core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import com.cashu.me.Models.PaymentMethodKind

internal fun interface LightningAddressJsonFetcher {
    suspend fun get(url: HttpUrl): String
}

internal class LightningAddressResolver(
    private val fetcher: LightningAddressJsonFetcher = OkHttpLightningAddressJsonFetcher(),
    private val metadataDecoder: (String) -> LightningRequestMetadata? = PaymentRequestDecoder::lightningMetadata,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Resolve LUD-16/LNURL-pay first; callers may fall back to BIP-353 only when indicated. */
    suspend fun resolveBolt11Invoice(address: String, amountMsat: Long): String {
        require(amountMsat > 0) { "Lightning address payments require an amount." }
        val endpoint = lightningAddressEndpoint(address)
        val payRequest = fetchJson<LnurlPayRequest>(endpoint)

        throwIfServiceError(payRequest.status, payRequest.reason)
        if (payRequest.tag != "payRequest") {
            throw LightningAddressResolverError.NoLnurlPayEndpoint(
                "Lightning address did not return an LNURL-pay request.",
            )
        }
        val callback = payRequest.callback
            ?: throw LightningAddressResolverError.NoLnurlPayEndpoint(
                "Lightning address response is missing payment details.",
            )
        val minimum = payRequest.minSendable
            ?: throw LightningAddressResolverError.NoLnurlPayEndpoint(
                "Lightning address response is missing payment details.",
            )
        val maximum = payRequest.maxSendable
            ?: throw LightningAddressResolverError.NoLnurlPayEndpoint(
                "Lightning address response is missing payment details.",
            )
        if (amountMsat !in minimum..maximum) {
            throw LightningAddressResolverError.AmountOutOfRange(amountMsat, minimum, maximum)
        }

        val callbackUrl = invoiceCallbackUrl(callback, amountMsat)
        val callbackResponse = fetchJson<LnurlPayCallbackResponse>(callbackUrl)
        throwIfServiceError(callbackResponse.status, callbackResponse.reason)

        val paymentRequest = callbackResponse.pr?.trim().takeUnless { it.isNullOrEmpty() }
            ?: throw LightningAddressResolverError.InvalidInvoice(
                "Lightning address service did not return an invoice.",
            )
        val metadata = metadataDecoder(paymentRequest)
        if (
            metadata == null ||
            metadata.paymentMethod != PaymentMethodKind.Bolt11 ||
            metadata.amountMsat != amountMsat
        ) {
            throw LightningAddressResolverError.InvalidInvoice(
                "Lightning address service returned an invoice for a different amount.",
            )
        }
        return metadata.normalizedRequest
    }

    internal fun lightningAddressEndpoint(address: String): HttpUrl {
        val trimmed = address.trim()
        val parts = trimmed.split('@', limit = 2)
        if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
            throw LightningAddressResolverError.InvalidAddress
        }
        val username = parts[0]
        val domain = parts[1].lowercase()
        if (
            '.' !in domain ||
            domain.startsWith('.') ||
            domain.endsWith('.') ||
            domain.any { it in "/?#@:" }
        ) {
            throw LightningAddressResolverError.InvalidAddress
        }
        val origin = "https://$domain".toHttpUrlOrNull()
            ?: throw LightningAddressResolverError.InvalidAddress
        return origin.newBuilder()
            .addPathSegment(".well-known")
            .addPathSegment("lnurlp")
            .addPathSegment(username)
            .build()
    }

    private fun invoiceCallbackUrl(callback: String, amountMsat: Long): HttpUrl {
        val parsed = callback.toHttpUrlOrNull()
            ?: throw LightningAddressResolverError.InvalidCallback
        if (parsed.scheme != "https") throw LightningAddressResolverError.InvalidCallback
        val builder = parsed.newBuilder()
        parsed.queryParameterNames
            .filter { it.equals("amount", ignoreCase = true) }
            .forEach(builder::removeAllQueryParameters)
        return builder
            .addQueryParameter("amount", amountMsat.toString())
            .build()
    }

    private suspend inline fun <reified T> fetchJson(url: HttpUrl): T {
        val body = try {
            fetcher.get(url)
        } catch (error: LightningAddressResolverError) {
            throw error
        } catch (_: Throwable) {
            throw LightningAddressResolverError.NoLnurlPayEndpoint(
                "Lightning address service could not be reached.",
            )
        }
        return runCatching { json.decodeFromString<T>(body) }
            .getOrElse {
                throw LightningAddressResolverError.NoLnurlPayEndpoint(
                    "Lightning address service returned an invalid JSON response.",
                )
            }
    }

    private fun throwIfServiceError(status: String?, reason: String?) {
        if (status.equals("ERROR", ignoreCase = true)) {
            throw LightningAddressResolverError.ServiceError(
                reason ?: "Lightning address service returned an error.",
            )
        }
    }
}

private class OkHttpLightningAddressJsonFetcher(
    private val client: OkHttpClient = OkHttpClient(),
) : LightningAddressJsonFetcher {
    override suspend fun get(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw LightningAddressResolverError.NoLnurlPayEndpoint(
                    "Lightning address service could not be reached.",
                )
            }
            response.body?.string()
                ?: throw LightningAddressResolverError.NoLnurlPayEndpoint(
                    "Lightning address service returned an empty response.",
                )
        }
    }
}

internal sealed class LightningAddressResolverError(message: String) : IllegalArgumentException(message) {
    data object InvalidAddress : LightningAddressResolverError(
        "That Lightning address does not look valid.",
    )
    data object InvalidCallback : LightningAddressResolverError(
        "Lightning address service returned an invalid payment callback.",
    )
    class NoLnurlPayEndpoint(message: String) : LightningAddressResolverError(message)
    class ServiceError(reason: String) : LightningAddressResolverError(reason)
    class InvalidInvoice(message: String) : LightningAddressResolverError(message)
    class AmountOutOfRange(requested: Long, minimum: Long, maximum: Long) : LightningAddressResolverError(
        "Amount is outside this Lightning address range. Requested ${requested / 1_000} sats, " +
            "supported range is ${minimum / 1_000}-${maximum / 1_000} sats.",
    )

    val indicatesNoLnurlPayEndpoint: Boolean
        get() = this is NoLnurlPayEndpoint
}

@Serializable
private data class LnurlPayRequest(
    val callback: String? = null,
    val maxSendable: Long? = null,
    val minSendable: Long? = null,
    val tag: String? = null,
    val status: String? = null,
    val reason: String? = null,
)

@Serializable
private data class LnurlPayCallbackResponse(
    val pr: String? = null,
    val status: String? = null,
    val reason: String? = null,
)
