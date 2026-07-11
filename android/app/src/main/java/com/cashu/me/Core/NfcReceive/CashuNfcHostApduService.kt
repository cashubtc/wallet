package com.cashu.me.Core.NfcReceive

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.cashu.me.App.CashuWalletApplication

class CashuNfcHostApduService : HostApduService() {
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private var isWriting = false
    private val timeout = Runnable {
        coordinator?.onTransportTimeout(isWriting)
        isWriting = false
    }
    private val coordinator: NfcReceiveCoordinator?
        get() = (application as CashuWalletApplication).container.value?.nfcReceiveCoordinator

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray {
        val command = commandApdu ?: return byteArrayOf(0x67, 0x00)
        if (isSelectAid(command)) isWriting = false
        if (command.size >= 2 && command[0] == 0.toByte() && command[1] == 0xD6.toByte()) {
            isWriting = true
        }
        val coordinator = coordinator ?: run {
            isWriting = false
            return byteArrayOf(0x69, 0x85.toByte())
        }
        val response = coordinator.type4Tag.process(command)
        timeoutHandler.removeCallbacks(timeout)
        if (coordinator.isAdvertising) {
            timeoutHandler.postDelayed(timeout, NFC_TIMEOUT_MS)
        } else {
            isWriting = false
        }
        return response
    }

    override fun onDeactivated(reason: Int) {
        // Match Numo: brief RF deactivation is not failed immediately. The
        // inactivity timer decides whether this was a harmless read-only tap
        // or a connection loss in the middle of UPDATE BINARY.
    }

    override fun onDestroy() {
        timeoutHandler.removeCallbacks(timeout)
        super.onDestroy()
    }

    private fun isSelectAid(command: ByteArray): Boolean =
        command.size >= 2 && command[0] == 0.toByte() && command[1] == 0xA4.toByte() &&
            command.size >= 3 && command[2] == 0x04.toByte()

    private companion object {
        const val NFC_TIMEOUT_MS = 3_500L
    }
}
