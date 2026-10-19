package com.cashu.me.Core.Services

import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.Ndef
import com.cashu.me.Core.NfcReceive.NfcType4Reader
import java.io.Closeable

/** Keep the selected tag connected from request read through token commit. */
internal abstract class NfcPaymentTag : Closeable {
    abstract fun read(): String
    abstract fun requireWritable()
    abstract fun write(record: NdefRecord)

    companion object {
        fun open(tag: Tag): NfcPaymentTag {
            // Preserve standard NDEF tags and existing Android/Numo receivers.
            val ndef = Ndef.get(tag)
            if (ndef != null) {
                try { ndef.connect() } catch (error: Exception) {
                    runCatching { ndef.close() }
                    throw error
                }
                return object : NfcPaymentTag() {
                    override fun read(): String {
                        val message = ndef.ndefMessage ?: ndef.cachedNdefMessage ?: error("No readable data on tag.")
                        return decode(message)
                    }
                    override fun requireWritable() { require(ndef.isWritable) { "Payment tag is read-only." } }
                    override fun write(record: NdefRecord) {
                        requireWritable()
                        val message = NdefMessage(arrayOf(record))
                        require(ndef.maxSize >= message.toByteArray().size) { "Payment tag is too small for the token." }
                        ndef.writeNdefMessage(message)
                    }
                    override fun close() = ndef.close()
                }
            }
            // A custom-AID iPhone is ISO-DEP but deliberately isn't a standard
            // NDEF application, so Android's Ndef discovery cannot recognize it.
            val iso = IsoDep.get(tag) ?: error("Unsupported payment tag.")
            try {
                iso.connect()
                iso.timeout = 5_000
                val reader = NfcType4Reader(maxCommandPayload = iso.maxTransceiveLength - 5, exchange = iso::transceive)
                return object : NfcPaymentTag() {
                    override fun read() = decode(NdefMessage(reader.read()))
                    override fun requireWritable() { require(reader.isWritable) { "Payment tag is read-only." } }
                    override fun write(record: NdefRecord) = reader.write(NdefMessage(arrayOf(record)).toByteArray())
                    override fun close() = iso.close()
                }
            } catch (error: Exception) {
                runCatching { iso.close() }
                throw error
            }
        }

        private fun decode(message: NdefMessage): String =
            NFCReaderDelegate.decodeMessage(message).firstOrNull() ?: error("No readable data on tag.")
    }
}
