package com.cashu.me.Core.NfcReceive

import org.junit.Assert.*
import org.junit.Test

class NfcType4ReaderTest {
    /** Android's existing Numo receiver is the protocol oracle after AID selection. */
    private class Peer(request: String = "creqA".repeat(100)) {
        val commands = mutableListOf<ByteArray>()
        val received = mutableListOf<NfcNdefPayload>()
        private val tag = NfcType4Tag({ NfcNdefCodec.textFile(request) }) {
            if (it is NfcType4Event.MessageReceived) received += it.payload
        }
        fun exchange(command: ByteArray): ByteArray {
            commands += command
            val selected = if (command[1] == 0xA4.toByte() && command[2].toInt() == 4) {
                assertArrayEquals(byteArrayOf(0, 0xA4.toByte(), 4, 0, 8,
                    0xF9.toByte(), 0x99.toByte(), 0x71, 0x15, 0x10, 0x41, 0x17, 0, 0), command)
                byteArrayOf(0, 0xA4.toByte(), 4, 0, 7, 0xD2.toByte(), 0x76, 0, 0, 0x85.toByte(), 1, 1, 0)
            } else command
            return tag.process(selected)
        }
    }

    @Test fun `custom AID reads and commits using Android Numo protocol`() {
        val peer = Peer()
        val reader = NfcType4Reader(maxCommandPayload = 17, exchange = peer::exchange)
        assertArrayEquals(NfcNdefCodec.textFile("creqA".repeat(100)).drop(2).toByteArray(), reader.read())
        val token = "cashuB".repeat(100)
        reader.write(NfcNdefCodec.textFile(token).drop(2).toByteArray())
        assertEquals(listOf(NfcNdefPayload.Text(token)), peer.received)
        val writes = peer.commands.filter { it[1] == 0xD6.toByte() }
        assertArrayEquals(byteArrayOf(0, 0xD6.toByte(), 0, 0, 2, 0, 0), writes.first())
        assertTrue(writes.all { (it[4].toInt() and 0xff) <= 17 })
        assertArrayEquals(byteArrayOf(0, 0xD6.toByte(), 0, 0, 2), writes.last().take(5).toByteArray())
    }

    @Test fun `binary token record uses the same commit protocol`() {
        val peer = Peer()
        val reader = NfcType4Reader(exchange = peer::exchange)
        reader.read()
        val mime = "application/octet-stream".toByteArray()
        val payload = byteArrayOf(0, 1, 2)
        reader.write(byteArrayOf(0xD2.toByte(), mime.size.toByte(), 3) + mime + payload)
        assertArrayEquals(payload, (peer.received.single() as NfcNdefPayload.CashuBinary).bytes)
    }

    @Test fun `read only and capacity limits prevent token writes`() {
        for (readOnly in listOf(true, false)) {
            val peer = Peer("creqA")
            val reader = NfcType4Reader { command ->
                peer.exchange(command).also { response ->
                    if (command.contentEquals(byteArrayOf(0, 0xB0.toByte(), 0, 0, 15))) {
                        if (readOnly) response[14] = 0xff.toByte()
                        else { response[11] = 0; response[12] = 32 }
                    }
                }
            }
            reader.read()
            assertThrows(IllegalArgumentException::class.java) { reader.write(ByteArray(40)) }
            assertTrue(peer.commands.none { it[1] == 0xD6.toByte() })
        }
    }

    @Test fun `truncated and error responses never become a payment request`() {
        for (response in listOf(byteArrayOf(0x6A, 0x82.toByte()), byteArrayOf(0x90.toByte()), byteArrayOf(0x90.toByte(), 0))) {
            val reader = NfcType4Reader { response }
            assertThrows(IllegalArgumentException::class.java) { reader.read() }
        }
    }

    @Test fun `lost final acknowledgment is never automatically retried`() {
        val peer = Peer()
        val reader = NfcType4Reader { command ->
            val response = peer.exchange(command)
            if (peer.received.isNotEmpty()) throw java.io.IOException("Connection lost after persistence")
            response
        }
        reader.read()
        assertThrows(java.io.IOException::class.java) { reader.write(NfcNdefCodec.textFile("cashuBtest").drop(2).toByteArray()) }
        assertEquals(1, peer.received.size)
    }
}
