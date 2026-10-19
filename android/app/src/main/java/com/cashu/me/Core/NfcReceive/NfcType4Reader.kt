package com.cashu.me.Core.NfcReceive

/** Payer for the Apple-approved application; NDEF and APDUs remain Numo compatible. */
internal class NfcType4Reader(
    private val maxCommandPayload: Int = 255,
    private val exchange: (ByteArray) -> ByteArray,
) {
    private var readSize = 0
    private var writeSize = 0
    private var capacity = 0
    var isWritable = false
        private set

    fun read(): ByteArray {
        capacity = 0
        isWritable = false
        command(byteArrayOf(0, 0xA4.toByte(), 4, 0, AID.size.toByte()) + AID + byteArrayOf(0))
        command(byteArrayOf(0, 0xA4.toByte(), 0, 0x0C, 2, 0xE1.toByte(), 3))
        val cc = readBytes(0, 15)
        require(number(cc, 0) >= 15 && (cc[2].toInt() and 0xff) ushr 4 == 2 &&
            cc[7].toInt() == 4 && cc[8].toInt() == 6 && cc[13].toInt() == 0) { "Unsupported payment tag." }
        readSize = minOf(number(cc, 3), 255)
        writeSize = minOf(number(cc, 5), 255, maxCommandPayload)
        capacity = minOf(number(cc, 11), NfcType4Tag.MAX_NDEF_SIZE)
        isWritable = cc[14].toInt() == 0 && writeSize >= 2
        require(readSize >= 2 && capacity >= 2) { "Invalid payment tag limits." }
        command(byteArrayOf(0, 0xA4.toByte(), 0, 0x0C, 2, cc[9], cc[10]))
        val length = number(readBytes(0, 2), 0)
        require(length > 0 && length <= capacity - 2) { "Invalid payment request length." }
        val body = ByteArray(length)
        var offset = 0
        while (offset < length) {
            val chunk = readBytes(offset + 2, minOf(readSize, length - offset))
            chunk.copyInto(body, offset)
            offset += chunk.size
        }
        return body
    }

    fun write(body: ByteArray) {
        require(isWritable && body.isNotEmpty() && body.size <= capacity - 2) {
            "Payment tag is read-only or the token is too large."
        }
        // Commit NLEN last. Do not retry a token after an ambiguous final response.
        update(0, byteArrayOf(0, 0))
        var offset = 0
        while (offset < body.size) {
            val end = minOf(offset + writeSize, body.size)
            update(offset + 2, body.copyOfRange(offset, end))
            offset = end
        }
        update(0, byteArrayOf((body.size ushr 8).toByte(), body.size.toByte()))
    }

    private fun update(offset: Int, bytes: ByteArray) {
        command(byteArrayOf(0, 0xD6.toByte(), (offset ushr 8).toByte(), offset.toByte(), bytes.size.toByte()) + bytes)
    }

    private fun readBytes(offset: Int, count: Int): ByteArray {
        val result = command(byteArrayOf(0, 0xB0.toByte(), (offset ushr 8).toByte(), offset.toByte(), count.toByte()))
        require(result.size == count) { "Incomplete payment tag response." }
        return result
    }

    private fun command(bytes: ByteArray): ByteArray {
        val response = exchange(bytes)
        require(response.size >= 2 && response[response.size - 2] == 0x90.toByte() && response.last() == 0.toByte()) {
            "The payment tag rejected the transfer. Keep the devices together and try again."
        }
        return response.copyOfRange(0, response.size - 2)
    }

    private fun number(bytes: ByteArray, offset: Int) =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    companion object {
        val AID = byteArrayOf(0xF9.toByte(), 0x99.toByte(), 0x71, 0x15, 0x10, 0x41, 0x17, 0)
    }
}
