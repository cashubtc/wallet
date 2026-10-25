package com.cashu.me.Core.NfcReceive

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NfcNdefCodecTest {
    @Test
    fun `rejects invalid declared payload lengths`() {
        for (length in listOf(Int.MAX_VALUE, Int.MAX_VALUE - 6, Int.MIN_VALUE, -1, NfcType4Tag.MAX_NDEF_SIZE + 1)) {
            assertThrows(IllegalArgumentException::class.java) {
                NfcNdefCodec.parseType4File(file(longRecord(length)))
            }
        }
    }

    @Test
    fun `rejects incomplete types and payloads`() {
        val records = listOf(
            byteArrayOf(0xc1.toByte(), 1, 0),
            longRecord(0).copyOf(6),
            longRecord(4) + byteArrayOf(2, 'e'.code.toByte(), 'n'.code.toByte()),
            byteArrayOf(0xd1.toByte(), 2, 0, 'T'.code.toByte()),
            byteArrayOf(0xd1.toByte(), 1, 4, 'T'.code.toByte(), 2, 'e'.code.toByte(), 'n'.code.toByte()),
        )
        for (record in records) {
            assertThrows(IllegalArgumentException::class.java) { NfcNdefCodec.parseType4File(file(record)) }
        }
    }

    @Test
    fun `rejects incomplete type four files`() {
        for (file in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 0), byteArrayOf(0, 4, 0xd1.toByte()))) {
            assertThrows(IllegalArgumentException::class.java) { NfcNdefCodec.parseType4File(file) }
        }
    }

    @Test
    fun `accepts short and non-short text records`() {
        for (length in listOf(1, 252, 253, 400, NfcType4Tag.MAX_NDEF_SIZE - 10)) {
            val text = "x".repeat(length)
            assertEquals(NfcNdefPayload.Text(text), NfcNdefCodec.parseType4File(NfcNdefCodec.textFile(text)))
        }
    }

    @Test
    fun `accepts non-short encoding of a small payload`() {
        val payload = byteArrayOf(2, 'e'.code.toByte(), 'n'.code.toByte()) + "token".toByteArray()
        assertEquals(NfcNdefPayload.Text("token"), NfcNdefCodec.parseType4File(file(longRecord(payload.size) + payload)))
    }

    @Test
    fun `accepts binary records`() {
        val type = "application/octet-stream".toByteArray()
        val payload = ByteArray(300) { it.toByte() }
        val record = byteArrayOf(0xc2.toByte(), type.size.toByte()) +
            ByteBuffer.allocate(4).putInt(payload.size).array() + type + payload
        val decoded = NfcNdefCodec.parseType4File(file(record)) as NfcNdefPayload.CashuBinary
        assertArrayEquals(payload, decoded.bytes)
    }

    @Test
    fun `rejects a complete message above the supported size`() {
        val payload = byteArrayOf(2, 'e'.code.toByte(), 'n'.code.toByte()) +
            ByteArray(NfcType4Tag.MAX_NDEF_SIZE - 9) { 'x'.code.toByte() }
        assertThrows(IllegalArgumentException::class.java) {
            NfcNdefCodec.parseType4File(file(longRecord(payload.size) + payload))
        }
    }

    private fun longRecord(payloadLength: Int): ByteArray =
        byteArrayOf(0xc1.toByte(), 1) + ByteBuffer.allocate(4).putInt(payloadLength).array() +
            byteArrayOf('T'.code.toByte())

    private fun file(record: ByteArray): ByteArray =
        byteArrayOf((record.size ushr 8).toByte(), record.size.toByte()) + record
}
