package com.cashu.me.Core

import org.junit.Assert.*
import org.junit.Test

class WalletRestorePolicyTest {
    @Test fun sameEntropyReusesLedgerEvenWithDistinctByteArrayInstances() {
        val entropy = ByteArray(16) { it.toByte() }
        assertTrue(reusesExistingWallet(entropy, entropy.copyOf()))
    }

    @Test fun differentOrMissingIdentityRequiresIsolatedDatabase() {
        val entropy = ByteArray(16) { it.toByte() }
        assertFalse(reusesExistingWallet(null, entropy))
        assertFalse(reusesExistingWallet(entropy, entropy.copyOf().apply { this[0] = 1 }))
        assertFalse(reusesExistingWallet(entropy, entropy.copyOf(32)))
    }
}
