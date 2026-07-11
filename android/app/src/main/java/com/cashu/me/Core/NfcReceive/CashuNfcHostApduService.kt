package com.cashu.me.Core.NfcReceive

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import com.cashu.me.App.CashuWalletApplication

class CashuNfcHostApduService : HostApduService() {
    private val coordinator: NfcReceiveCoordinator
        get() = (application as CashuWalletApplication).container.nfcReceiveCoordinator

    override fun processCommandApdu(commandApdu: ByteArray?, extras: Bundle?): ByteArray =
        commandApdu?.let { coordinator.type4Tag.process(it) } ?: byteArrayOf(0x67, 0x00)

    override fun onDeactivated(reason: Int) {
        coordinator.type4Tag.deactivate()
    }
}
