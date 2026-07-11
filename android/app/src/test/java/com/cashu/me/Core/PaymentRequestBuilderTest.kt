package com.cashu.me.Core

import com.cashu.me.Models.CashuRequest
import com.cashu.me.Models.CashuRequestPayment
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentRequestBuilderTest {
    @Test
    fun nprofileEncodesPubkeyAndRelays() {
        val pubkeyHex = NostrService.publicKeyHex(PRIVATE_KEY_HEX)
        val nprofile = PaymentRequestBuilder.makeNprofile(
            pubkeyHex = pubkeyHex,
            relays = listOf("wss://relay.example"),
        )
        val decoded = Bech32.decode("nprofile", nprofile)

        assertEquals(0, decoded[0].toInt())
        assertEquals(32, decoded[1].toInt())
        assertEquals(pubkeyHex, decoded.copyOfRange(2, 34).hex())
        assertEquals(1, decoded[34].toInt())
        assertEquals("wss://relay.example".length, decoded[35].toInt())
    }

    @Test
    fun builderCreatesSwiftCompatibleCashuRequestEncoding() {
        val encoded = PaymentRequestBuilder.build(
            id = "request-id",
            amount = 42,
            unit = "sat",
            mints = listOf("https://mint.example"),
            description = "coffee",
            nostrPubkeyHex = NostrService.publicKeyHex(PRIVATE_KEY_HEX),
            relays = listOf("wss://relay.example"),
        )

        assertEquals(
            "creqAp2FpanJlcXVlc3QtaWRhYRgqYXVjc2F0YXP2YW2BdGh0dHBzOi8vbWludC5leGFtcGxlYWRmY29mZmVlYXSBo2F0ZW5vc3RyYWF4Z25wcm9maWxlMXFxczhuMG54MG11YWV3YXYya3N4OTl3d3N1OXN3cTVtbG5kam1uM2dtOXZsOXEybXptdXAweHFwemRtaHh1ZTY5dWhoeWV0dnY5dWp1ZXRjdjlraHFtcjk5bXZ0NTRhZ4GCYW5iMTc=",
            encoded,
        )
        val cbor = Base64.getUrlDecoder().decode(encoded.removePrefix("creqA"))
        assertEquals(0xA7.toByte(), cbor.first())
    }

    @Test
    fun nfcBuilderOmitsRemoteTransportsAndPreservesTerms() {
        val encoded = PaymentRequestBuilder.buildNfc(
            id = "tap-1",
            amount = 42,
            unit = "sat",
            mints = listOf("https://mint.example"),
            description = "coffee",
        )

        val request = PaymentRequestDecoder.cashuPaymentRequestSummary(encoded)!!
        assertEquals(42L, request.amount)
        assertEquals("sat", request.unit)
        assertEquals(listOf("https://mint.example"), request.mints)
        // Six top-level CBOR fields: i, a, u, s, m, d. A transport-bearing
        // request has a seventh `t` field and starts with A7 instead.
        val cbor = Base64.getUrlDecoder().decode(encoded.removePrefix("creqA"))
        assertEquals(0xA6.toByte(), cbor.first())
    }

    @Test
    fun cashuRequestLegacyPaymentIdsArePreservedAsZeroAmountPayments() {
        val request = CashuRequest(
            id = "abc",
            encoded = "creqA-test",
            createdAtEpochMillis = 1234,
            receivedPaymentIds = listOf("legacy-tx"),
        ).withLegacyPaymentFallback()

        assertEquals(
            listOf(CashuRequestPayment("legacy-tx", amount = 0, receivedAtEpochMillis = 1234)),
            request.receivedPayments,
        )
    }

    private companion object {
        private const val PRIVATE_KEY_HEX = "0000000000000000000000000000000000000000000000000000000000000001"

        private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
    }
}
