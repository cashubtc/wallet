package org.cashu.wallet.Core

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.cashu.wallet.Models.MintContactInfo
import org.cashu.wallet.Models.MintInfo
import org.cashu.wallet.Models.MintNutSupport
import org.cashu.wallet.Models.MintPaymentMethodSetting
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
            val mintMethodSettings = methodSettings(nuts?.get("4")?.jsonObject, mint = true)
            val meltMethodSettings = methodSettings(nuts?.get("5")?.jsonObject, mint = false)
            val mintMethods = mintMethodSettings
                .map { it.method }
                .distinct()
                .sortedBy { it.sortOrder }
                .ifEmpty { listOf(PaymentMethodKind.Bolt11) }
            val meltMethods = meltMethodSettings
                .map { it.method }
                .distinct()
                .sortedBy { it.sortOrder }
                .ifEmpty { listOf(PaymentMethodKind.Bolt11) }
            val mintUnits = mintMethodSettings.map { it.unit }.distinct().sorted().ifEmpty { listOf("sat") }
            val units = (mintUnits + meltMethodSettings.map { it.unit }).distinct().sorted().ifEmpty { listOf("sat") }
            val version = softwareInfo(root["version"])
            MintInfo(
                url = url,
                name = name,
                pubkey = root["pubkey"]?.jsonPrimitive?.contentOrNull,
                description = description,
                descriptionLong = root["description_long"]?.jsonPrimitive?.contentOrNull,
                iconUrl = iconUrl,
                urls = root["urls"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty(),
                motd = root["motd"]?.jsonPrimitive?.contentOrNull,
                serverTimeEpochSeconds = root["time"]?.jsonPrimitive?.longOrNull,
                tosUrl = root["tos_url"]?.jsonPrimitive?.contentOrNull,
                softwareName = version.name,
                softwareVersion = version.version,
                contacts = contacts(root["contact"]),
                nuts = MintNutSupport(
                    nut04 = nutSupported(nuts, "4", mintMethodSettings.isNotEmpty()),
                    nut05 = nutSupported(nuts, "5", meltMethodSettings.isNotEmpty()),
                    nut07 = nutSupported(nuts, "7"),
                    nut08 = nutSupported(nuts, "8"),
                    nut09 = nutSupported(nuts, "9"),
                    nut10 = nutSupported(nuts, "10"),
                    nut11 = nutSupported(nuts, "11"),
                    nut12 = nutSupported(nuts, "12"),
                    nut14 = nutSupported(nuts, "14"),
                    nut20 = nutSupported(nuts, "20"),
                    nut21 = nutSupported(nuts, "21"),
                    nut22 = nutSupported(nuts, "22"),
                    nut29 = nutSupported(nuts, "29"),
                ),
                mintMethodSettings = mintMethodSettings,
                meltMethodSettings = meltMethodSettings,
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

    private fun methodSettings(nut: JsonObject?, mint: Boolean): List<MintPaymentMethodSetting> {
        if (nut?.get("disabled")?.jsonPrimitive?.booleanOrNull == true) return emptyList()
        return nut?.get("methods")?.jsonArray.orEmpty().mapNotNull { element ->
            val fields = element.jsonObject
            val method = PaymentMethodKind.fromRaw(fields["method"]?.jsonPrimitive?.contentOrNull)
                ?: return@mapNotNull null
            MintPaymentMethodSetting(
                method = method,
                unit = fields["unit"]?.jsonPrimitive?.contentOrNull ?: "sat",
                minAmount = fields["min_amount"]?.jsonPrimitive?.longOrNull,
                maxAmount = fields["max_amount"]?.jsonPrimitive?.longOrNull,
                supportsDescription = if (mint) fields["description"]?.jsonPrimitive?.booleanOrNull else null,
                supportsAmountless = if (!mint) fields["amountless"]?.jsonPrimitive?.booleanOrNull else null,
            )
        }.sortedWith(compareBy<MintPaymentMethodSetting> { it.method.sortOrder }.thenBy { it.unit })
    }

    private fun nutSupported(nuts: JsonObject?, number: String, defaultWhenPresent: Boolean = true): Boolean {
        val nut = nuts?.get(number)?.jsonObject ?: return false
        if (nut["supported"]?.jsonPrimitive?.booleanOrNull == false) return false
        if (nut["disabled"]?.jsonPrimitive?.booleanOrNull == true) return false
        return defaultWhenPresent
    }

    private fun contacts(element: JsonElement?): List<MintContactInfo> {
        val array = runCatching { element?.jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { item ->
            val fields = runCatching { item.jsonArray }.getOrNull()
            if (fields != null && fields.size >= 2) {
                val method = fields[0].jsonPrimitive.contentOrNull ?: return@mapNotNull null
                val info = fields[1].jsonPrimitive.contentOrNull ?: return@mapNotNull null
                return@mapNotNull MintContactInfo(method, info)
            }
            val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
            val method = obj["method"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val info = obj["info"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            MintContactInfo(method, info)
        }
    }

    private fun softwareInfo(element: JsonElement?): MintSoftwareInfo {
        val fields = runCatching { element?.jsonObject }.getOrNull()
        if (fields != null) {
            return MintSoftwareInfo(
                name = fields["name"]?.jsonPrimitive?.contentOrNull,
                version = fields["version"]?.jsonPrimitive?.contentOrNull,
            )
        }
        val text = runCatching { element?.jsonPrimitive?.contentOrNull }
            .getOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: return MintSoftwareInfo()
        val name = text.substringBefore("/", missingDelimiterValue = "").takeIf { it.isNotBlank() }
        val version = text.substringAfter("/", missingDelimiterValue = text).takeIf { it.isNotBlank() }
        return MintSoftwareInfo(name = name, version = version)
    }

    private data class MintSoftwareInfo(
        val name: String? = null,
        val version: String? = null,
    )
}
