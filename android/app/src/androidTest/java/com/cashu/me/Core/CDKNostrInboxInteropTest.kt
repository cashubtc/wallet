package com.cashu.me.Core

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.io.EOFException
import java.net.SocketException
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.cashudevkit.FfiException
import org.cashudevkit.NostrInboxEvent
import org.cashudevkit.nip44Decrypt
import org.cashudevkit.nip44Encrypt
import org.cashudevkit.nostrGetPubkey
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class CDKNostrInboxInteropTest {
    private fun fixture(): JSONObject = InstrumentationRegistry.getInstrumentation().context.assets
        .open("nostr-inbox.json").bufferedReader().use { JSONObject(it.readText()) }

    @Test
    fun independentNip44VectorRejectsWrongKeyMalformedPayloadAndBadMac() {
        val f = fixture()
        val secret = f.getString("recipient_secret")
        val sender = f.getString("sender_public")
        val payload = f.getString("ciphertext")
        val plaintext = f.getString("plaintext")
        assertEquals(plaintext, nip44Decrypt(secret, sender, payload))
        assertTrue(runCatching { nip44Decrypt(f.getString("wrong_secret"), sender, payload) }.isFailure)
        assertTrue(runCatching { nip44Decrypt(secret, sender, "not-base64") }.isFailure)
        val badMac = Base64.getDecoder().decode(payload)
        badMac[badMac.lastIndex] = (badMac.last().toInt() xor 1).toByte()
        assertTrue(runCatching { nip44Decrypt(secret, sender, Base64.getEncoder().encodeToString(badMac)) }.isFailure)
        val encrypted = nip44Encrypt(secret, sender, plaintext)
        assertEquals(plaintext, nip44Decrypt("0".repeat(63) + "1", nostrGetPubkey(secret), encrypted))
    }

    @Test
    fun invalidInboxStartReportsFailureAndRetires() = runBlocking {
        val failed = CompletableDeferred<Unit>()
        val inbox = CDKCashuRequestInbox(
            secretKey = "invalid", relays = listOf("wss://relay.example.com"), since = 0,
            onFailure = { failed.complete(Unit) },
        ) { error("invalid inbox delivered an event") }
        try {
            inbox.start()
            withTimeout(5_000) { failed.await() }
        } finally {
            inbox.stopAndJoin()
        }
    }

    @Test
    fun realCdkDropsInvalidWrapsAndReconnectsWithFixedLookback() = runBlocking {
        val f = fixture()
        Relay(f).use { relay ->
            val delivered = CompletableDeferred<Unit>()
            val received = CopyOnWriteArrayList<NostrInboxEvent>()
            val inbox = CDKCashuRequestInbox(
                secretKey = f.getString("recipient_secret"),
                relays = listOf("ws://127.0.0.1:${relay.port}"), since = 1_699_500_000,
                onFailure = { delivered.completeExceptionally(AssertionError("inbox start failed")) },
            ) { event ->
                received += event
                if (received.size >= 2) delivered.complete(Unit)
            }
            try {
                inbox.start()
                try {
                    withTimeout(30_000) { delivered.await() }
                } catch (error: Exception) {
                    throw AssertionError("Relay filters=${relay.filters}; received=${received.map { it.wrapId }}; server=${relay.failure}", error)
                }
            } finally {
                inbox.stopAndJoin()
            }
            assertEquals(setOf(f.getJSONObject("valid").getString("id"),
                f.getJSONObject("valid_second").getString("id")), received.map { it.wrapId }.toSet())
            assertTrue(received.all { it.rumorKind == 14.toUShort() && it.rumorContent == f.getString("payload") })
            assertTrue(relay.filters.size >= 2)
            assertTrue(relay.filters.all { it.getLong("since") == 1_699_500_000L })
            assertTrue(relay.filters.all { it.getJSONArray("kinds").getInt(0) == 1059 })
        }
    }

    /** Minimal loopback RFC-6455 test relay; drops the first subscription. */
    private class Relay(private val fixtures: JSONObject) : Closeable {
        private val server = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        private val sockets = CopyOnWriteArrayList<Socket>()
        val filters = CopyOnWriteArrayList<JSONObject>()
        val port: Int get() = server.localPort
        @Volatile var failure: Throwable? = null
        private val worker = thread(isDaemon = true, name = "inbox-test-relay") {
            runCatching {
                while (!server.isClosed) {
                    val socket = server.accept().also { sockets += it }
                    try {
                        serve(socket)
                    } catch (_: EOFException) {
                        // Client disconnect; continue accepting reconnects.
                    } catch (_: SocketException) {
                        // Socket closed during fixture teardown or reconnect.
                    }
                }
            }.onFailure { if (!server.isClosed) failure = it }
        }

        private fun serve(socket: Socket) {
            socket.soTimeout = 30_000
            val input = DataInputStream(socket.getInputStream())
            // Buffered so the handshake leaves in one write. Unbuffered writeBytes
            // sends one byte per write, and tungstenite rejects a handshake read in
            // many tiny chunks ("Attack attempt detected"), adding a 7-13 s retry.
            val output = DataOutputStream(socket.getOutputStream().buffered())
            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) header.append(input.readUnsignedByte().toChar())
            val key = header.lines().first { it.startsWith("Sec-WebSocket-Key:", ignoreCase = true) }
                .substringAfter(':').trim()
            val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
            output.writeBytes("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n")
            output.flush()
            while (!socket.isClosed) {
                val opcode = input.readUnsignedByte() and 15
                val size = input.readUnsignedByte()
                val count = when (size and 127) {
                    126 -> input.readUnsignedShort()
                    127 -> input.readLong().toInt()
                    else -> size and 127
                }
                require(count in 0..65_536)
                val mask = if (size and 128 != 0) ByteArray(4).also(input::readFully) else null
                val data = ByteArray(count).also(input::readFully)
                if (mask != null) data.indices.forEach { data[it] = (data[it].toInt() xor mask[it % 4].toInt()).toByte() }
                if (opcode != 1) continue
                val message = JSONArray(String(data, Charsets.UTF_8))
                if (message.optString(0) != "REQ") continue
                filters += message.getJSONObject(2)
                val first = filters.size == 1
                val names = if (first) listOf("malformed", "invalid_mac", "invalid_seal", "valid")
                    else listOf("valid", "valid_second")
                names.forEach { send(output, JSONArray().put("EVENT").put(message.getString(1)).put(fixtures.getJSONObject(it))) }
                send(output, JSONArray().put("EOSE").put(message.getString(1)))
                if (first) {
                    Thread.sleep(500)
                    socket.close()
                }
            }
        }

        private fun send(output: DataOutputStream, message: JSONArray) {
            val data = message.toString().toByteArray()
            output.writeByte(0x81)
            if (data.size < 126) output.writeByte(data.size) else {
                output.writeByte(126)
                output.writeShort(data.size)
            }
            output.write(data)
            output.flush()
        }

        override fun close() {
            server.close()
            sockets.forEach { it.close() }
            worker.join(2_000)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class CDKNIP44BoundaryTest {
    private val alice = "1".padStart(64, '0')
    private val bob = "2".padStart(64, '0')

    @Test
    fun nip44EncryptDecryptRoundTripBetweenTwoKeys() {
        val senderPrivateHex = "1".padStart(64, '0')
        val recipientPrivateHex = "2".padStart(64, '0')
        val recipientPubkey = nostrGetPubkey(recipientPrivateHex)

        val payload = nip44Encrypt(
            plaintext = """{"hello":"cashu"}""",
            nostrSecretKey = senderPrivateHex,
            recipientPubkey = recipientPubkey,
        )

        assertEquals(
            """{"hello":"cashu"}""",
            nip44Decrypt(
                payload = payload,
                nostrSecretKey = recipientPrivateHex,
                senderPubkey = nostrGetPubkey(senderPrivateHex),
            ),
        )
    }

    // Fixed payloads from https://github.com/paulmillr/nip44/blob/main/nip44.vectors.json
    // These tests use the shipped Android native library, not a mock JVM implementation.
    @Test
    fun officialVectors() {
        assertEquals("a", nip44Decrypt("0000000000000000000000000000000000000000000000000000000000000002", nostrGetPubkey("0000000000000000000000000000000000000000000000000000000000000001"), "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"))
        assertEquals("🍕🫃", nip44Decrypt("0000000000000000000000000000000000000000000000000000000000000001", nostrGetPubkey("0000000000000000000000000000000000000000000000000000000000000002"), "AvAAAAAAAAAAAAAAAAAAAPAAAAAAAAAAAAAAAAAAAAAPSKSK6is9ngkX2+cSq85Th16oRTISAOfhStnixqZziKMDvB0QQzgFZdjLTPicCJaV8nDITO+QfaQ61+KbWQIOO2Yj"))
        assertEquals("表ポあA鷗ŒéＢ逍Üßªąñ丂㐀𠀀", nip44Decrypt("4b22aa260e4acb7021e32f38a6cdf4b673c6a277755bfce287e370c924dc936d", nostrGetPubkey("5c0c523f52a5b6fad39ed2403092df8cebc36318b39383bca6c00808626fab3a"), "ArY1I2xC2yDwIbuNHN/1ynXdGgzHLqdCrXUPMwELJPc7s7JqlCMJBAIIjfkpHReBPXeoMCyuClwgbT419jUWU1PwaNl4FEQYKCDKVJz+97Mp3K+Q2YGa77B6gpxB/lr1QgoqpDf7wDVrDmOqGoiPjWDqy8KzLueKDcm9BVP8xeTJIxs="))
    }

    @Test
    fun roundTripsAcrossPaddingBoundaries() {
        for (text in listOf(" ", "こんにちは世界 🌍", "A".repeat(33), "B".repeat(257), "C".repeat(2_000), "D".repeat(65_408))) {
            val payload = nip44Encrypt(alice, nostrGetPubkey(bob), text)
            assertEquals(text, nip44Decrypt(bob, nostrGetPubkey(alice), payload))
        }
    }

    @Test
    fun rejectsMalformedPayloadsWrongKeysAndTampering() {
        val sender = nostrGetPubkey(alice)
        val payload = nip44Encrypt(alice, nostrGetPubkey(bob), "secret")
        for (invalid in listOf("", "not-base64!!!", "dG9vc2hvcnQ=")) {
            assertThrows(FfiException::class.java) { nip44Decrypt(bob, sender, invalid) }
        }
        assertThrows(FfiException::class.java) { nip44Decrypt("3".padStart(64, '0'), sender, payload) }
        val raw = Base64.getDecoder().decode(payload)
        // Version, nonce, ciphertext and authentication tag.
        for (offset in listOf(0, 1, 50, raw.lastIndex)) {
            val corrupt = raw.copyOf()
            corrupt[offset] = (corrupt[offset].toInt() xor 1).toByte()
            assertThrows(FfiException::class.java) { nip44Decrypt(bob, sender, Base64.getEncoder().encodeToString(corrupt)) }
        }
    }

    @Test
    fun rejectsInvalidKeysAndPlaintextLengths() {
        val pubkey = nostrGetPubkey(bob)
        for (key in listOf("", "invalid", "0".repeat(64), "f".repeat(64))) {
            assertThrows(FfiException::class.java) { nip44Encrypt(key, pubkey, "test") }
        }
        for (key in listOf("", "79be", "f".repeat(64))) {
            assertThrows(FfiException::class.java) { nip44Encrypt(alice, key, "test") }
        }
        for (text in listOf("", "A".repeat(65_409), "A".repeat(65_535), "A".repeat(65_536))) {
            assertThrows(FfiException::class.java) { nip44Encrypt(alice, pubkey, text) }
        }
    }
}
