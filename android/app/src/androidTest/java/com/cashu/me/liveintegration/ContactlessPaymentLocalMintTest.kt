package com.cashu.me.liveintegration

import com.cashu.me.Core.Services.ContactlessSendCallbacks
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContactlessPaymentLocalMintTest : PaymentFixtureTest() {
    @Test fun approvalCancellationReleasesNativePreparation() = runBlocking {
        val payer = wallet("controlled")
        payer.fund()
        val result = runCatching {
            payer.gateway.sendContactlessToken(21, payer.url, emptyList(), ContactlessSendCallbacks(
                approve = { error("Approval cancelled") }, deliver = { fail("Must not deliver") },
            ))
        }
        assertTrue(result.isFailure)
        assertEquals(100, payer.balance())
        assertTrue(payer.gateway.listPendingSendOperationIds(payer.url).isEmpty())
    }

    @Test fun writeFailureReturnsNativeFundsAcrossReopen() = runBlocking {
        val payer = wallet("controlled")
        val receiver = wallet("controlled")
        payer.fund()
        val result = runCatching {
            payer.gateway.sendContactlessToken(21, payer.url, emptyList(), ContactlessSendCallbacks(
                approve = { assertEquals(21, it) }, deliver = { error("Test write failure") },
            ))
        }
        assertTrue(result.isFailure)
        assertTrue(payer.gateway.listPendingSendOperationIds(payer.url).isEmpty())
        payer.reopen()
        assertEquals(100, payer.balance())
        assertEquals(100, receiver.receive(payer.send(100).token))
    }

    @Test fun successfulDeliveryIsNotRevoked() = runBlocking {
        val payer = wallet("controlled")
        val receiver = wallet("controlled")
        payer.fund()
        payer.gateway.sendContactlessToken(21, payer.url, emptyList(), ContactlessSendCallbacks(
            approve = { assertEquals(21, it) }, deliver = { assertEquals(21, receiver.receive(it)) },
        ))
        assertEquals(79, payer.balance())
    }
}
