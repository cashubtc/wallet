package com.cashu.me.liveintegration

import androidx.test.platform.app.InstrumentationRegistry
import com.cashu.me.Models.MintQuoteState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** Exercise the production gateway subscription against each mint directly. */
class LiveMintSubscriptionTest : PaymentFixtureTest() {
    @Test fun cdkQuoteSubscriptionAndIssuance() = runBlocking { observeAndMint("cdk", 3339) }
    @Test fun nutshellQuoteSubscriptionAndIssuance() = runBlocking { observeAndMint("nutshell", 3338) }

    private suspend fun observeAndMint(mint: String, port: Int) {
        val mintUrl = InstrumentationRegistry.getArguments().getString("cashu.${mint}MintUrl")
            ?: "http://127.0.0.1:$port"
        val receiver = wallet(mint, mintUrl)
        val quote = receiver.quote(21)
        val paid = withTimeout(20_000) {
            receiver.gateway.subscribeToMintQuote(quote.id) { true }.first { it.state == MintQuoteState.Paid }
        }
        assertEquals(quote.id, paid.id)
        assertEquals(21, receiver.gateway.mintTokens(quote.id))
        receiver.reopen()
        assertEquals(21, receiver.balance())
    }
}
