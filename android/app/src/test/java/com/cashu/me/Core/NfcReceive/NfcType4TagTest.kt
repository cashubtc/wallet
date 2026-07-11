package com.cashu.me.Core.NfcReceive

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NfcType4TagTest {
    @Test
    fun `reader selects and reads payment request`() {
        val events = mutableListOf<NfcType4Event>()
        val request = NfcNdefCodec.textFile("creqA-request")
        val tag = NfcType4Tag({ request }, events::add)

        assertOk(tag.process(selectAid()))
        assertOk(tag.process(selectNdef()))
        val response = tag.process(read(offset = 0, length = request.size))

        assertArrayEquals(request, response.dropLast(2).toByteArray())
        assertEquals(NfcType4Event.Connected, events.first())
    }

    @Test
    fun `numo zero-length then body then final length yields text token`() {
        val events = mutableListOf<NfcType4Event>()
        val tag = NfcType4Tag({ NfcNdefCodec.textFile("creqA-request") }, events::add)
        val tokenFile = NfcNdefCodec.textFile("cashuBtoken_payload")

        assertOk(tag.process(selectAid()))
        assertOk(tag.process(selectNdef()))
        assertOk(tag.process(update(0, byteArrayOf(0, 0))))
        tokenFile.copyOfRange(2, tokenFile.size).toList().chunked(17).fold(2) { offset, chunk ->
            assertOk(tag.process(update(offset, chunk.toByteArray())))
            offset + chunk.size
        }
        assertOk(tag.process(update(0, tokenFile.copyOfRange(0, 2))))

        val received = events.filterIsInstance<NfcType4Event.MessageReceived>().single().payload
        assertEquals(NfcNdefPayload.Text("cashuBtoken_payload"), received)
        assertEquals(1, events.count { it == NfcType4Event.WritingStarted })
    }

    @Test
    fun `header first chunked write yields uri token`() {
        val events = mutableListOf<NfcType4Event>()
        val tag = NfcType4Tag({ NfcNdefCodec.textFile("creqA-request") }, events::add)
        val file = uriFile("cashu.me/#token=cashuAtoken")

        assertOk(tag.process(selectAid()))
        assertOk(tag.process(selectNdef()))
        file.toList().chunked(11).fold(0) { offset, chunk ->
            assertOk(tag.process(update(offset, chunk.toByteArray())))
            offset + chunk.size
        }

        val received = events.filterIsInstance<NfcType4Event.MessageReceived>().single().payload
        assertEquals(NfcNdefPayload.Text("https://cashu.me/#token=cashuAtoken"), received)
    }

    @Test
    fun `write outside advertised file is rejected`() {
        val tag = NfcType4Tag({ NfcNdefCodec.textFile("request") }) {}
        assertOk(tag.process(selectAid()))
        assertOk(tag.process(selectNdef()))
        val response = tag.process(update(NfcType4Tag.MAX_NDEF_SIZE + 2, byteArrayOf(1)))
        assertArrayEquals(byteArrayOf(0x6B, 0x00), response)
    }

    @Test
    fun `long text request uses non-short ndef record`() {
        val file = NfcNdefCodec.textFile("x".repeat(400))
        val payload = NfcNdefCodec.parseType4File(file)
        assertEquals(NfcNdefPayload.Text("x".repeat(400)), payload)
        assertTrue(file.size > 400)
    }

    private fun assertOk(response: ByteArray) = assertArrayEquals(byteArrayOf(0x90.toByte(), 0), response)

    private fun selectAid() = byteArrayOf(
        0x00, 0xA4.toByte(), 0x04, 0x00, 0x07,
        0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01, 0x00,
    )

    private fun selectNdef() = byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02, 0xE1.toByte(), 0x04)

    private fun read(offset: Int, length: Int) = byteArrayOf(
        0x00, 0xB0.toByte(), (offset ushr 8).toByte(), offset.toByte(), length.toByte(),
    )

    private fun update(offset: Int, data: ByteArray) = byteArrayOf(
        0x00, 0xD6.toByte(), (offset ushr 8).toByte(), offset.toByte(), data.size.toByte(),
    ) + data

    private fun uriFile(uriWithoutPrefix: String): ByteArray {
        val type = byteArrayOf('U'.code.toByte())
        val payload = byteArrayOf(0x04) + uriWithoutPrefix.toByteArray()
        val record = byteArrayOf(0xD1.toByte(), type.size.toByte(), payload.size.toByte()) + type + payload
        return byteArrayOf((record.size ushr 8).toByte(), record.size.toByte()) + record
    }
}
