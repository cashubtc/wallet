package com.cashu.me.Core.NfcReceive

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

internal sealed interface NfcNdefPayload {
    data class Text(val value: String) : NfcNdefPayload
    data class CashuBinary(val bytes: ByteArray) : NfcNdefPayload
}

/** Pure NDEF codec for the single-record messages used by the Numo protocol. */
internal object NfcNdefCodec {
    private const val TNF_WELL_KNOWN = 0x01
    private const val TNF_MIME_MEDIA = 0x02
    private const val FLAG_MB = 0x80
    private const val FLAG_ME = 0x40
    private const val FLAG_CF = 0x20
    private const val FLAG_SR = 0x10
    private const val FLAG_IL = 0x08
    private const val TNF_MASK = 0x07
    private const val CASHU_BINARY_MIME = "application/octet-stream"

    fun textFile(text: String): ByteArray {
        val language = "en".toByteArray(StandardCharsets.US_ASCII)
        val textBytes = text.toByteArray(StandardCharsets.UTF_8)
        val payload = ByteArray(1 + language.size + textBytes.size)
        payload[0] = language.size.toByte()
        language.copyInto(payload, 1)
        textBytes.copyInto(payload, 1 + language.size)
        return type4File(type = byteArrayOf('T'.code.toByte()), payload = payload, tnf = TNF_WELL_KNOWN)
    }

    fun parseType4File(file: ByteArray): NfcNdefPayload {
        require(file.size >= 2) { "NDEF file is incomplete." }
        val nlen = ((file[0].toInt() and 0xff) shl 8) or (file[1].toInt() and 0xff)
        require(nlen > 0 && nlen + 2 <= file.size) { "NDEF length is invalid." }
        return parseRecord(file.copyOfRange(2, nlen + 2))
    }

    private fun type4File(type: ByteArray, payload: ByteArray, tnf: Int): ByteArray {
        val short = payload.size <= 0xff
        val headerSize = if (short) 3 else 6
        val record = ByteArray(headerSize + type.size + payload.size)
        record[0] = (FLAG_MB or FLAG_ME or (if (short) FLAG_SR else 0) or tnf).toByte()
        record[1] = type.size.toByte()
        var cursor: Int
        if (short) {
            record[2] = payload.size.toByte()
            cursor = 3
        } else {
            ByteBuffer.wrap(record, 2, 4).putInt(payload.size)
            cursor = 6
        }
        type.copyInto(record, cursor)
        cursor += type.size
        payload.copyInto(record, cursor)
        require(record.size <= NfcType4Tag.MAX_NDEF_SIZE) { "NDEF message is too large." }
        return ByteArray(record.size + 2).also {
            it[0] = (record.size ushr 8).toByte()
            it[1] = record.size.toByte()
            record.copyInto(it, 2)
        }
    }

    private fun parseRecord(record: ByteArray): NfcNdefPayload {
        require(record.size >= 3) { "NDEF record is incomplete." }
        val header = record[0].toInt() and 0xff
        require(header and FLAG_MB != 0 && header and FLAG_ME != 0) { "Only one complete NDEF record is supported." }
        require(header and FLAG_CF == 0 && header and FLAG_IL == 0) { "Chunked or identified NDEF records are unsupported." }
        val typeLength = record[1].toInt() and 0xff
        require(typeLength > 0) { "NDEF record type is missing." }
        val short = header and FLAG_SR != 0
        val payloadLength: Int
        var cursor: Int
        if (short) {
            payloadLength = record[2].toInt() and 0xff
            cursor = 3
        } else {
            require(record.size >= 6) { "NDEF payload length is incomplete." }
            payloadLength = ByteBuffer.wrap(record, 2, 4).int
            require(payloadLength >= 0) { "NDEF payload length is invalid." }
            cursor = 6
        }
        require(cursor + typeLength + payloadLength <= record.size) { "NDEF payload is incomplete." }
        val type = record.copyOfRange(cursor, cursor + typeLength)
        cursor += typeLength
        val payload = record.copyOfRange(cursor, cursor + payloadLength)
        return when (header and TNF_MASK) {
            TNF_WELL_KNOWN -> parseWellKnown(type, payload)
            TNF_MIME_MEDIA -> {
                require(String(type, StandardCharsets.US_ASCII).equals(CASHU_BINARY_MIME, ignoreCase = true)) {
                    "Unsupported NDEF MIME record."
                }
                NfcNdefPayload.CashuBinary(payload)
            }
            else -> throw IllegalArgumentException("Unsupported NDEF record type.")
        }
    }

    private fun parseWellKnown(type: ByteArray, payload: ByteArray): NfcNdefPayload.Text {
        require(type.size == 1 && payload.isNotEmpty()) { "Unsupported NDEF well-known record." }
        return when (type[0].toInt().toChar()) {
            'T' -> {
                val status = payload[0].toInt() and 0xff
                val languageLength = status and 0x3f
                require(status and 0x80 == 0) { "UTF-16 NDEF text is unsupported." }
                require(1 + languageLength <= payload.size) { "NDEF language code is invalid." }
                NfcNdefPayload.Text(String(payload, 1 + languageLength, payload.size - 1 - languageLength, StandardCharsets.UTF_8))
            }
            'U' -> {
                val prefix = uriPrefix(payload[0].toInt() and 0xff)
                NfcNdefPayload.Text(prefix + String(payload, 1, payload.size - 1, StandardCharsets.UTF_8))
            }
            else -> throw IllegalArgumentException("Unsupported NDEF well-known record.")
        }
    }

    private fun uriPrefix(code: Int): String = when (code) {
        0x01 -> "http://www."
        0x02 -> "https://www."
        0x03 -> "http://"
        0x04 -> "https://"
        0x05 -> "tel:"
        0x06 -> "mailto:"
        0x0d -> "ftp://"
        0x13 -> "urn:"
        0x15 -> "sip:"
        0x16 -> "sips:"
        0x1d -> "file://"
        0x23 -> "urn:nfc:"
        else -> ""
    }
}
