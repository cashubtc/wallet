package org.cashu.wallet.Core

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.PaymentMethodKind

internal class WalletMintMetadataFetcher {
    suspend fun fetchRawMintInfo(url: String): MintInfo = withContext(Dispatchers.IO) {
        val connection = (URL("$url/v1/info").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
        }
        try {
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("Mint info HTTP ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = Json.parseToJsonElement(body).jsonObject
            val name = root["name"]?.jsonPrimitive?.content ?: URL(url).host ?: "Unknown Mint"
            val description = root["description"]?.jsonPrimitive?.content
            val iconUrl = root["icon_url"]?.jsonPrimitive?.content
            val nuts = root["nuts"]?.jsonObject
            val nut04 = nuts?.get("4")?.jsonObject
            val nut05 = nuts?.get("5")?.jsonObject
            val mintMethods = paymentMethods(nut04).ifEmpty { listOf(PaymentMethodKind.Bolt11) }
            val meltMethods = paymentMethods(nut05).ifEmpty { listOf(PaymentMethodKind.Bolt11) }
            val mintUnits = paymentUnits(nut04).ifEmpty { listOf("sat") }
            val units = (mintUnits + paymentUnits(nut05)).distinct().sorted().ifEmpty { listOf("sat") }
            MintInfo(
                url = url,
                name = name,
                description = description,
                iconUrl = iconUrl,
                units = units,
                mintUnits = mintUnits,
                supportedMintMethods = mintMethods,
                supportedMeltMethods = meltMethods,
            )
        } finally {
            connection.disconnect()
        }
    }

    fun normalizeMintUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "https://$normalized"
        }
        return normalized.trimEnd('/')
    }

    fun validateMintUrl(url: String): String? {
        val parsed = runCatching { URL(url) }.getOrNull() ?: return "Invalid URL format."
        if (parsed.host.isNullOrBlank()) return "Invalid URL format."
        if (parsed.protocol != "https") return "Mint URL must use HTTPS for security."
        return null
    }

    private fun paymentMethods(nut: JsonObject?): List<PaymentMethodKind> =
        methodFields(nut)
            .mapNotNull { fields -> PaymentMethodKind.fromRaw(fields["method"]?.jsonPrimitive?.contentOrNull) }
            .distinct()
            .sortedBy { it.sortOrder }

    private fun paymentUnits(nut: JsonObject?): List<String> =
        methodFields(nut)
            .map { fields -> fields["unit"]?.jsonPrimitive?.contentOrNull ?: "sat" }
            .distinct()
            .sorted()

    private fun methodFields(nut: JsonObject?): List<JsonObject> {
        if (nut?.get("disabled")?.jsonPrimitive?.booleanOrNull == true) return emptyList()
        return nut?.get("methods")?.jsonArray.orEmpty().map { it.jsonObject }
    }
}
