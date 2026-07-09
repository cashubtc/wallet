package org.cashu.wallet.ui.send

import kotlinx.coroutines.runBlocking
import org.cashu.wallet.Models.MintQuoteInfo
import org.cashu.wallet.Models.MintQuoteState
import org.cashu.wallet.Models.PaymentMethodKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class SendCashuRequestTopUpTest {
    @Test
    fun externalTopUpCreatesBolt11SatMintQuoteForTargetMint() = runBlocking {
        val quote = MintQuoteInfo(
            id = "quote-id",
            request = "lnbc1invoice",
            amount = 42,
            paymentMethod = PaymentMethodKind.Bolt11,
            state = MintQuoteState.Unpaid,
            expiryEpochSeconds = 123,
            mintUrl = "https://mint.example",
        )
        val calls = mutableListOf<String>()

        val created = createExternalTopUpQuote(
            mintUrl = "https://mint.example",
            requestedAmountSats = 42,
        ) { mintUrl, amount, method, unit ->
            calls += "$mintUrl:$amount:${method.rawValue}:$unit"
            quote
        }

        assertSame(quote, created)
        assertEquals(listOf("https://mint.example:42:bolt11:sat"), calls)
    }
}
