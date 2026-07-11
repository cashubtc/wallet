package com.cashu.me.Core.NfcReceive

internal sealed interface NfcType4Event {
    data object Connected : NfcType4Event
    data object WritingStarted : NfcType4Event
    data class MessageReceived(val payload: NfcNdefPayload) : NfcType4Event
}

/** NFC Forum Type 4 tag state machine compatible with Numo's HCE protocol. */
internal class NfcType4Tag(
    private val requestFile: () -> ByteArray?,
    private val onEvent: (NfcType4Event) -> Unit,
) {
    private enum class SelectedFile { NONE, CC, NDEF }

    private var selected = SelectedFile.NONE
    private var outgoing = ByteArray(0)
    private val incoming = ByteArray(MAX_NDEF_SIZE + 2)
    private val written = BooleanArray(MAX_NDEF_SIZE + 2)
    private var expectedLength = -1
    private var writingStarted = false

    @Synchronized
    fun process(command: ByteArray): ByteArray {
        if (isSelectAid(command)) {
            resetExchange()
            onEvent(NfcType4Event.Connected)
            return OK
        }
        if (command.size < 2) return WRONG_LENGTH
        return when (command[1].toInt() and 0xff) {
            0xA4 -> selectFile(command)
            0xB0 -> readBinary(command)
            0xD6 -> updateBinary(command)
            else -> INSTRUCTION_NOT_SUPPORTED
        }
    }

    @Synchronized
    fun deactivate() {
        selected = SelectedFile.NONE
        outgoing = ByteArray(0)
        resetIncoming()
    }

    private fun selectFile(command: ByteArray): ByteArray {
        if (command.size < 7 || !command.copyOfRange(0, 4).contentEquals(SELECT_FILE_HEADER) || command[4].toInt() != 2) {
            return WRONG_LENGTH
        }
        return when {
            command.copyOfRange(5, 7).contentEquals(CC_FILE_ID) -> {
                selected = SelectedFile.CC
                outgoing = CC_FILE
                OK
            }
            command.copyOfRange(5, 7).contentEquals(NDEF_FILE_ID) -> {
                val file = requestFile() ?: return FILE_NOT_FOUND
                selected = SelectedFile.NDEF
                outgoing = file
                OK
            }
            else -> FILE_NOT_FOUND
        }
    }

    private fun readBinary(command: ByteArray): ByteArray {
        if (command.size < 5 || selected == SelectedFile.NONE) return WRONG_LENGTH
        val offset = ((command[2].toInt() and 0xff) shl 8) or (command[3].toInt() and 0xff)
        val requested = (command[4].toInt() and 0xff).let { if (it == 0) 256 else it }
        if (offset > outgoing.size) return WRONG_PARAMETERS
        val end = minOf(offset + requested, outgoing.size)
        return outgoing.copyOfRange(offset, end) + OK
    }

    private fun updateBinary(command: ByteArray): ByteArray {
        if (selected != SelectedFile.NDEF || command.size < 5) return FILE_NOT_FOUND
        val offset = ((command[2].toInt() and 0xff) shl 8) or (command[3].toInt() and 0xff)
        val length = command[4].toInt() and 0xff
        if (command.size != 5 + length) return WRONG_LENGTH
        if (offset + length > incoming.size) return WRONG_PARAMETERS
        if (!writingStarted) {
            writingStarted = true
            onEvent(NfcType4Event.WritingStarted)
        }
        if (offset == 0 && length >= 2 && command[5] == 0.toByte() && command[6] == 0.toByte()) {
            resetIncoming()
            writingStarted = true
        }
        command.copyOfRange(5, command.size).copyInto(incoming, offset)
        for (index in offset until offset + length) written[index] = true
        if (written[0] && written[1]) {
            val candidate = ((incoming[0].toInt() and 0xff) shl 8) or (incoming[1].toInt() and 0xff)
            if (candidate > MAX_NDEF_SIZE) return WRONG_PARAMETERS
            expectedLength = candidate
        }
        if (expectedLength > 0 && (0 until expectedLength + 2).all(written::get)) {
            val file = incoming.copyOfRange(0, expectedLength + 2)
            val payload = runCatching { NfcNdefCodec.parseType4File(file) }.getOrNull()
            resetIncoming()
            if (payload != null) onEvent(NfcType4Event.MessageReceived(payload))
        }
        return OK
    }

    private fun resetExchange() {
        selected = SelectedFile.NONE
        outgoing = ByteArray(0)
        resetIncoming()
    }

    private fun resetIncoming() {
        incoming.fill(0)
        written.fill(false)
        expectedLength = -1
        writingStarted = false
    }

    private fun isSelectAid(command: ByteArray): Boolean {
        if (command.size < 12 || command[0] != 0.toByte() || command[1] != 0xA4.toByte() || command[2] != 0x04.toByte()) return false
        val length = command[4].toInt() and 0xff
        return length == NDEF_AID.size && command.size >= 5 + length && command.copyOfRange(5, 5 + length).contentEquals(NDEF_AID)
    }

    companion object {
        const val MAX_NDEF_SIZE = 0x70ff
        private val NDEF_AID = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        private val SELECT_FILE_HEADER = byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C)
        private val CC_FILE_ID = byteArrayOf(0xE1.toByte(), 0x03)
        private val NDEF_FILE_ID = byteArrayOf(0xE1.toByte(), 0x04)
        private val CC_FILE = byteArrayOf(
            0x00, 0x0F, 0x20, 0x00, 0x3B, 0x00, 0x34, 0x04, 0x06,
            0xE1.toByte(), 0x04, 0x70, 0xFF.toByte(), 0x00, 0x00,
        )
        private val OK = byteArrayOf(0x90.toByte(), 0x00)
        private val FILE_NOT_FOUND = byteArrayOf(0x6A, 0x82.toByte())
        private val WRONG_PARAMETERS = byteArrayOf(0x6B, 0x00)
        private val WRONG_LENGTH = byteArrayOf(0x67, 0x00)
        private val INSTRUCTION_NOT_SUPPORTED = byteArrayOf(0x6D, 0x00)
    }
}
