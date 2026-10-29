package com.cashu.me.liveintegration

import androidx.test.platform.app.InstrumentationRegistry
import com.cashu.me.Core.Services.NFCPaymentInput
import com.cashu.me.Core.Services.NFCPaymentInputDecoder
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class NfcPaymentRequestCompatibilityTest : PaymentFixtureTest() {
    @Test fun preparedInBandRequestsAwaitCdkSupportWithoutReservingFunds() = runBlocking {
        for (amount in listOf(21L, null)) {
            val payer = wallet("controlled")
            val receiver = wallet("controlled")
            payer.fund()
            val encoded = inBandRequest(payer.url, amount)
            val failure = runCatching {
                payer.gateway.payCashuPaymentRequest(encoded, if (amount == null) 21 else null, payer.url)
            }.exceptionOrNull()
            assertNotNull("Revisit the NFC integration when CDK supports prepared in-band requests", failure)
            assertTrue("The pinned CDK must fail specifically at the in-band transport boundary",
                failure.toString().contains("No transport available in payment request"))
            assertTrue(payer.gateway.listPendingSendOperationIds(payer.url).isEmpty())
            payer.reopen()
            assertEquals(100, payer.balance())
            assertEquals(100, receiver.receive(payer.send(100).token))
        }
    }

    @Test fun fixedNfcRequestAtZeroFeeMint() = runBlocking {
        assertCurrentNfcSend("controlled")
    }

    @Test fun fixedNfcRequestReceiverFeeRegression() = runBlocking {
        assumeTrue("Pending #427 and upstream CDK support; see docs/reviews/nfc-request-payments.md",
            InstrumentationRegistry.getArguments().getString("cashu.paymentKnownRegressions") == "true")
        assertCurrentNfcSend("fees")
    }

    private fun inBandRequest(mint: String, amount: Long?): String =
        call("$root/request", "POST", buildJsonObject {
            put("transport", "none")
            putJsonArray("mints") { add(mint) }
            if (amount != null) put("amount", amount)
        }).getValue("request").jsonPrimitive.content

    private suspend fun assertCurrentNfcSend(mint: String) {
        val payer = wallet(mint)
        val receiver = wallet(mint)
        payer.fund()
        val request = NFCPaymentInputDecoder.decode(inBandRequest(payer.url, 21)) as NFCPaymentInput.CashuRequest
        // Exercise the production gateway currently called by NFCPaymentService.
        val sent = payer.gateway.sendEcashToken(checkNotNull(request.summary.amount), null, null, payer.url)
        val received = receiver.receive(sent.token)
        assertEquals("NFC payment requests must deliver the requested net amount", 21, received)
        assertEquals(100, payer.balance() + 21 + sent.fee)
    }
}
