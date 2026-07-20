package com.cashu.me.Core

import com.cashu.me.Core.Errors.NostrErrorReport
import com.cashu.me.Core.Errors.NostrReportReceipt
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

data class NostrReportRecipient(val pubkeyHex: String, val relays: List<String>)

/** Anonymous NIP-17/NIP-44 delivery used only after explicit user approval. */
object NostrErrorTransport {
    private val random = SecureRandom()
    private val json = Json { encodeDefaults = true; explicitNulls = false }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun parseNprofile(value: String): NostrReportRecipient? = runCatching {
        val bytes = Bech32.decode("nprofile", value)
        var offset = 0
        var pubkey: String? = null
        val relays = mutableListOf<String>()
        while (offset + 2 <= bytes.size) {
            val type = bytes[offset++].toInt() and 0xff
            val length = bytes[offset++].toInt() and 0xff
            require(offset + length <= bytes.size) { "Invalid nprofile TLV." }
            val field = bytes.copyOfRange(offset, offset + length)
            offset += length
            when (type) {
                0 -> if (field.size == 32 && pubkey == null) pubkey = field.toHex()
                1 -> field.toString(Charsets.UTF_8)
                    .takeIf { it.startsWith("wss://") }
                    ?.let(relays::add)
            }
        }
        require(offset == bytes.size && pubkey != null) { "Invalid nprofile." }
        val distinctRelays = relays.distinct().take(3)
        require(distinctRelays.isNotEmpty()) { "The support nprofile needs relay hints." }
        NostrReportRecipient(pubkey!!, distinctRelays)
    }.getOrNull()

    suspend fun send(recipient: NostrReportRecipient, report: NostrErrorReport): NostrReportReceipt {
        val reportJson = json.encodeToString(report)
        val giftWrap = createGiftWrap(reportJson, recipient.pubkeyHex)
        val eventJson = json.encodeToString(giftWrap)
        val accepted = coroutineScope {
            recipient.relays.map { relay -> async { publish(relay, giftWrap.id, eventJson) } }.awaitAll()
        }
        val acceptedCount = accepted.count { it }
        check(acceptedCount > 0) { "No Nostr relay accepted the report." }
        return NostrReportReceipt(
            eventId = giftWrap.id,
            acceptedRelays = acceptedCount.toUInt(),
            failedRelays = (accepted.size - acceptedCount).toUInt(),
        )
    }

    internal fun createGiftWrap(plaintext: String, recipientPubkeyHex: String): NostrIncomingEvent {
        require(recipientPubkeyHex.length == 64) { "Invalid recipient public key." }
        val senderKey = randomSecret()
        val senderPubkey = NostrService.publicKeyXOnly(senderKey).toHex()
        val now = System.currentTimeMillis() / 1_000L
        val rumorTags = listOf(listOf("p", recipientPubkeyHex))
        val rumorId = NostrService.calculateEventId(senderPubkey, now, 14, rumorTags, plaintext)
        val rumorJson = unsignedEventJson(rumorId, senderPubkey, now, 14, rumorTags, plaintext)

        val sealContent = NIP44.encrypt(rumorJson, senderKey, recipientPubkeyHex)
        val seal = signedEvent(senderKey, now, 13, emptyList(), sealContent)
        val sealJson = json.encodeToString(seal)

        val giftKey = randomSecret()
        val giftContent = NIP44.encrypt(sealJson, giftKey, recipientPubkeyHex)
        val backdated = now - random.nextInt(172_801)
        return signedEvent(giftKey, backdated, 1059, listOf(listOf("p", recipientPubkeyHex)), giftContent)
    }

    private fun signedEvent(
        privateKey: ByteArray,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): NostrIncomingEvent {
        val pubkey = NostrService.publicKeyXOnly(privateKey).toHex()
        val id = NostrService.calculateEventId(pubkey, createdAt, kind, tags, content)
        val signature = NostrService.schnorrSign(NostrService.hexToBytes(id), privateKey, ByteArray(32).also(random::nextBytes)).toHex()
        return NostrIncomingEvent(id, pubkey, createdAt, kind, tags, content, signature)
    }

    private fun unsignedEventJson(
        id: String,
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
    ): String = json.encodeToString(
        buildJsonObject {
            put("id", id)
            put("pubkey", pubkey)
            put("created_at", createdAt)
            put("kind", kind)
            put("tags", buildJsonArray { tags.forEach { tag -> add(JsonArray(tag.map(::JsonPrimitive))) } })
            put("content", content)
        },
    )

    private fun randomSecret(): ByteArray {
        while (true) {
            val candidate = ByteArray(32).also(random::nextBytes)
            if (runCatching { NostrService.publicKeyXOnly(candidate) }.isSuccess) return candidate
        }
    }

    private suspend fun publish(relay: String, eventId: String, eventJson: String): Boolean {
        val result = CompletableDeferred<Boolean>()
        val request = runCatching { Request.Builder().url(relay).build() }.getOrNull() ?: return false
        val socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!webSocket.send("[\"EVENT\",$eventJson]")) result.complete(false)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val message = runCatching { json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return
                if (message.size >= 3 && message[0].jsonPrimitive.content == "OK" &&
                    message[1].jsonPrimitive.content == eventId
                ) {
                    result.complete(message[2].jsonPrimitive.content.toBooleanStrictOrNull() == true)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                result.complete(false)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                result.complete(false)
            }
        })
        return try {
            withTimeoutOrNull(12_000) { result.await() } ?: false
        } finally {
            socket.cancel()
        }
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
