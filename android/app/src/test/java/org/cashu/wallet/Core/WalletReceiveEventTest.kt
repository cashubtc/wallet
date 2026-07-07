package org.cashu.wallet.Core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WalletReceiveEventTest {
    @Test
    fun homeDeltaOnlyShowsPositiveSatReceives() {
        assertTrue(
            WalletReceiveEvent(
                id = 1,
                amount = 21,
                unit = "sat",
                source = WalletReceiveSource.Ecash,
            ).showsHomeSatDelta(),
        )
        assertTrue(
            WalletReceiveEvent(
                id = 4,
                amount = 1,
                unit = "SAT",
                source = WalletReceiveSource.CashuRequest,
            ).showsHomeSatDelta(),
        )
        assertFalse(
            WalletReceiveEvent(
                id = 2,
                amount = 21,
                unit = "eur",
                source = WalletReceiveSource.Ecash,
            ).showsHomeSatDelta(),
        )
        assertFalse(
            WalletReceiveEvent(
                id = 3,
                amount = 0,
                unit = "sat",
                source = WalletReceiveSource.Lightning,
            ).showsHomeSatDelta(),
        )
    }
}
