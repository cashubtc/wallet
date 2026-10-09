package com.cashu.me.Core

/** Compare BIP39 identity, never the seed's presentation or a cached UI value. */
internal fun reusesExistingWallet(previousEntropy: ByteArray?, restoredEntropy: ByteArray): Boolean =
    previousEntropy?.contentEquals(restoredEntropy) == true
