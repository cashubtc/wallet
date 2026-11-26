package com.cashu.me.Core

import com.cashu.me.Models.PaymentMethodKind
import org.cashudevkit.CurrencyUnit as CdkCurrencyUnit
import org.cashudevkit.PaymentType as CdkPaymentType
import org.cashudevkit.decodeInvoice
import org.cashudevkit.decodePaymentRequest
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

data class CashuPaymentRequestSummary(
    val encoded: String,
    val amount: Long? = null,
    val unit: String? = null,
    val description: String? = null,
    val mints: List<String> = emptyList(),
) {
    val isSatUnit: Boolean get() = unit?.lowercase() == null || unit.lowercase() == "sat"
}

sealed interface PaymentRequestDecodeResult {
    data class LightningAddress(val address: String) : PaymentRequestDecodeResult
    data class Bolt11(val amountSats: Long?, val description: String?) : PaymentRequestDecodeResult
    data class Bolt12(val amountSats: Long?, val description: String?) : PaymentRequestDecodeResult
    data class Onchain(val address: String) : PaymentRequestDecodeResult
    data class CashuPaymentRequest(val summary: CashuPaymentRequestSummary) : PaymentRequestDecodeResult
    data object Unrecognized : PaymentRequestDecodeResult
}

/**
 * True for a BOLT11 invoice that leaves the amount to the payer. Send collects
 * the amount and passes it through CDK's amountless melt option, exactly like
 * an amountless BOLT12 offer.
 */
val PaymentRequestDecodeResult.isAmountlessBolt11: Boolean
    get() = this is PaymentRequestDecodeResult.Bolt11 && (amountSats == null || amountSats <= 0L)

object PaymentRequestParser {
    fun normalizeLightningRequest(request: String): String {
        val trimmed = request.trim()
        return when {
            trimmed.startsWith("lightning://", ignoreCase = true) -> trimmed.drop("lightning://".length)
            trimmed.startsWith("lightning:", ignoreCase = true) -> trimmed.drop("lightning:".length)
            else -> trimmed
        }
    }

    fun normalizeBitcoinRequest(request: String): String {
        val trimmed = request.trim()
        val withoutScheme = when {
            trimmed.startsWith("bitcoin://", ignoreCase = true) -> trimmed.drop("bitcoin://".length)
            trimmed.startsWith("bitcoin:", ignoreCase = true) -> trimmed.drop("bitcoin:".length)
            else -> trimmed
        }
        return withoutScheme.substringBefore("?")
    }

    fun isBitcoinAddress(request: String): Boolean =
        BitcoinAddressValidator.isValidAddress(normalizeBitcoinRequest(request))

    fun isHumanReadableLightningAddress(request: String): Boolean {
        val trimmed = request.trim()
        val atIndex = trimmed.indexOf('@')
        if (atIndex <= 0 || atIndex == trimmed.lastIndex) return false
        val domain = trimmed.substring(atIndex + 1)
        return "." in domain && !domain.startsWith(".") && !domain.endsWith(".")
    }

    fun paymentMethod(request: String): PaymentMethodKind? {
        if (isHumanReadableLightningAddress(request)) return null
        PaymentRequestDecoder.lightningPaymentMethod(request)?.let { return it }
        if (isBitcoinAddress(request)) return PaymentMethodKind.Onchain
        return null
    }
}

internal data class DecodedInvoiceSnapshot(
    val paymentMethod: PaymentMethodKind,
    val amountMsat: Long?,
    val description: String?,
)

object PaymentRequestDecoder {
    internal const val INVOICE_CACHE_CAPACITY = 256

    private sealed interface CachedInvoiceEntry {
        data class Decoded(val snapshot: DecodedInvoiceSnapshot) : CachedInvoiceEntry
        data object Invalid : CachedInvoiceEntry
    }

    private data class NormalizedLightningCandidate(
        val normalized: String,
        val fromBitcoinUri: Boolean,
    )

    private val invoiceCacheLock = Any()
    private val invoiceCache = object : LinkedHashMap<String, CachedInvoiceEntry>(64, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, CachedInvoiceEntry>?,
        ): Boolean = size > INVOICE_CACHE_CAPACITY
    }
    private var invoiceCacheGeneration = 0L

    @Volatile
    private var invoiceDecoder: (String) -> DecodedInvoiceSnapshot? = ::defaultDecodeInvoice

    internal fun resetInvoiceCacheForTesting() {
        synchronized(invoiceCacheLock) {
            invoiceCache.clear()
            invoiceCacheGeneration += 1L
        }
    }

    internal fun setInvoiceDecoderForTesting(override: ((String) -> DecodedInvoiceSnapshot?)?) {
        synchronized(invoiceCacheLock) {
            invoiceCache.clear()
            invoiceCacheGeneration += 1L
            invoiceDecoder = override ?: ::defaultDecodeInvoice
        }
    }

    private fun defaultDecodeInvoice(normalized: String): DecodedInvoiceSnapshot? =
        runCatching { decodeInvoice(normalized) }.getOrNull()?.let { decoded ->
            val paymentMethod = when (decoded.paymentType) {
                CdkPaymentType.BOLT11 -> PaymentMethodKind.Bolt11
                CdkPaymentType.BOLT12 -> PaymentMethodKind.Bolt12
            }
            DecodedInvoiceSnapshot(
                paymentMethod = paymentMethod,
                amountMsat = decoded.amountMsat?.toLong(),
                description = decoded.description,
            )
        }

    private fun decodeInvoiceCached(normalized: String): DecodedInvoiceSnapshot? {
        val lookupGeneration: Long
        val activeDecoder: (String) -> DecodedInvoiceSnapshot?
        synchronized(invoiceCacheLock) {
            when (val cached = invoiceCache[normalized]) {
                is CachedInvoiceEntry.Decoded -> return cached.snapshot
                CachedInvoiceEntry.Invalid -> return null
                null -> {
                    lookupGeneration = invoiceCacheGeneration
                    activeDecoder = invoiceDecoder
                }
            }
        }

        // Run FFI outside the cache lock so concurrent decodes never serialize.
        val snapshot = activeDecoder(normalized)
        val entry = snapshot?.let(CachedInvoiceEntry::Decoded) ?: CachedInvoiceEntry.Invalid
        synchronized(invoiceCacheLock) {
            if (invoiceCacheGeneration == lookupGeneration) {
                invoiceCache[normalized] = entry
            }
        }
        return snapshot
    }

    /** Recover immutable descriptions from persisted invoices, including older history. */
    fun description(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        LightningRequestParser.parseBolt12Offer(raw)?.let { return it.description?.takeIf(String::isNotBlank) }
        return when (val decoded = decode(raw, includeCashuPaymentRequests = true)) {
            is PaymentRequestDecodeResult.Bolt11 -> decoded.description
            is PaymentRequestDecodeResult.Bolt12 -> decoded.description
            is PaymentRequestDecodeResult.CashuPaymentRequest -> decoded.summary.description
            else -> null
        }?.takeIf(String::isNotBlank)
    }

    private const val CREQ_A_PREFIX = "creqA"

    fun decode(
        raw: String,
        includeCashuPaymentRequests: Boolean = false,
        preferCashuPaymentRequests: Boolean = false,
    ): PaymentRequestDecodeResult {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return PaymentRequestDecodeResult.Unrecognized

        if (includeCashuPaymentRequests && preferCashuPaymentRequests) {
            cashuPaymentRequestSummary(trimmed)?.let { return PaymentRequestDecodeResult.CashuPaymentRequest(it) }
        }

        decodedLightningRequest(trimmed)?.let { return it }

        if (PaymentRequestParser.isHumanReadableLightningAddress(trimmed)) {
            return PaymentRequestDecodeResult.LightningAddress(trimmed)
        }

        if (PaymentRequestParser.isBitcoinAddress(trimmed)) {
            return PaymentRequestDecodeResult.Onchain(PaymentRequestParser.normalizeBitcoinRequest(trimmed))
        }

        if (includeCashuPaymentRequests) {
            cashuPaymentRequestSummary(trimmed)?.let { return PaymentRequestDecodeResult.CashuPaymentRequest(it) }
        }

        return PaymentRequestDecodeResult.Unrecognized
    }

    fun encodedLightningRequest(raw: String): String? {
        val candidate = normalizedLightningCandidate(raw) ?: return null
        LightningRequestParser.parseBolt12Offer(candidate.normalized)?.let { return it.request }
        if (candidate.fromBitcoinUri) return candidate.normalized
        return if (decodeInvoiceCached(candidate.normalized) != null) candidate.normalized else null
    }

    internal fun lightningPaymentMethod(raw: String): PaymentMethodKind? {
        val candidate = normalizedLightningCandidate(raw) ?: return null
        val offer = LightningRequestParser.parseBolt12Offer(candidate.normalized)
        val lookupKey = offer?.request ?: candidate.normalized
        decodeInvoiceCached(lookupKey)?.let { return it.paymentMethod }
        return if (offer != null) PaymentMethodKind.Bolt12 else null
    }

    private fun normalizedLightningCandidate(raw: String): NormalizedLightningCandidate? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        bitcoinPaymentURI(trimmed)?.lightning?.let { embedded ->
            val normalized = PaymentRequestParser.normalizeLightningRequest(embedded)
            return normalized.takeIf(String::isNotEmpty)?.let {
                NormalizedLightningCandidate(normalized = it, fromBitcoinUri = true)
            }
        }
        val normalized = PaymentRequestParser.normalizeLightningRequest(trimmed)
        return normalized.takeIf(String::isNotEmpty)?.let {
            NormalizedLightningCandidate(normalized = it, fromBitcoinUri = false)
        }
    }

    fun encodedCashuPaymentRequest(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        bitcoinPaymentURI(trimmed)?.creq?.let { return it }
        val withoutCashuScheme = stripSchemePrefixes(listOf("cashu://", "cashu:"), trimmed)
        val lower = withoutCashuScheme.lowercase()
        return if (lower.startsWith("creqa") || lower.startsWith("creqb1")) withoutCashuScheme else null
    }

    fun cdkCompatibleCashuPaymentRequest(raw: String): String? {
        val encoded = encodedCashuPaymentRequest(raw) ?: return null
        return if (encoded.startsWith(CREQ_A_PREFIX, ignoreCase = true)) {
            val payload = encoded.drop(CREQ_A_PREFIX.length).trimEnd('=')
            val padding = (4 - payload.length % 4) % 4
            CREQ_A_PREFIX + payload + "=".repeat(padding)
        } else {
            encoded
        }
    }

    fun cashuPaymentRequestSummary(raw: String): CashuPaymentRequestSummary? {
        val encoded = encodedCashuPaymentRequest(raw) ?: return null
        val cdkEncoded = cdkCompatibleCashuPaymentRequest(encoded) ?: encoded
        runCatching { decodePaymentRequest(cdkEncoded) }.getOrNull()?.let { request ->
            return CashuPaymentRequestSummary(
                encoded = cdkEncoded,
                amount = request.amount()?.value?.toLong(),
                unit = request.unit()?.toDomainUnit(),
                description = request.description(),
                mints = request.mints(),
            )
        }
        return legacyCreqASummary(cdkEncoded)
    }

    private fun decodedLightningRequest(raw: String): PaymentRequestDecodeResult? {
        val candidate = normalizedLightningCandidate(raw) ?: return null
        val offer = LightningRequestParser.parseBolt12Offer(candidate.normalized)
        val lookupKey = offer?.request ?: candidate.normalized
        decodeInvoiceCached(lookupKey)?.let { decoded ->
            val amountSats = decoded.amountMsat?.let { (it + 999L) / 1000L }
            return when (decoded.paymentMethod) {
                PaymentMethodKind.Bolt11 -> PaymentRequestDecodeResult.Bolt11(amountSats, decoded.description)
                PaymentMethodKind.Bolt12 -> PaymentRequestDecodeResult.Bolt12(amountSats, decoded.description)
                PaymentMethodKind.Onchain -> null
            }
        }
        return offer?.let { PaymentRequestDecodeResult.Bolt12(it.amountSats, it.description) }
    }

    fun amountLocked(result: PaymentRequestDecodeResult): Boolean = when (result) {
        is PaymentRequestDecodeResult.Bolt11 -> result.amountSats != null
        is PaymentRequestDecodeResult.Bolt12 -> result.amountSats != null
        else -> false
    }

    fun typeLabel(result: PaymentRequestDecodeResult): String = when (result) {
        is PaymentRequestDecodeResult.LightningAddress -> "Lightning address"
        is PaymentRequestDecodeResult.Bolt11 -> "BOLT11 invoice"
        is PaymentRequestDecodeResult.Bolt12 -> "BOLT12 offer"
        is PaymentRequestDecodeResult.Onchain -> "Bitcoin address"
        is PaymentRequestDecodeResult.CashuPaymentRequest -> "Cashu request"
        PaymentRequestDecodeResult.Unrecognized -> "Unrecognized"
    }

    /**
     * How a request reads in the Send "To" row: a fixed `take(8)…takeLast(6)` cut,
     * with Lightning addresses whole (iOS parity). BOLT11, BOLT12 and bech32
     * addresses are case-insensitive and QR codes carry them uppercase, so they
     * show lowercase; case-sensitive formats (base58 addresses, Cashu requests)
     * keep their case.
     */
    fun displayRequest(raw: String, result: PaymentRequestDecodeResult): String {
        val trimmed = raw.trim()
        return when (result) {
            is PaymentRequestDecodeResult.LightningAddress -> result.address
            is PaymentRequestDecodeResult.Bolt11,
            is PaymentRequestDecodeResult.Bolt12 -> middleTruncated(trimmed.lowercase())
            is PaymentRequestDecodeResult.Onchain -> {
                val lowered = trimmed.lowercase()
                middleTruncated(if (listOf("bc1", "tb1", "bcrt1").any { lowered.startsWith(it) }) lowered else trimmed)
            }
            is PaymentRequestDecodeResult.CashuPaymentRequest,
            PaymentRequestDecodeResult.Unrecognized -> middleTruncated(trimmed)
        }
    }

    /** `take(8)…takeLast(6)` for opaque request strings; short ones are returned whole (iOS parity). */
    fun middleTruncated(raw: String): String {
        val trimmed = raw.trim()
        return if (trimmed.length > 16) "${trimmed.take(8)}…${trimmed.takeLast(6)}" else trimmed
    }

    fun shortRepresentation(raw: String, result: PaymentRequestDecodeResult): String = when (result) {
        is PaymentRequestDecodeResult.LightningAddress -> result.address
        is PaymentRequestDecodeResult.CashuPaymentRequest ->
            result.summary.description ?: amountLabel(result.summary) ?: "Cashu payment request"
        else -> {
            val trimmed = raw.trim()
            if (trimmed.length > 16) "${trimmed.take(8)}...${trimmed.takeLast(6)}" else trimmed
        }
    }

    fun amountLabel(summary: CashuPaymentRequestSummary): String? =
        summary.amount?.let { "$it ${summary.unit ?: "sat"}" }

    private fun bitcoinPaymentURI(raw: String): BitcoinPaymentURI? {
        val body = when {
            raw.startsWith("bitcoin://", ignoreCase = true) -> raw.drop("bitcoin://".length)
            raw.startsWith("bitcoin:", ignoreCase = true) -> raw.drop("bitcoin:".length)
            else -> return null
        }
        val query = body.substringAfter("?", missingDelimiterValue = "")
        if (query.isEmpty()) return BitcoinPaymentURI(creq = null, lightning = null)
        val parameters = query.split("&").mapNotNull { entry ->
            val key = entry.substringBefore("=", missingDelimiterValue = "").decodeQueryComponent().trim()
            if (key.isEmpty()) return@mapNotNull null
            val value = entry.substringAfter("=", missingDelimiterValue = "").decodeQueryComponent().trim()
            key to value
        }
        val creq = parameters
            .firstOrNull { (key, _) -> key.equals("creq", ignoreCase = true) }
            ?.second
        val lightning = parameters
            .firstOrNull { (key, _) -> key.equals("lightning", ignoreCase = true) || key.equals("lightninginvoice", ignoreCase = true) }
            ?.second
        return BitcoinPaymentURI(creq = creq, lightning = lightning)
    }

    private fun stripSchemePrefixes(prefixes: List<String>, input: String): String {
        val prefix = prefixes.firstOrNull { input.startsWith(it, ignoreCase = true) }
        return if (prefix == null) input else input.drop(prefix.length)
    }

    private fun legacyCreqASummary(encoded: String): CashuPaymentRequestSummary? {
        if (!encoded.startsWith(CREQ_A_PREFIX, ignoreCase = true)) return null
        val payload = encoded.drop(CREQ_A_PREFIX.length)
        val bytes = runCatching { Base64.getUrlDecoder().decode(payload) }.getOrNull() ?: return null
        val fields = (runCatching { CborReader(bytes).readValue() }.getOrNull() as? CborValue.Map)
            ?.entries
            ?.mapNotNull { (key, value) -> (key as? CborValue.Text)?.value?.let { it to value } }
            ?.toMap()
            ?: return null
        val mints = (fields["m"] as? CborValue.Array)
            ?.values
            ?.mapNotNull { (it as? CborValue.Text)?.value }
            .orEmpty()
        return CashuPaymentRequestSummary(
            encoded = encoded,
            amount = (fields["a"] as? CborValue.UInt)?.value,
            unit = (fields["u"] as? CborValue.Text)?.value,
            description = (fields["d"] as? CborValue.Text)?.value,
            mints = mints,
        )
    }

    private data class BitcoinPaymentURI(val creq: String?, val lightning: String?)

    private sealed interface CborValue {
        data class UInt(val value: Long) : CborValue
        data class Text(val value: String) : CborValue
        data class Bool(val value: Boolean) : CborValue
        data class Array(val values: List<CborValue>) : CborValue
        data class Map(val entries: List<Pair<CborValue, CborValue>>) : CborValue
        data object Null : CborValue
    }

    private class CborReader(private val bytes: ByteArray) {
        private var position = 0

        fun readValue(): CborValue {
            val initial = readByte()
            val major = initial shr 5
            val additional = initial and 0x1F
            return when (major) {
                0 -> CborValue.UInt(readLength(additional))
                3 -> CborValue.Text(readBytes(readLength(additional).toInt()).toString(Charsets.UTF_8))
                4 -> CborValue.Array(List(readLength(additional).toInt()) { readValue() })
                5 -> CborValue.Map(List(readLength(additional).toInt()) { readValue() to readValue() })
                7 -> when (additional) {
                    20 -> CborValue.Bool(false)
                    21 -> CborValue.Bool(true)
                    22 -> CborValue.Null
                    else -> error("Unsupported CBOR simple value.")
                }
                else -> error("Unsupported CBOR major type.")
            }
        }

        private fun readLength(additional: Int): Long = when {
            additional < 24 -> additional.toLong()
            additional == 24 -> readByte().toLong()
            additional == 25 -> ((readByte() shl 8) or readByte()).toLong()
            additional == 26 -> (0 until 4).fold(0L) { acc, _ -> (acc shl 8) or readByte().toLong() }
            additional == 27 -> (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or readByte().toLong() }
            else -> error("Unsupported CBOR length.")
        }

        private fun readBytes(length: Int): ByteArray {
            require(length >= 0 && position + length <= bytes.size) { "Truncated CBOR payload." }
            return bytes.copyOfRange(position, position + length).also { position += length }
        }

        private fun readByte(): Int {
            require(position < bytes.size) { "Truncated CBOR payload." }
            return bytes[position++].toInt() and 0xFF
        }
    }

    private fun String.decodeQueryComponent(): String =
        runCatching { URLDecoder.decode(this, StandardCharsets.UTF_8.name()) }.getOrDefault(this)

}
