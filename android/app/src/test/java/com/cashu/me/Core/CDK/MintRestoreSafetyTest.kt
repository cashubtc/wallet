package com.cashu.me.Core.CDK

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MintRestoreSafetyTest {
    private val mint = "https://mint.example"
    private fun saga(kind: String, url: String = mint) = """{"kind":"$kind","mint_url":"$url"}"""

    @Test fun unrelatedSendDoesNotBlockIssueCounterRecovery() {
        assertTrue(mintRestoreAllowed(listOf(saga("send", "https://other.example"), saga("mint")), mint, false))
    }
    @Test fun sameMintInputOperationsRemainProtected() {
        for (kind in listOf("send", "melt", "swap", "receive", "unknown")) {
            assertFalse(mintRestoreAllowed(listOf(saga(kind, "https://MINT.example/")), mint, false))
        }
        assertFalse(mintRestoreAllowed(listOf(saga("mint")), mint, true))
    }
    @Test fun malformedJournalFailsClosed() {
        for (json in listOf("invalid", "{}", """{"kind":"mint"}""")) {
            assertFalse(mintRestoreAllowed(listOf(json), mint, false))
        }
    }
}
