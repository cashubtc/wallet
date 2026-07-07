package org.cashu.wallet.Core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PendingReceiveTokenIdsTest {
    @Test
    fun stablePendingReceiveTokenIdIgnoresOuterWhitespace() {
        assertEquals(
            stablePendingReceiveTokenId(" cashuA-token "),
            stablePendingReceiveTokenId("cashuA-token"),
        )
    }

    @Test
    fun stablePendingReceiveTokenIdUsesFullTokenNotPrefix() {
        val sharedPrefix = "cashuA" + "x".repeat(128)

        assertNotEquals(
            stablePendingReceiveTokenId(sharedPrefix + "a"),
            stablePendingReceiveTokenId(sharedPrefix + "b"),
        )
    }
}
